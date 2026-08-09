package com.dunwugudao.crawler.strategy.tonghuashun;

import com.dunwugudao.crawler.core.config.AntiCrawlConfig;
import com.dunwugudao.crawler.strategy.eastmoney.ProxyProvider;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 同花顺板块爬虫(Playwright 直连代理,不用 CloakBrowser)。
 * <p>优势:</p>
 * <ul>
 *   <li>启动快(1-2s vs Cloak 的 5-10s)</li>
 *   <li>切换代理快(2-3s vs Cloak 的 15-20s)</li>
 *   <li>进程少(1个 vs 2个)</li>
 * </ul>
 * <p>注意:依赖代理质量,同花顺主要检测 IP 而非指纹。</p>
 */
public class ThsPlateDirectCrawler {

    private static final Logger log = LoggerFactory.getLogger(ThsPlateDirectCrawler.class);

    private ProxyProvider proxyProvider;

    /** 板块类型 → 列表页 URL */
    private static final Map<Integer, String> PLATE_TYPE_URL = new HashMap<>();

    static {
        PLATE_TYPE_URL.put(4, "http://q.10jqka.com.cn/dy/");      // 地域
        PLATE_TYPE_URL.put(5, "http://q.10jqka.com.cn/thshy/");   // 行业
        PLATE_TYPE_URL.put(6, "http://q.10jqka.com.cn/gn/");      // 概念
    }

    private static final String HOST = "q.10jqka.com.cn";
    private static final int MAX_RETRIES = 15;

    public void setProxyProvider(ProxyProvider provider) {
        this.proxyProvider = provider;
    }

    /**
     * 抓取指定类型的同花顺板块。
     *
     * @param plateType 4=地域 5=行业 6=概念
     * @param tradeDate 数据日期
     * @param cfg       反爬配置(可 null,不使用)
     * @return 行数据列表
     */
    public List<Map<String, Object>> crawl(int plateType, String tradeDate, AntiCrawlConfig cfg) {
        // cfg 不使用,代理从注入的 proxyProvider 获取
        return crawl(plateType, tradeDate);
    }

    /**
     * 抓取指定类型的同花顺板块(简化版)。
     */
    public List<Map<String, Object>> crawl(int plateType, String tradeDate) {
        String listUrl = PLATE_TYPE_URL.get(plateType);
        if (listUrl == null) {
            log.warn("[ThsPlateDirectCrawler] 未知 plateType={}, 跳过", plateType);
            return new ArrayList<>();
        }

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            String proxy = acquireProxy();
            if (proxy == null) {
                log.error("[ThsPlateDirectCrawler] 获取代理失败,第{}/{}次", attempt, MAX_RETRIES);
                sleep(1000);
                continue;
            }

            log.info("[ThsPlateDirectCrawler] plateType={}, 第{}/{}次, proxy={}",
                    plateType, attempt, MAX_RETRIES, proxy.replaceAll("://.*@", "://***@"));

            try (Playwright pw = Playwright.create()) {
                Browser browser = launchBrowser(pw, proxy);
                try {
                    List<Map<String, Object>> rows = doCrawl(browser, plateType, listUrl, tradeDate);
                    if (!rows.isEmpty()) {
                        log.info("[ThsPlateDirectCrawler] plateType={}, 完成: rows={}, 第{}次成功",
                                plateType, rows.size(), attempt);
                        return rows;
                    }
                    // 返回空(列表页失败),换代理重试
                    log.warn("[ThsPlateDirectCrawler] plateType={}, 返回空,换代理重试", plateType);
                } catch (Exception e) {
                    log.warn("[ThsPlateDirectCrawler] plateType={}, 第{}次失败: {}",
                            plateType, attempt, e.getMessage());
                } finally {
                    browser.close();
                }
            } catch (Exception e) {
                log.warn("[ThsPlateDirectCrawler] plateType={}, 启动浏览器失败: {}",
                        plateType, e.getMessage());
            }

            // 重试前短暂延迟
            sleep(500 + (long) (Math.random() * 500));
        }

        log.error("[ThsPlateDirectCrawler] plateType={}, 重试{}次仍失败", plateType, MAX_RETRIES);
        return new ArrayList<>();
    }

    /**
     * 启动带代理的浏览器。
     */
    private Browser launchBrowser(Playwright pw, String proxy) {
        BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions()
                .setHeadless(true);
        if (proxy != null && !proxy.isBlank()) {
            opts.setProxy(parseProxy(proxy));
        }
        return pw.chromium().launch(opts);
    }

    /**
     * 执行抓取(列表页 + 详情页)。
     */
    private List<Map<String, Object>> doCrawl(Browser browser, int plateType,
                                               String listUrl, String tradeDate) {
        List<Map<String, Object>> allRows = new ArrayList<>();

        BrowserContext context = browser.newContext();
        Page listPage = context.newPage();

        try {
            // ===== 列表页 =====
            log.info("[ThsPlateDirectCrawler] 打开列表页: {}", listUrl);
            listPage.navigate(listUrl, new Page.NavigateOptions().setTimeout(15_000));
            listPage.waitForLoadState(LoadState.DOMCONTENTLOADED);
            listPage.waitForSelector(".cate_items",
                    new Page.WaitForSelectorOptions().setTimeout(15_000));
            sleep(1000);

            // 概念页:点击"显示全部"
            if (plateType == 6) {
                clickShowAll(listPage);
            }

            // 收集板块链接
            List<PlateRef> plateRefs = new ArrayList<>();
            for (ElementHandle cateItem : listPage.querySelectorAll(".cate_items")) {
                for (ElementHandle a : cateItem.querySelectorAll("a")) {
                    String href = a.getAttribute("href");
                    String text = a.innerText();
                    if (href != null && !href.isBlank() && text != null && !text.isBlank()) {
                        plateRefs.add(new PlateRef(text.trim(), href.trim()));
                    }
                }
            }
            log.info("[ThsPlateDirectCrawler] plateType={}, 取到 {} 个板块链接",
                    plateType, plateRefs.size());

            if (plateRefs.isEmpty()) {
                return allRows; // 空列表,触发重试
            }

            listPage.close();

            // ===== 详情页 =====
            int successCount = 0;
            int failCount = 0;

            for (PlateRef ref : plateRefs) {
                try {
                    Map<String, Object> row = crawlDetailPage(context, plateType, ref, tradeDate);
                    if (row != null) {
                        allRows.add(row);
                        successCount++;
                    }
                } catch (Exception e) {
                    failCount++;
                    // 连续失败5次,认为代理挂了,抛出异常触发重试
                    if (failCount >= 5) {
                        throw new RuntimeException("连续失败5次,代理可能挂了: " + e.getMessage());
                    }
                }
                // 礼貌延迟
                sleep(300 + (long) (Math.random() * 300));
            }

            log.info("[ThsPlateDirectCrawler] plateType={}, 详情页: 成功={}, 失败={}",
                    plateType, successCount, failCount);

        } finally {
            if (listPage != null && !listPage.isClosed()) {
                listPage.close();
            }
            context.close();
        }

        return allRows;
    }

    /**
     * 抓取单个板块详情页。
     */
    private Map<String, Object> crawlDetailPage(BrowserContext context, int plateType,
                                                 PlateRef ref, String tradeDate) {
        Page page = context.newPage();
        try {
            page.navigate(ref.url, new Page.NavigateOptions().setTimeout(10_000));
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForSelector("h3", new Page.WaitForSelectorOptions().setTimeout(8_000));
            sleep(500);

            Map<String, Object> row = new HashMap<>();
            row.put("plate_type", plateType);
            row.put("plate_name", ref.name);
            row.put("trade_date", tradeDate);

            // 板块代码
            String codeXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[1]/h3/span"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[1]/h3/span";
            row.put("plate_index", safeInnerText(page, codeXpath));

            // 当前价格
            String priceXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[1]/span"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[1]/span";
            row.put("cur_price", parseDecimal(safeInnerText(page, priceXpath)));

            // 涨跌幅
            String increaseXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[6]/dd"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[2]/dl[6]/dd";
            row.put("increase", parsePercent(safeInnerText(page, increaseXpath)));

            // 成交额(亿→元)
            String turnoverXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[10]/dd"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[2]/dl[10]/dd";
            row.put("turnover", parseYi(safeInnerText(page, turnoverXpath)));

            // 成交量(万手→手)
            String volumeXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[5]/dd"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[2]/dl[5]/dd";
            row.put("volume", parseWanShou(safeInnerText(page, volumeXpath)));

            // 上涨家数
            String upXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[8]/dd/span[1]"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[2]/dl[8]/dd/span[1]";
            row.put("up_count", parseInteger(safeInnerText(page, upXpath)));

            // 下跌家数
            String downXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[8]/dd/span[2]"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[2]/dl[8]/dd/span[2]";
            row.put("down_count", parseInteger(safeInnerText(page, downXpath)));

            // 领涨股
            try {
                row.put("lead_stock_name",
                        safeInnerText(page, "#maincont table tbody tr:first-child td:eq(2) a"));
                row.put("lead_stock_code",
                        safeInnerText(page, "#maincont table tbody tr:first-child td:eq(1) a"));
            } catch (Exception ignored) {
            }

            return row;

        } finally {
            page.close();
        }
    }

    // ==================== 工具方法 ====================

    private String acquireProxy() {
        if (proxyProvider == null) return null;
        try {
            return proxyProvider.acquire();
        } catch (Exception e) {
            log.warn("[ThsPlateDirectCrawler] acquireProxy failed: {}", e.getMessage());
            return null;
        }
    }

    private void clickShowAll(Page page) {
        String[] selectors = {
                "a.more",
                "a:has-text('显示全部')",
                "a:has-text('更多')",
                "xpath=/html/body/div[2]/div[2]/a"
        };
        for (String sel : selectors) {
            try {
                com.microsoft.playwright.Locator btn = page.locator(sel).first();
                if (btn.count() > 0 && btn.isVisible()) {
                    btn.click();
                    page.waitForTimeout(2000);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
    }

    private String safeInnerText(Page page, String xpath) {
        try {
            com.microsoft.playwright.Locator loc = page.locator("xpath=" + xpath).first();
            if (loc.count() > 0) {
                String text = loc.innerText();
                return text != null && !text.isBlank() ? text.trim() : null;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private com.microsoft.playwright.options.Proxy parseProxy(String proxy) {
        if (proxy == null || proxy.isBlank()) return null;
        try {
            URI uri = URI.create(proxy);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || port <= 0) return new com.microsoft.playwright.options.Proxy(proxy);
            String userinfo = uri.getUserInfo();
            if (userinfo != null && userinfo.contains(":")) {
                String[] parts = userinfo.split(":", 2);
                return new com.microsoft.playwright.options.Proxy(host + ":" + port)
                        .setUsername(parts[0]).setPassword(parts[1]);
            }
            return new com.microsoft.playwright.options.Proxy(host + ":" + port);
        } catch (Exception e) {
            return new com.microsoft.playwright.options.Proxy(proxy);
        }
    }

    private BigDecimal parseDecimal(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return new BigDecimal(text.replaceAll("[,\\s]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parsePercent(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return new BigDecimal(text.replace("%", "").replaceAll("[,\\s]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseYi(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String cleaned = text.replace("亿", "").replaceAll("[,\\s]", "");
            return new BigDecimal(cleaned).multiply(new BigDecimal("100000000"));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long parseWanShou(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String cleaned = text.replace("万手", "").replace("手", "").replaceAll("[,\\s]", "");
            return new BigDecimal(cleaned).multiply(new BigDecimal("10000")).longValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInteger(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Integer.parseInt(text.replaceAll("[,\\s]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private record PlateRef(String name, String url) {
    }
}
