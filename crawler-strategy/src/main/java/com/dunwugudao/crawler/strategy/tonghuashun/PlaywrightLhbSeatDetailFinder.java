package com.dunwugudao.crawler.strategy.tonghuashun;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Proxy;
import com.dunwugudao.crawler.strategy.eastmoney.QgLongTermProxyProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Playwright 抓取东财龙虎榜个股明细页的网络请求，找出席位明细（dt_detail）的 reportName。
 */
public class PlaywrightLhbSeatDetailFinder {

    // 青果代理配置（与 SeedStrategyBeans 同）
    private static final String QG_KEY = "985DC0DF";
    private static final String QG_PASS = "937CBBDAEF9C";

    public static void main(String[] args) throws Exception {
        QgLongTermProxyProvider provider = new QgLongTermProxyProvider(QG_KEY, QG_PASS);
        String proxyStr = provider.acquire();
        System.out.println("[proxy] " + proxyStr);
        if (proxyStr == null) {
            System.out.println("获取代理失败，退出");
            return;
        }
        String[] p = parseProxy(proxyStr);
        String host = p[0];
        int port = Integer.parseInt(p[1]);
        String user = p[2];
        String pass = p[3];

        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions().setHeadless(true);
            try (Browser browser = playwright.chromium().launch(launch)) {
                Browser.NewContextOptions ctxOpts = new Browser.NewContextOptions()
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
                        // 不带代理直连（避免 Chromium SIGSEGV）

                try (BrowserContext context = browser.newContext(ctxOpts)) {
                    Page page = context.newPage();

                    List<String> dcUrls = new ArrayList<>();
                    page.onRequest(req -> {
                        String url = req.url();
                        if (url.contains("datacenter-web.eastmoney.com")) {
                            dcUrls.add(url);
                        }
                    });

                    // 龙虎榜个股明细页（盛达资源 000603，昨日上榜）
                    String pageUrl = "https://data.eastmoney.com/lhb/StockHdStatistics/000603.html";
                    System.out.println("[navigate] " + pageUrl);
                    page.navigate(pageUrl);

                    Thread.sleep(6000); // 等页面加载 + 异步 XHR

                    System.out.println("\n=== 抓到 " + dcUrls.size() + " 个 datacenter-web 请求 ===");
                    for (String u : dcUrls) {
                        System.out.println(u);
                    }

                    System.out.println("\n=== 解析 reportName / filter / columns ===");
                    for (String u : dcUrls) {
                        try {
                            String query = java.net.URI.create(u).getQuery();
                            if (query == null) continue;
                            boolean printedUrl = false;
                            for (String kv : query.split("&")) {
                                if (kv.startsWith("reportName=") || kv.startsWith("filter=") || kv.startsWith("columns=")) {
                                    String decoded = java.net.URLDecoder.decode(kv, "UTF-8");
                                    if (!printedUrl) {
                                        System.out.println("URL: " + u.substring(0, Math.min(120, u.length())) + "...");
                                        printedUrl = true;
                                    }
                                    System.out.println("  " + decoded);
                                }
                            }
                        } catch (Exception ignore) {}
                    }
                }
            }
        }
    }

    private static String[] parseProxy(String proxy) {
        String[] r = new String[4];
        try {
            java.net.URI uri = java.net.URI.create(proxy);
            r[0] = uri.getHost();
            r[1] = String.valueOf(uri.getPort());
            String info = uri.getUserInfo();
            if (info != null && info.contains(":")) {
                String[] ps = info.split(":", 2);
                r[2] = ps[0];
                r[3] = ps[1];
            }
        } catch (Exception e) {
            System.out.println("解析代理失败: " + e.getMessage());
        }
        return r;
    }
}
