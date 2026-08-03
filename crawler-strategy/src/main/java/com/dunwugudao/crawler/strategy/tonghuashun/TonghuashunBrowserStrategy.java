package com.dunwugudao.crawler.strategy.tonghuashun;

import com.dunwugudao.crawler.core.config.AntiCrawlConfig;
import com.dunwugudao.crawler.core.model.CrawlContext;
import com.dunwugudao.crawler.core.model.CrawlResult;
import com.dunwugudao.crawler.core.model.CrawlTask;
import com.dunwugudao.crawler.core.model.SourceType;
import com.dunwugudao.crawler.core.strategy.SourceStrategy;
import com.dunwugudao.crawler.core.util.JsonCheckpoint;
import com.dunwugudao.crawler.core.util.RateLimiter;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 同花顺浏览器策略（M2 深化，真实 Chromium + Playwright）。
 * <p>supports 返回 source == TONGHUASHUN。fetch 从 {@link BrowserPool} 取常驻浏览器 →
 * {@link BrowserContextFactory} 建带反爬配置的上下文 → 导航 → 等待选择器 →
 * （可选）滚动到底稳定 → （可选）按 params.extract 结构化抽取 → 设置 CrawlResult。</p>
 *
 * <p>说明：同花顺具体 DOM 选择器需 M6 端到端实测配置；本类提供通用框架 + 注入点
 * （waitSelector / scrollUntilStable / extract.selector+cols）。</p>
 */
public class TonghuashunBrowserStrategy implements SourceStrategy {

    private final AntiCrawlConfig antiCrawlConfig;
    private final BrowserPool browserPool;
    private final BrowserContextFactory contextFactory = new BrowserContextFactory();
    private final RateLimiter rateLimiter;

    public TonghuashunBrowserStrategy(AntiCrawlConfig antiCrawlConfig, BrowserPool browserPool) {
        this.antiCrawlConfig = antiCrawlConfig;
        this.browserPool = browserPool;
        this.rateLimiter = new RateLimiter(antiCrawlConfig.getRateLimitPerSec());
    }

    @Override
    public boolean supports(SourceType source) {
        return source == SourceType.TONGHUASHUN;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CrawlResult fetch(CrawlContext ctx) {
        CrawlTask task = ctx.getTask();
        Map<String, Object> params = JsonCheckpoint.deserialize(task.getParamsJson());
        String url = task.getUrl();
        if (url == null || url.isBlank()) {
            throw new RuntimeException("Tonghuashun task missing url");
        }

        rateLimiter.acquire();

        Browser browser = browserPool.acquire(antiCrawlConfig);
        BrowserContext context = null;
        try {
            String host = BrowserContextFactory.hostOf(url);
            context = contextFactory.newContext(browser, antiCrawlConfig, host);

            try (Page page = context.newPage()) {
                page.navigate(url);

                String waitSelector = String.valueOf(params.getOrDefault("waitSelector", "body"));
                page.waitForSelector(waitSelector, new Page.WaitForSelectorOptions().setTimeout(30_000));

                if (Boolean.parseBoolean(String.valueOf(params.getOrDefault("scrollUntilStable", "false")))) {
                    scrollUntilStable(page);
                }

                // 结构化抽取（可选）
                Object extract = params.get("extract");
                List<Map<String, Object>> rows = null;
                if (extract instanceof Map) {
                    Map<String, Object> ex = (Map<String, Object>) extract;
                    String selector = String.valueOf(ex.get("selector"));
                    Object colsObj = ex.get("cols");
                    Map<String, String> cols = (colsObj instanceof Map)
                            ? (Map<String, String>) colsObj : new HashMap<>();
                    String tradeDate = tradeDateOf(params);
                    rows = extractRows(page, selector, cols, tradeDate);
                }

                String raw = page.content();

                CrawlResult result = new CrawlResult();
                result.setSuccess(true);
                result.setData(rows);
                result.setRaw(raw);
                result.setRowCount(rows != null ? rows.size() : raw.length());
                result.setHttpStatus(200);
                return result;
            }
        } catch (Exception e) {
            throw new RuntimeException("Tonghuashun browser fetch failed: " + e.getMessage(), e);
        } finally {
            if (context != null) {
                try {
                    context.close(); // 关闭上下文释放页面；浏览器常驻
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 循环向下滚动直到连续两次页面高度不变（最多 20 次），用于无限滚动/懒加载。 */
    private void scrollUntilStable(Page page) {
        int stableCount = 0;
        int prevHeight = -1;
        for (int i = 0; i < 20; i++) {
            page.mouse().wheel(0, 2000);
            try {
                Thread.sleep(800);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            int height = ((Number) page.evaluate("document.body.scrollHeight")).intValue();
            if (height == prevHeight) {
                stableCount++;
                if (stableCount >= 2) {
                    break;
                }
            } else {
                stableCount = 0;
            }
            prevHeight = height;
        }
    }

    /** 按 colToField（目标列→子选择器/索引）从 selector 匹配的元素中抽取文本。 */
    private List<Map<String, Object>> extractRows(Page page, String selector,
                                                  Map<String, String> colToField, String tradeDate) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<ElementHandle> els = page.querySelectorAll(selector);
        for (ElementHandle el : els) {
            Map<String, Object> row = new HashMap<>();
            for (Map.Entry<String, String> e : colToField.entrySet()) {
                String col = e.getKey();       // 目标 schema 列名
                String cellSpec = e.getValue(); // 子选择器或数字索引
                String text = extractCell(el, cellSpec);
                if (text != null && !text.isBlank()) {
                    row.put(col, text.trim());
                }
            }
            // 分区键必需：每行带 trade_date（无则下游无法落库）
            if (tradeDate != null) {
                row.put("trade_date", tradeDate);
            }
            rows.add(row);
        }
        return rows;
    }

    private String extractCell(ElementHandle el, String cellSpec) {
        if (cellSpec != null && cellSpec.matches("\\d+")) {
            // 数字索引：取第 N 个子节点文本
            int idx = Integer.parseInt(cellSpec);
            List<ElementHandle> children = el.querySelectorAll(":scope > *");
            if (idx < children.size()) {
                return children.get(idx).innerText();
            }
            return null;
        }
        ElementHandle sub = (cellSpec == null || cellSpec.isBlank()) ? null : el.querySelector(cellSpec);
        if (sub != null) {
            return sub.innerText();
        }
        return el.innerText();
    }

    private static String tradeDateOf(Map<String, Object> params) {
        Object v = params.get("tradeDate");
        if (v == null) {
            v = params.get("date");
        }
        return v == null ? null : String.valueOf(v);
    }
}
