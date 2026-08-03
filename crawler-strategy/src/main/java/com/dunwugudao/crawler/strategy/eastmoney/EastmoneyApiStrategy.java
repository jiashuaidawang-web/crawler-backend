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
            if ("STOCK_DAILY".equals(taskType)) {
                // STOCK_DAILY 不 fallback：OkHttp 失败直接抛，交 worker 重试/死亡
                log.warn("[{}] OkHttp failed ({})，无 Playwright fallback，交 worker 重试", taskType, e.getMessage());
                throw e;
            }
            // OkHttp 路径失败（代理级错误/446/EMPTY_RESPONSE 等）→ 立即 fallback Playwright + 青果长效 IP
            log.warn("[{}] OkHttp failed ({}), fallback to Playwright + QgLongTerm", taskType, e.getMessage());
            try {
                return playwrightStrategy.fetch(ctx);
            } catch (Exception e2) {
                // Playwright 也失败 → 抛出异常，让 worker 重试/死亡
                log.error("[{}] Playwright fallback also failed: {}", taskType, e2.getMessage());
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

        String ua = randomUa();

        List<Map<String, Object>> allRows = new ArrayList<>();
        String lastRaw = "";
        String lastUrl = "";

        switch (spec.getParserType()) {
            case CLIST: {
                String td = EastmoneyEndpoints.requireTradeDate(params);
                // STOCK_DAILY 按页拆任务：params.pn 显式传入时只抓这一页，不自动翻页
                if (params.containsKey("pn")) {
                    int pn = EastmoneyEndpoints.parseInt(params.get("pn"), 1);
                    rateLimiter.acquire();
                    String url = spec.buildUrl(params, pn);
                    String resp = fetchWithWorkerProxy(url, ua);
                    lastRaw = resp;
                    JsonNode root = readTree(resp);
                    JsonNode data = root.path("data");
                    allRows.addAll(EastmoneyParsers.parseClist(data, spec, td, params));
                    break;
                }
                int page = 1;
                int totalPages = 1;
                while (true) {
                    rateLimiter.acquire();
                    String url = spec.buildUrl(params, page);
                    String resp = fetchWithWorkerProxy(url, ua);
                    lastRaw = resp;
                    JsonNode root = readTree(resp);
                    JsonNode data = root.path("data");
                    allRows.addAll(EastmoneyParsers.parseClist(data, spec, td, params));
                    // 翻页：优先用 pages；缺失则用 total+pz 算（push2 接口只有 total 无 pages）
                    int pagesFromField = data.path("pages").asInt(0);
                    if (pagesFromField > 0) {
                        totalPages = pagesFromField;
                    } else {
                        int total = data.path("total").asInt(0);
                        int pz = 200;
                        try {
                            pz = Integer.parseInt(String.valueOf(params.getOrDefault("pz", "200")));
                        } catch (NumberFormatException ignored) { }
                        totalPages = (total <= 0) ? 1 : ((total + pz - 1) / pz);
                    }
                    if (page >= totalPages) {
                        break;
                    }
                    page++;
                }
                break;
            }
            case ZT_POOL:
            case DATACENTER: {
                String td = EastmoneyEndpoints.requireTradeDate(params);
                int pageIdx = 0;
                while (pageIdx < POOL_MAX_PAGES) {
                    rateLimiter.acquire();
                    String url = spec.buildUrl(params, pageIdx);
                    String resp = fetchWithWorkerProxy(url, ua);
                    lastRaw = resp;
                    lastUrl = url;
                    String cleaned = cleanJsonp(resp);
                    JsonNode root = readTree(cleaned);
                    List<Map<String, Object>> rows;
                    if (spec.getParserType() == EastmoneyEndpoints.ParserType.ZT_POOL) {
                        rows = EastmoneyParsers.parseZtPool(root.path("data"), spec, params);
                        // 涨停/跌停/强势/次新：一次取全量，无需翻页
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
                break;
            }
            case KLINE: {
                rateLimiter.acquire();
                String url = spec.buildUrl(params, 1);
                String resp = fetchWithWorkerProxy(url, ua);
                lastRaw = resp;
                JsonNode root = readTree(resp);
                allRows.addAll(EastmoneyParsers.parseKline(root.path("data"), spec, params));
                break;
            }
            default:
                throw new UnsupportedOperationException("parserType not handled: " + spec.getParserType());
        }

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
     * 用 Worker 级代理发请求（极简方案：失败后才换新 IP）。
     * <p>与原 {@code fetchWithProxy} 的关键区别：</p>
     * <ul>
     *   <li>不再每次换新 IP——用 worker 当前绑定的同一个 IP。</li>
     *   <li>代理级错误（连接超时/重置/SSL/407）→ 标记失效 → 抛异常 → ClaimLoop 重试时自动换新 IP。</li>
     *   <li>业务错误（HTTP 200 但 rc:102）→ 不标记失效 → 正常返回空数据。</li>
     * </ul>
     */
    private String fetchWithWorkerProxy(String url, String ua) {
        if (workerProxyManager == null) {
            throw new IllegalStateException("WorkerProxyManager 未注入，请在装配时调用 setWorkerProxyManager()");
        }
        String proxy = workerProxyManager.getProxy();
        try {
            String resp = client.get(url, ua, proxy);
            log.info("[fetchWithWorkerProxy] success, proxy={}, url={}", proxy, url);
            return resp;
        } catch (Exception e) {
            // 判断是否为代理级错误（需要换 IP）vs 业务错误（IP 没问题）
            boolean proxyFailure = isProxyFailure(e);
            if (proxyFailure) {
                workerProxyManager.invalidate();
            }
            log.warn("[fetchWithWorkerProxy] failed, proxy={}, proxyFailure={}, error={}",
                    proxy, proxyFailure, e.getMessage());
            throw e;
        }
    }

    /**
     * 判断异常是否由代理/IP 问题引起（需要换新 IP），而非目标站点业务错误。
     * 代理级错误特征：连接超时、连接重置、SSL 握手失败、407 认证失败、无法建立隧道、
     * 东财 TLS 指纹拦截（446/460 等）。
     */
    private static boolean isProxyFailure(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        String cls = e.getClass().getSimpleName().toLowerCase();
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
        // 东财 TLS 指纹拦截（446/460 等）—— 代理级错误，需换 IP
        // EastmoneyClient 抛出的 RuntimeException message 形如 "Eastmoney HTTP 446 for ..."
        if (msg.contains("eastmoney http 446") || msg.contains("eastmoney http 460")) return true;
        // 业务错误（东财返回 rc:102 但 HTTP 200）不在此列——那些是正常响应，不抛异常
        return false;
    }

    // ----------------------------------------------------------------------
    // 工具
    // ----------------------------------------------------------------------

    private JsonNode readTree(String resp) {
        try {
            return objectMapper.readTree(resp);
        } catch (Exception e) {
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
