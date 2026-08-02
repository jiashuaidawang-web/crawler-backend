package com.dunwugudao.crawler.strategy.tonghuashun;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.options.Cookie;

import java.net.URI;
import java.util.List;

/** 带 Cookie vs 无 Cookie 对比（带代理重试，每个 IP 只试一次避免慢代理拖时间）。 */
public class CookieCompare {

    private static final String COOKIE_DIR =
            "/Users/null/Myself/stock/dunwugudao/爬虫项目/github/crawler-backend-new/cookies";
    private static final String POOL = "http://124.223.220.245:8088";
    private static final int PROXY_RETRY = 3;

    private static final String[] TARGETS = {
            "https://quote.10jqka.com.cn/center/",
            "https://stockpage.10jqka.com.cn/000001/",
            "https://q.10jqka.com.cn/gn/",
            "https://q.10jqka.com.cn/dy/"
    };

    public static void main(String[] args) throws Exception {
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {

            for (String url : TARGETS) {
                System.out.println("\n==== " + url + " ====");
                System.out.printf("  %-12s %-8s %-30s %s%n", "mode", "status", "title", "len");
                for (boolean withCookie : new boolean[]{false, true}) {
                    String res = tryWithRetry(browser, url, withCookie);
                    System.out.printf("  %-12s %s%n", withCookie ? "w/ cookie" : "no cookie", res);
                }
            }
        }
    }

    private static String tryWithRetry(Browser browser, String url, boolean withCookie) {
        for (int i = 1; i <= PROXY_RETRY; i++) {
            String proxyStr = acquireProxy();
            if (proxyStr == null) return "no proxy";
            String host = BrowserContextFactory.hostOf(url);
            URI u = URI.create(proxyStr);
            String[] up = u.getUserInfo() != null ? u.getUserInfo().split(":", 2) : null;
            Proxy pwProxy = up != null && up.length == 2
                    ? new Proxy(u.getHost() + ":" + u.getPort()).setUsername(up[0]).setPassword(up[1])
                    : new Proxy(u.getHost() + ":" + u.getPort());

            BrowserContext ctx = browser.newContext(new Browser.NewContextOptions().setProxy(pwProxy));
            try {
                if (withCookie) {
                    List<Cookie> cookies = new BrowserContextFactory().loadCookies(host, COOKIE_DIR);
                    if (!cookies.isEmpty()) ctx.addCookies(cookies);
                }
                Page page = ctx.newPage();
                Response r = page.navigate(url, new Page.NavigateOptions().setTimeout(25000));
                String title = safeTitle(page);
                return String.format("%-8d %-30s %d", r.status(), truncate(title, 28), safeLen(r));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage().replaceAll("\\s+", " ");
                if (i == PROXY_RETRY) return "FAIL after " + PROXY_RETRY + ": " + msg.substring(0, Math.min(60, msg.length()));
            } finally {
                ctx.close();
            }
        }
        return "FAIL";
    }

    private static String acquireProxy() {
        try {
            okhttp3.OkHttpClient c = new okhttp3.OkHttpClient.Builder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build();
            okhttp3.Response r = c.newCall(new okhttp3.Request.Builder().url(POOL + "/proxy/acquire").get().build()).execute();
            if (r.body() == null) return null;
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.body().string()).path("proxy").asText(null);
        } catch (Exception e) { return null; }
    }

    private static String safeTitle(Page p) { try { return p.title(); } catch (Exception e) { return ""; } }
    private static int safeLen(Response r) { try { return r.text().length(); } catch (Exception e) { return 0; } }
    private static String truncate(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s); }
}
