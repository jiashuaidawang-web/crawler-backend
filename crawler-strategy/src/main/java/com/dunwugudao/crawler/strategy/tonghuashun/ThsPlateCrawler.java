package com.dunwugudao.crawler.strategy.tonghuashun;

import com.dunwugudao.crawler.core.config.AntiCrawlConfig;
import com.dunwugudao.crawler.strategy.eastmoney.ProxyProvider;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 同花顺板块基础维表爬虫（P0-1 THS_PLATE）。
 *
 * <p>抓取同花顺地域/行业/概念板块，流程：
 * <ol>
 *   <li>打开板块列表页（dy/thshy/gn）</li>
 *   <li>概念页点击"显示全部"按钮（默认只显示 20 个，总共 375 个）</li>
 *   <li>遍历 {@code .cate_items > a} 取板块名 + 详情页 URL</li>
 *   <li>（第二阶段）逐个打开详情页，取板块代码（指数代码）+ 行情数据</li>
 * </ol>
 *
 * <p>不实现 {@link com.dunwugudao.crawler.core.strategy.SourceStrategy}，
 * 由 {@link TonghuashunBrowserStrategy} 按 taskType 路由调用。</p>
 *
 * <p>⚠️ 所有 xpath 基于对标项目（market）实测，同花顺 DOM 可能改版，需多选择器兜底。</p>
 */
public class ThsPlateCrawler {

    private static final Logger log = LoggerFactory.getLogger(ThsPlateCrawler.class);

    private final BrowserPool browserPool;
    private final BrowserContextFactory contextFactory;
    private ProxyProvider proxyProvider;

    /** 板块类型 → 列表页 URL */
    private static final Map<Integer, String> PLATE_TYPE_URL = new HashMap<>();

    static {
        PLATE_TYPE_URL.put(4, "http://q.10jqka.com.cn/dy/");      // 地域
        PLATE_TYPE_URL.put(5, "http://q.10jqka.com.cn/thshy/");   // 行业
        PLATE_TYPE_URL.put(6, "http://q.10jqka.com.cn/gn/");      // 概念
    }

    private static final String HOST = "q.10jqka.com.cn";

    public ThsPlateCrawler(BrowserPool browserPool) {
        this.browserPool = browserPool;
        this.contextFactory = new BrowserContextFactory();
    }

    /** 设置代理提供者(青果等) */
    public void setProxyProvider(ProxyProvider provider) {
        this.proxyProvider = provider;
    }

    /**
     * 刷新代理:关闭当前浏览器,重新获取代理并启动新浏览器。
     * <p>短效 IP 场景下,代理过期(407)时调用。</p>
     *
     * @param cfg 反爬配置(用于重启 cloakserve 时获取新代理)
     */
    public void refreshProxy(AntiCrawlConfig cfg) {
        log.info("[ThsPlateCrawler] 刷新代理...");
        // 强制重启 cloakserve + 获取新代理
        CloakServerProcess.restartWithNewProxy(cfg);
    }

    /**
     * 抓取指定类型的同花顺板块。
     *
     * @param plateType 4=地域 5=行业 6=概念
     * @param tradeDate 数据日期（yyyy-MM-dd）
     * @param cfg       反爬配置
     * @return 行数据列表（key=ths_plate 表列名，必含 trade_date）
     */
    public List<Map<String, Object>> crawl(int plateType, String tradeDate, AntiCrawlConfig cfg) {
        String listUrl = PLATE_TYPE_URL.get(plateType);
        if (listUrl == null) {
            log.warn("[ThsPlateCrawler] 未知 plateType={}, 跳过", plateType);
            return new ArrayList<>();
        }

        Browser browser = browserPool.acquire(cfg);
        BrowserContext context = null;
        List<Map<String, Object>> allRows = new ArrayList<>();
        Page listPage = null;

        try {
            context = contextFactory.newContext(browser, cfg, HOST);

            // ===== 第一步：打开板块列表页 =====
            listPage = context.newPage();
            log.info("[ThsPlateCrawler] 打开列表页: plateType={}, url={}", plateType, listUrl);
            listPage.navigate(listUrl, new Page.NavigateOptions().setTimeout(15_000));
            listPage.waitForLoadState(LoadState.NETWORKIDLE);
            listPage.waitForSelector(".cate_items", new Page.WaitForSelectorOptions().setTimeout(15_000));
            Thread.sleep(1500); // 等 JS 渲染

            // 截图（调试用，看页面状态）
            try {
                byte[] screenshot = listPage.screenshot();
                log.info("[ThsPlateCrawler] 列表页截图完成, 字节数={}", screenshot.length);
            } catch (Exception ignored) {
            }

            // 概念页：点击"显示全部"按钮（默认只显示 20 个概念）
            if (plateType == 6) {
                clickShowAll(listPage);
            }

            // ===== 第二步：遍历 .cate_items 下的 <a> 标签 =====
            List<ElementHandle> cateItems = listPage.querySelectorAll(".cate_items");
            log.info("[ThsPlateCrawler] plateType={}, cate_items 容器数量={}", plateType, cateItems.size());

            List<PlateRef> plateRefs = new ArrayList<>();
            for (ElementHandle cateItem : cateItems) {
                List<ElementHandle> links = cateItem.querySelectorAll("a");
                for (ElementHandle a : links) {
                    String href = a.getAttribute("href");
                    String text = a.innerText();
                    if (href != null && !href.isBlank() && text != null && !text.isBlank()) {
                        plateRefs.add(new PlateRef(text.trim(), href.trim()));
                    }
                }
            }
            log.info("[ThsPlateCrawler] plateType={}, 取到 {} 个板块链接", plateType, plateRefs.size());

            // 打印前 10 个链接（调试验证）
            for (int i = 0; i < Math.min(10, plateRefs.size()); i++) {
                PlateRef ref = plateRefs.get(i);
                log.info("[ThsPlateCrawler]   板块[{}]: name={}, url={}", i, ref.name, ref.url);
            }

            // 关闭列表页
            listPage.close();
            listPage = null;

            // ===== 详情页抓取 =====
            int successCount = 0;
            int failCount = 0;
            int proxyRefreshCount = 0;
            final int MAX_PROXY_REFRESH = 5; // 最多刷新 5 次代理

            for (int i = 0; i < plateRefs.size(); i++) {
                PlateRef ref = plateRefs.get(i);
                Map<String, Object> row = null;
                // 每个板块最多重试 3 次(换代理)
                for (int retry = 0; retry < 3; retry++) {
                    try {
                        row = crawlPlateDetail(context, plateType, ref, tradeDate);
                        if (row != null) {
                            break; // 成功
                        }
                    } catch (Exception e) {
                        // 转小写做匹配(错误消息可能是大写 ERR_PROXY_CONNECTION_FAILED)
                        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                        // 代理过期(407)或连接超时/失败,刷新代理后重试
                        if (msg.contains("407") || msg.contains("proxy") || msg.contains("timeout") || msg.contains("targetclosed") || msg.contains("connection")) {
                            if (proxyRefreshCount < MAX_PROXY_REFRESH) {
                                proxyRefreshCount++;
                                log.warn("[ThsPlateCrawler] 代理可能过期(第{}次), 刷新代理: {}", proxyRefreshCount, e.getMessage());
                                refreshProxy(cfg);
                                // 重新获取浏览器和上下文
                                browser = browserPool.acquire(cfg);
                                context = contextFactory.newContext(browser, cfg, HOST);
                                listPage = null; // 列表页已关闭,不需要再关
                            } else {
                                log.warn("[ThsPlateCrawler] 代理刷新次数用尽, 跳过: {}", ref.name);
                            }
                        } else {
                            // 其他错误,不重试
                            log.warn("[ThsPlateCrawler] 板块详情抓取失败: {}, error={}", ref.name, msg);
                            break;
                        }
                    }
                    // 重试前延迟
                    if (retry < 2) {
                        try {
                            Thread.sleep(1000 + (long) (Math.random() * 1000));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                if (row != null) {
                    allRows.add(row);
                    successCount++;
                } else {
                    failCount++;
                }
                // 礼貌延迟,避免反爬
                Thread.sleep(500 + (long) (Math.random() * 500));
            }
            log.info("[ThsPlateCrawler] plateType={}, 详情页完成: 成功={}, 失败={}, 总计={}, 代理刷新={}次",
                    plateType, successCount, failCount, plateRefs.size(), proxyRefreshCount);

        } catch (Exception e) {
            log.error("[ThsPlateCrawler] plateType={} 抓取异常: {}", plateType, e.getMessage(), e);
            // 抛出异常,触发外层重试(换代理)
            throw new RuntimeException("THS_PLATE plateType=" + plateType + " 抓取失败: " + e.getMessage(), e);
        } finally {
            if (listPage != null && !listPage.isClosed()) {
                try {
                    listPage.close();
                } catch (Exception ignored) {
                }
            }
            if (context != null) {
                try {
                    context.close();
                } catch (Exception ignored) {
                }
            }
            // 关闭浏览器,下次任务重新获取新代理(短效 IP 场景)
            try {
                browserPool.closeBrowser();
            } catch (Exception ignored) {
            }
        }
        return allRows;
    }

    /**
     * 点击概念页的"显示全部"按钮。
     * <p>同花顺概念页默认只显示前 20 个，必须点这个按钮才能展示全部 375 个。</p>
     */
    private void clickShowAll(Page page) {
        // 多种选择器兜底（同花顺可能改 DOM）
        String[] selectors = {
                "a.more",
                "a:has-text('显示全部')",
                "a:has-text('更多')",
                ".cate_items a.more",
                "xpath=/html/body/div[2]/div[2]/a"
        };
        for (String sel : selectors) {
            try {
                Locator btn = page.locator(sel).first();
                if (btn.count() > 0 && btn.isVisible()) {
                    log.info("[ThsPlateCrawler] 点击'显示全部'按钮, selector={}", sel);
                    btn.click();
                    page.waitForTimeout(2000); // 等展开动画 + 懒加载
                    // 验证是否展开成功
                    int linkCount = page.querySelectorAll(".cate_items a").size();
                    log.info("[ThsPlateCrawler] 点击后板块链接数={}", linkCount);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        log.warn("[ThsPlateCrawler] 未找到'显示全部'按钮, 继续尝试（可能已默认全量展示）");
    }

    /**
     * 抓取单个板块详情页。
     *
     * @return 行数据，失败返回 null
     */
    private Map<String, Object> crawlPlateDetail(BrowserContext context, int plateType,
                                                  PlateRef ref, String tradeDate) {
        Page page = context.newPage();
        try {
            page.navigate(ref.url, new Page.NavigateOptions().setTimeout(10_000));
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForSelector("h3", new Page.WaitForSelectorOptions().setTimeout(8_000));
            Thread.sleep(500); // 等 JS 渲染

            Map<String, Object> row = new HashMap<>();
            row.put("plate_type", plateType);
            row.put("plate_name", ref.name);
            row.put("trade_date", tradeDate);

            // 板块代码（指数代码）— 概念和地域/行业在不同 xpath
            String codeXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[1]/h3/span"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[1]/h3/span";
            String plateIndex = safeInnerText(page, codeXpath);
            row.put("plate_index", plateIndex);

            // 当前价格
            String priceXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[1]/span"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[1]/span";
            String priceText = safeInnerText(page, priceXpath);
            row.put("cur_price", parseDecimal(priceText));

            // 涨跌幅（%）
            String increaseXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[6]/dd"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[2]/dl[6]/dd";
            String increaseText = safeInnerText(page, increaseXpath);
            row.put("increase", parsePercent(increaseText));

            // 成交额（亿 → 元）
            String turnoverXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[10]/dd"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[2]/dl[10]/dd";
            String turnoverText = safeInnerText(page, turnoverXpath);
            row.put("turnover", parseYi(turnoverText));

            // 成交量（万手 → 手）
            String volumeXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[5]/dd"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[2]/dl[5]/dd";
            String volumeText = safeInnerText(page, volumeXpath);
            row.put("volume", parseWanShou(volumeText));

            // 上涨家数
            String upXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[8]/dd/span[1]"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[2]/dl[8]/dd/span[1]";
            String upText = safeInnerText(page, upXpath);
            row.put("up_count", parseInteger(upText));

            // 下跌家数
            String downXpath = (plateType == 6)
                    ? "html/body/div[2]/div[3]/div[2]/div/div[2]/div[1]/div[2]/dl[8]/dd/span[2]"
                    : "/html/body/div[2]/div[2]/div[2]/div/div/div[1]/div[2]/dl[8]/dd/span[2]";
            String downText = safeInnerText(page, downXpath);
            row.put("down_count", parseInteger(downText));

            // 领涨股（取第一个 tr 的股票名+代码）— 概念页在 #maincont 表格
            try {
                Locator firstStock = page.locator("#maincont table tbody tr:first-child td:eq(2) a").first();
                if (firstStock.count() > 0) {
                    row.put("lead_stock_name", firstStock.innerText());
                }
                Locator firstCode = page.locator("#maincont table tbody tr:first-child td:eq(1) a").first();
                if (firstCode.count() > 0) {
                    row.put("lead_stock_code", firstCode.innerText());
                }
            } catch (Exception e) {
                log.debug("[ThsPlateCrawler] 领涨股提取失败: {}", e.getMessage());
            }

            return row;

        } catch (Exception e) {
            log.warn("[ThsPlateCrawler] 详情页抓取失败: {}, error={}", ref.url, e.getMessage());
            return null;
        } finally {
            page.close();
        }
    }

    // ==================== 工具方法 ====================

    /** 关闭浏览器(供策略层在重试时调用) */
    public void closeBrowser() {
        browserPool.closeBrowser();
    }

    /** 安全取元素 innerText，不存在返回 null */
    private String safeInnerText(Page page, String xpath) {
        try {
            Locator loc = page.locator("xpath=" + xpath).first();
            if (loc.count() > 0) {
                String text = loc.innerText();
                return text != null && !text.isBlank() ? text.trim() : null;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 解析数字（去掉逗号、空格） */
    private java.math.BigDecimal parseDecimal(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String cleaned = text.replaceAll("[,\\s]", "");
            return new java.math.BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析百分比（"1.23%" → 1.23） */
    private java.math.BigDecimal parsePercent(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String cleaned = text.replace("%", "").replaceAll("[,\\s]", "");
            return new java.math.BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析"亿"单位（"123.45亿" → 12345000000） */
    private java.math.BigDecimal parseYi(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String cleaned = text.replace("亿", "").replaceAll("[,\\s]", "");
            java.math.BigDecimal yi = new java.math.BigDecimal(cleaned);
            return yi.multiply(new java.math.BigDecimal("100000000"));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析"万手"单位（"1234.56万手" → 12345600 手） */
    private Long parseWanShou(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String cleaned = text.replace("万手", "").replace("手", "").replaceAll("[,\\s]", "");
            java.math.BigDecimal wan = new java.math.BigDecimal(cleaned);
            return wan.multiply(new java.math.BigDecimal("10000")).longValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析整数 */
    private Integer parseInteger(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String cleaned = text.replaceAll("[,\\s]", "");
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 板块引用（名称 + 详情页 URL） */
    private record PlateRef(String name, String url) {
    }
}
