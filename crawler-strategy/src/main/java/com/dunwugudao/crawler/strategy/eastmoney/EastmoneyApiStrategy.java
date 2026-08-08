package com.dunwugudao.crawler.strategy.eastmoney;

import com.dunwugudao.crawler.core.config.AntiCrawlConfig;
import com.dunwugudao.crawler.core.model.CrawlContext;
import com.dunwugudao.crawler.core.model.CrawlResult;
import com.dunwugudao.crawler.core.model.CrawlTask;
import com.dunwugudao.crawler.core.model.SourceType;
import com.dunwugudao.crawler.core.strategy.SourceStrategy;
import com.dunwugudao.crawler.core.util.JsonCheckpoint;
import com.dunwugudao.crawler.core.util.RateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.Proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 东方财富 HTTP/JSON API 策略（OkHttp 路径）。
 * <p>supports 返回 source == EASTMONEY。fetch 依据 {@code task.taskType} 从
 * {@link EastmoneyEndpoints} 取端点规格，构建 URL（注入 tradeDate/secid/分页），用
 * {@link EastmoneyClient} 执行；按 parserType 调用 {@link EastmoneyParsers} 对应解析器，
 * 归一化为 {@code List<Map<String,Object>>}（key=schema 列名，必带 trade_date），设置 CrawlResult。</p>
 *
 * <p>能力：分页(clist 用 pages；池/Datacenter 用「返回空即停」)、字段映射对齐 PART A 原始表、
 * UA 随机 + 代理(perSource) + 令牌桶限速。失败抛 RuntimeException 交 worker RetryPolicy。</p>
 *
 * <p>STOCK_DAILY / STOCK_WEEKLY / INDEX_DAILY 因 TLS 指纹拦截，委托
 * {@link EastmoneyPlaywrightStrategy} 走 Playwright 路径（本类不执行）。</p>
 *
 * <p>策略：OkHttp + 快代理优先；失败自动 fallback 到 Playwright + 青果长效 IP。</p>
 */
public class EastmoneyApiStrategy implements SourceStrategy {

    private static final Logger log = LoggerFactory.getLogger(EastmoneyApiStrategy.class);

    private static final int POOL_MAX_PAGES = 50; // 池/明细安全上限（配合「空即停」）

    /**
     * 任务级：一个任务最多使用几个代理 IP。
     * <p>worker 接新任务时 {@link CrawlContext#proxyFetchCount} 重建归零，所以这是"每个任务"的额度，
     * 而非 worker 级总额度。每个 IP 失败后退避重试；用尽后不再换新 IP，直接失败交 worker 走 RETRY/DEAD。</p>
     */
    public static final int MAX_PROXY_FETCH_ATTEMPTS_PER_TASK = 10;

    /** 换 IP 间的指数退避：base * 2^(used-1)，单位毫秒。第 1 次换 IP 等 2s，第 2 次 4s，第 3 次 8s…… */
    private static final long PROXY_BACKOFF_BASE_MS = 2000L;
    private static final long PROXY_BACKOFF_CAP_MS = 30000L;

    private final AntiCrawlConfig antiCrawlConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EastmoneyClient client = new EastmoneyClient();
    /** Worker 级 IP 管理：1 worker = 1 IP，失败后才换新。替换原 ProxyClient 按请求取 IP。 */
    private WorkerProxyManager workerProxyManager;
    private final RateLimiter rateLimiter;
    private final Random random = new Random();
    /** Playwright 策略（用于 push2his / push2 端点绕过 TLS 指纹拦截）。 */
    private EastmoneyPlaywrightStrategy playwrightStrategy;

    public EastmoneyApiStrategy(AntiCrawlConfig antiCrawlConfig) {
        this.antiCrawlConfig = antiCrawlConfig;
        this.rateLimiter = new RateLimiter(antiCrawlConfig.getRateLimitPerSec());
    }

    /** 注入 Playwright 策略（由 StrategyFactoryConfig 装配）。 */
    public void setPlaywrightStrategy(EastmoneyPlaywrightStrategy playwrightStrategy) {
        this.playwrightStrategy = playwrightStrategy;
    }

    /**
     * 注入 WorkerProxyManager（由 StrategyFactoryConfig 在装配时传入）。
     * 必须在 worker 启动前调用，否则 fetch 会因 manager 为 null 失败。
     */
    public void setWorkerProxyManager(WorkerProxyManager workerProxyManager) {
        this.workerProxyManager = workerProxyManager;
    }

    @Override
    public boolean supports(SourceType source) {
        return source == SourceType.EASTMONEY;
    }

    @Override
    public CrawlResult fetch(CrawlContext ctx) {
        CrawlTask task = ctx.getTask();
        String taskType = task.getTaskType();

        // STOCK_WEEKLY / INDEX_DAILY：kline 端点，OkHttp 一贯被 TLS 指纹拦截，直接走 Playwright + 青果
        if ("STOCK_WEEKLY".equals(taskType) || "INDEX_DAILY".equals(taskType)) {
            return playwrightStrategy.fetch(ctx);
        }

        // 其他东财请求（含 STOCK_DAILY）：先试 OkHttp + 快代理
        // STOCK_DAILY 纯 OkHttp（push2 clist 不被 TLS 拦截，无需 Playwright 兜底）；
        // 其它 CLIST 类型保留 Playwright + 青果 fallback 安全网
        try {
            return fetchWithOkHttp(ctx, taskType);
        } catch (Exception e) {
            if ("STOCK_DAILY".equals(taskType) || "NORTHBOUND_FLOW".equals(taskType)) {
                // STOCK_DAILY / NORTHBOUND_FLOW 不 fallback：前者走 Playwright 会 TLS 拦截，
                // 后者 Playwright 路径无 KAMT 解析器（会静空），均直接抛交 worker 重试/死亡
                log.error("[{}] OkHttp failed ({})，无 Playwright fallback，交 worker 重试", taskType, e.getMessage(), e);
                throw e;
            }
            // OkHttp 路径失败（代理级错误/446/EMPTY_RESPONSE 等）→ 立即 fallback Playwright + 青果长效 IP
            log.warn("[{}] OkHttp failed ({}), fallback to Playwright + QgLongTerm", taskType, e.getMessage());
            try {
                return playwrightStrategy.fetch(ctx);
            } catch (Exception e2) {
                // Playwright 也失败 → 抛出异常，让 worker 重试/死亡
                log.error("[{}] Playwright fallback also failed: {}", taskType, e2.getMessage(), e2);
                throw e2;
            }
        }
    }

    /**
     * OkHttp 路径（快代理私密代理）。
     * <p>成功直接返回；抛异常由 {@link #fetch(CrawlContext)} 捕获并 fallback 到 Playwright。</p>
     */
    private CrawlResult fetchWithOkHttp(CrawlContext ctx, String taskType) {
        CrawlTask task = ctx.getTask();
        Map<String, Object> params = JsonCheckpoint.deserialize(task.getParamsJson());
        EastmoneyEndpoints.EndpointSpec spec = EastmoneyEndpoints.get(taskType);

        // 每个任务类型独立方法,边界清晰
        return switch (spec.getParserType()) {
            case CLIST -> fetchClist(ctx, taskType, params, spec);
            case ZT_POOL, DATACENTER -> fetchZtPoolOrDatacenter(ctx, taskType, params, spec);
            case KLINE -> fetchKline(ctx, taskType, params, spec);
            case KAMT -> fetchKamt(ctx, taskType, params, spec);
        };
    }

    /** CLIST 类型单独处理:STOCK_DAILY/board_daily/MAIN_FUND_* 等 */
    private CrawlResult fetchClist(CrawlContext ctx, String taskType, Map<String, Object> params, EastmoneyEndpoints.EndpointSpec spec) {
        String td = EastmoneyEndpoints.requireTradeDate(params);
        int pn = EastmoneyEndpoints.parseInt(params.get("pn"), 1);
        rateLimiter.acquire();
        String url = spec.buildUrl(params, pn);
        String resp = fetchWithWorkerProxy(url, randomUa(), ctx);
        resp = cleanJsonp(resp);
        JsonNode root = readTree(resp);
        JsonNode data = root.path("data");
        // DEBUG: 打印 CLIST 解析摘要（total/diff数量）
        log.debug("[fetchClist] taskType={}, pn={}, total={}, diffSize={}", taskType, pn,
                data.path("total").asInt(-1),
                data.path("diff").isArray() ? data.path("diff").size() : 0);
        List<Map<String, Object>> allRows = EastmoneyParsers.parseClist(data, spec, td, params);
        return buildResult(allRows, resp, url);
    }

    /** ZT_POOL/DATACENTER 单独处理:涨停池/跌停池/龙虎榜 */
    private CrawlResult fetchZtPoolOrDatacenter(CrawlContext ctx, String taskType, Map<String, Object> params, EastmoneyEndpoints.EndpointSpec spec) {
        String td = EastmoneyEndpoints.requireTradeDate(params);
        List<Map<String, Object>> allRows = new ArrayList<>();
        String lastRaw = "";
        String lastUrl = "";
        int pageIdx = 0;
        while (pageIdx < POOL_MAX_PAGES) {
            rateLimiter.acquire();
            String url = spec.buildUrl(params, pageIdx);
            String resp = fetchWithWorkerProxy(url, randomUa(), ctx);
            lastRaw = resp;
            lastUrl = url;
            String cleaned = cleanJsonp(resp);
            JsonNode root = readTree(cleaned);
            List<Map<String, Object>> rows;
            if (spec.getParserType() == EastmoneyEndpoints.ParserType.ZT_POOL) {
                rows = EastmoneyParsers.parseZtPool(root.path("data"), spec, params);
                allRows.addAll(rows);
                break;
            } else {
                rows = EastmoneyParsers.parseDatacenter(root.path("data"), spec, params);
                if (rows.isEmpty()) {
                    break;
                }
                allRows.addAll(rows);
                pageIdx++;
            }
        }
        return buildResult(allRows, lastRaw, lastUrl);
    }

    /** KLINE 单独处理:个股/指数日周线 */
    private CrawlResult fetchKline(CrawlContext ctx, String taskType, Map<String, Object> params, EastmoneyEndpoints.EndpointSpec spec) {
        rateLimiter.acquire();
        String url = spec.buildUrl(params, 1);
        String resp = fetchWithWorkerProxy(url, randomUa(), ctx);
        resp = cleanJsonp(resp);
        JsonNode root = readTree(resp);
        List<Map<String, Object>> allRows = EastmoneyParsers.parseKline(root.path("data"), spec, params);
        return buildResult(allRows, resp, url);
    }

    /** 北向资金（kamt 实时端点，纯 JSON 非 JSONP，无需 cleanJsonp）。 */
    private CrawlResult fetchKamt(CrawlContext ctx, String taskType, Map<String, Object> params, EastmoneyEndpoints.EndpointSpec spec) {
        rateLimiter.acquire();
        String url = spec.buildUrl(params, 0);
        String resp = fetchWithWorkerProxy(url, randomUa(), ctx);
        // kamt 返回纯 JSON（非 JSONP），直接解析
        JsonNode root = readTree(resp);
        List<Map<String, Object>> allRows = EastmoneyParsers.parseNorthbound(root, spec, params);
        return buildResult(allRows, resp, url);
    }

    /** 构建 CrawlResult */
    private CrawlResult buildResult(List<Map<String, Object>> allRows, String lastRaw, String lastUrl) {
        CrawlResult result = new CrawlResult();
        result.setSuccess(true);
        result.setData(allRows);
        result.setRaw(lastRaw);
        result.setUrl(lastUrl);
        result.setRowCount(allRows.size());
        result.setHttpStatus(200);
        return result;
    }

    /**
     * 用代理发请求，带<b>任务级</b>多 IP 重试 + 指数退避。
     * <p>一个任务最多用 {@link #MAX_PROXY_FETCH_ATTEMPTS_PER_TASK} 个 IP（计数存在
     * {@link CrawlContext#proxyFetchCount}，worker 接新任务时重建归零）。
     * 每个 IP 失败（代理级错误）后：指数退避 → 标记失效 → 取新 IP → 重试同一请求；
     * IP 用尽后不再换新，直接抛异常交 worker 走 RETRY/DEAD。</p>
     * <p>业务错误（HTTP 200 但 rc:102 等）不消耗 IP 配额，直接抛异常。</p>
     *
     * @param ctx 任务上下文，携带本任务的 {@code proxyFetchCount}
     */
    private String fetchWithWorkerProxy(String url, String ua, CrawlContext ctx) {
        if (workerProxyManager == null) {
            throw new IllegalStateException("WorkerProxyManager 未注入，请在装配时调用 setWorkerProxyManager()");
        }
        final AtomicInteger proxyFetchCount = ctx.getProxyFetchCount();
        while (true) {
            int used = proxyFetchCount.get(); // 本任务已尝试过的 IP 个数
            String proxy = workerProxyManager.getProxy();
            try {
                String resp = client.get(url, ua, proxy);
                log.info("[fetchWithWorkerProxy] success, proxy={}, usedIp={}/{}, url={}",
                        proxy, used + 1, MAX_PROXY_FETCH_ATTEMPTS_PER_TASK, url);
                return resp;
            } catch (Exception e) {
                boolean proxyFailure = isProxyFailure(e);
                if (proxyFailure && used < MAX_PROXY_FETCH_ATTEMPTS_PER_TASK - 1) {
                    // 还有 IP 配额：指数退避 → 标记失效 → 取新 IP → 重试同一请求
                    long backoffMs = proxyBackoffMs(used);
                    log.warn("[fetchWithWorkerProxy] proxy failed, usedIp={}/{}, backoff {}ms, rotate IP, error={}",
                            used + 1, MAX_PROXY_FETCH_ATTEMPTS_PER_TASK, backoffMs, e.getMessage());
                    workerProxyManager.invalidate();
                    proxyFetchCount.incrementAndGet();
                    sleepInterruptibly(backoffMs);
                    continue;
                }
                // 配额已尽 or 业务错误：不再换 IP，抛异常交 worker
                log.error("[fetchWithWorkerProxy] give up, usedIp={}/{}, proxyFailure={}, error={}",
                        used + 1, MAX_PROXY_FETCH_ATTEMPTS_PER_TASK, proxyFailure, e.getMessage(), e);
                throw e;
            }
        }
    }

    /** 第 used 次换 IP 的退避时长：base * 2^used，封顶。used=0 → 2s。 */
    static long proxyBackoffMs(int used) {
        long delay = PROXY_BACKOFF_BASE_MS * (1L << Math.min(used, 30));
        return Math.min(delay, PROXY_BACKOFF_CAP_MS);
    }

    /** 可中断的 sleep，让 worker 能响应关闭；被中断时恢复中断标志并结束。 */
    private static void sleepInterruptibly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during proxy backoff", ie);
        }
    }

    /**
     * 判断异常是否由代理/IP 问题引起（需要换新 IP），而非目标站点业务错误。
     * <p>代理级错误特征：连接超时、连接重置、SSL 握手失败、407 认证失败、无法建立隧道、
     * 东财 TLS 指纹拦截（446/460 等）、上游空响应/断流（EOF/unexpected end of stream）。</p>
     * <p>注意：{@link EastmoneyClient} 会把 IOException 包装成 RuntimeException 抛出，
     * 所以必须沿 cause 链向上追溯，只看最表层会漏掉 SocketException/EOFException 等真实原因。</p>
     */
    private static boolean isProxyFailure(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
            String cls = t.getClass().getSimpleName().toLowerCase();
            // 连接层面错误
            if (cls.contains("sockettimeout") && msg.contains("connect")) return true;
            if (cls.contains("connectexception") && msg.contains("refused")) return true;
            if (cls.contains("socketexception") && msg.contains("reset")) return true;
            if (cls.contains("ssl") || cls.contains("handshake")) return true;
            // HTTP 407 代理认证失败
            if (msg.contains("407") || msg.contains("proxy authentication")) return true;
            // 无法建立隧道（HTTPS through proxy）
            if (msg.contains("unable to tunnel") || msg.contains("tunnel")) return true;
            // 连接超时（读）通常是代理慢/挂了
            if (cls.contains("sockettimeout") && msg.contains("read")) return true;
            // 东财 TLS 指纹拦截（446/460 等）
            if (msg.contains("eastmoney http 446") || msg.contains("eastmoney http 460")) return true;
            // 上游在响应前断开/返回空响应（EOF、unexpected end of stream）→ 代理级问题，换 IP
            if (cls.contains("eofexception") || msg.contains("unexpected end of stream")) return true;
            // 业务错误（东财返回 rc:102 但 HTTP 200）不在此列——那些是正常响应，不抛异常
        }
        return false;
    }

    // ----------------------------------------------------------------------
    // 工具
    // ----------------------------------------------------------------------

    private JsonNode readTree(String resp) {
        try {
            return objectMapper.readTree(resp);
        } catch (Exception e) {
            log.error("[EastmoneyApiStrategy] JSON parse failed, respLen={}: {}", resp == null ? 0 : resp.length(), e.getMessage(), e);
            throw new RuntimeException("Eastmoney JSON parse failed: " + e.getMessage(), e);
        }
    }

    /**
     * 清洗 JSONP 包裹：东财 push2ex / datacenter 返回 {@code callback({...});}，
     * 需剥掉前缀（第一个 '(' 之前）与后缀（最后一个 ');'）才是合法 JSON。
     * <p>非 JSONP（纯 JSON / 空串）原样返回。</p>
     */
    private String cleanJsonp(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        int lparen = s.indexOf('(');
        int rparen = s.lastIndexOf(')');
        if (lparen > 0 && rparen > lparen && s.endsWith(");")) {
            return s.substring(lparen + 1, rparen);
        }
        return s;
    }

    private String randomUa() {
        List<String> pool = antiCrawlConfig.getUaPool();
        if (pool == null || pool.isEmpty()) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
        }
        return pool.get(random.nextInt(pool.size()));
    }
}
