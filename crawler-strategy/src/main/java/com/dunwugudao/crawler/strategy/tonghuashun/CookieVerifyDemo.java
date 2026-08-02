package com.dunwugudao.crawler.strategy.tonghuashun;

import com.dunwugudao.crawler.core.config.AntiCrawlConfig;
import com.dunwugudao.crawler.core.model.SourceType;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.options.Cookie;

import java.net.URI;
import java.util.List;

/** 验证 Cookie 登录态是否生效：带 Cookie + 代理打开同花顺，对比有/无 Cookie。 */
public class CookieVerifyDemo {

    private static final String COOKIE_DIR =
            "/Users/null/Myself/stock/dunwugudao/爬虫项目/github/crawler-backend-new/cookies";
    private static final String POOL = "http://124.223.220.245:8088";

    private static final String[] TARGETS = {
            "https://quote.10jqka.com.cn/center/",
            "https://stockpage.10jqka.com.cn/000001/",
            "https://q.10jqka.com.cn/gn/"
    };

    public static void main(String[] args) throws Exception {
        String proxyStr = acquireProxy();
        System.out.println("[代理] " + proxyStr);

        // 有 Cookie
        System.out.println("\n############ 有 Cookie 登录态 ############");
        runBatch(true, proxyStr);
        // 无 Cookie 对照
        System.out.println("\n############ 无 Cookie（对照）############");
        runBatch(false, proxyStr);
    }

    private static void runBatch(boolean withCookie, String proxyStr) throws Exception {
        Proxy pwProxy = proxyStr != null ? buildProxy(proxyStr) : null;
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            for (String url : TARGETS) {
                String host = BrowserContextFactory.hostOf(url);
                Browser.NewContextOptions opts = new Browser.NewContextOptions();
                if (pwProxy != null) opts.setProxy(pwProxy);
                BrowserContext ctx = browser.newContext(opts);
                if (withCookie) {
                    List<Cookie> cookies = new BrowserContextFactory().loadCookies(host, COOKIE_DIR);
                    if (!cookies.isEmpty()) {
                        ctx.addCookies(cookies);
                        System.out.println("[载入] " + host + " 的 Cookie " + cookies.size() + " 条");
                    }
                }
                try (Page page = ctx.newPage()) {
                    Response r = page.navigate(url, new Page.NavigateOptions().setTimeout(40000));
                    String body = safeBody(r);
                    System.out.printf("  %-45s status=%d  title=%s  len=%d%n",
                            url, r.status(), truncate(page.title(), 30), body.length());
                } catch (Exception e) {
                    System.out.printf("  %-45s FAILED %s%n", url, firstLine(e));
                } finally {
                    ctx.close();
                }
                sleep(1000);
            }
        }
    }

    private static Proxy buildProxy(String proxyStr) {
        URI u = URI.create(proxyStr);
        String[] up = u.getUserInfo().split(":", 2);
        return new Proxy(u.getHost() + ":" + u.getPort()).setUsername(up[0]).setPassword(up[1]);
    }

    private static String acquireProxy() throws Exception {
        okhttp3.OkHttpClient c = new okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build();
        okhttp3.Response r = c.newCall(new okhttp3.Request.Builder().url(POOL + "/proxy/acquire").get().build()).execute();
        if (r.body() == null) return null;
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.body().string()).path("proxy").asText(null);
    }

    private static String safeBody(Response r) { try { return r.text(); } catch (Exception e) { return ""; } }

    private static String firstLine(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.substring(0, Math.min(80, m.length()));
    }

    private static String truncate(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s); }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
