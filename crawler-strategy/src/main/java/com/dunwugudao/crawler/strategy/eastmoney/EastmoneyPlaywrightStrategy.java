package com.dunwugudao.crawler.strategy.eastmoney;

import com.dunwugudao.crawler.core.config.AntiCrawlConfig;
import com.dunwugudao.crawler.core.model.CrawlContext;
import com.dunwugudao.crawler.core.model.CrawlResult;
import com.dunwugudao.crawler.core.model.CrawlTask;
import com.dunwugudao.crawler.core.model.SourceType;
import com.dunwugudao.crawler.core.strategy.SourceStrategy;
import com.dunwugudao.crawler.core.util.JsonCheckpoint;
import com.dunwugudao.crawler.strategy.tonghuashun.BrowserContextFactory;
import com.dunwugudao.crawler.strategy.tonghuashun.BrowserPool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 东财 Playwright 策略（绕过 TLS 指纹拦截）。
 * <p>用于 push2his / push2 端点（STOCK_DAILY / STOCK_WEEKLY / INDEX_DAILY）等 OkHttp 被 446/460 拒绝的端点。
 * 复用同花顺的 BrowserContextFactory + stealth + 代理，解析委托 {@link EastmoneyParsers}。</p>
 */
public class EastmoneyPlaywrightStrategy implements SourceStrategy {

    private final AntiCrawlConfig antiCrawlConfig;
    private final BrowserPool browserPool;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 青果长效 IP（fallback 用）。 */
    private WorkerProxyManager workerProxyManager;

    public EastmoneyPlaywrightStrategy(AntiCrawlConfig antiCrawlConfig, BrowserPool browserPool) {
        this.antiCrawlConfig = antiCrawlConfig;
        this.browserPool = browserPool;
    }

    /** 注入青果长效 IP 管理器（由 StrategyFactoryConfig 装配）。 */
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
        Map<String, Object> params = JsonCheckpoint.deserialize(task.getParamsJson());
        String taskType = task.getTaskType();

        // 构建 URL（复用 EastmoneyEndpoints，注入 pn / secid / date）
        EastmoneyEndpoints.EndpointSpec spec = EastmoneyEndpoints.get(taskType);
        int pn = EastmoneyEndpoints.parseInt(params.getOrDefault("pn", 1), 1);
        String url = spec.buildUrl(params, pn);

        Browser browser = browserPool.acquire();
        // 代理来自青果长效 IP（WorkerProxyManager），没有则无代理
        String proxy = workerProxyManager != null ? workerProxyManager.getProxy() : null;
        BrowserContext context = new BrowserContextFactory().newContext(browser, antiCrawlConfig, hostOf(url), proxy);

        try (Page page = context.newPage()) {
            // 访问 API
            com.microsoft.playwright.Response resp = page.navigate(url, new Page.NavigateOptions().setTimeout(30_000));
            String body = resp.text();

            // 解析 JSON
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");

            List<Map<String, Object>> rows = new ArrayList<>();
            if (spec.getParserType() == EastmoneyEndpoints.ParserType.CLIST) {
                // STOCK_DAILY：全市场快照（clist）
                String tradeDate = EastmoneyEndpoints.tradeDate(params);
                rows = EastmoneyParsers.parseClist(data, spec, tradeDate, params);
            } else {
                // STOCK_WEEKLY / INDEX_DAILY：kline
                rows = EastmoneyParsers.parseKline(data, spec, params);
            }

            CrawlResult result = new CrawlResult();
            result.setSuccess(true);
            result.setData(rows);
            result.setUrl(url);
            result.setRowCount(rows.size());
            result.setHttpStatus(resp.status());
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Eastmoney Playwright fetch failed: " + e.getMessage(), e);
        } finally {
            context.close();
        }
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return "eastmoney.com";
        }
    }
}
