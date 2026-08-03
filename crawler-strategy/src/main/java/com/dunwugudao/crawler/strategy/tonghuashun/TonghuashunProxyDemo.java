package com.dunwugudao.crawler.strategy.tonghuashun;

import com.dunwugudao.crawler.core.config.AntiCrawlConfig;
import com.dunwugudao.crawler.core.model.SourceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.Proxy;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 同花顺 + proxy-pool（Webshare）代理链路验证 demo。
 * <p>不走 worker/DB，独立可跑。每个目标页：从 proxy-pool 取一个代理 →
 * 新建带 stealth + 代理的浏览器上下文 → 先访问 httpbin.org/ip 验证出口 →
 * 再打开同花顺页 → 报告状态码 / 标题 / 内容长度 / 是否被拦截。</p>
 *
 * <p>代理统一走本项目的 proxy-pool（http://124.223.220.245:8088），
 * 该池已接入 12 个 Webshare 账号（90+ 住宅 IP）+ 冷却循环 + 质量追踪，
 * 不需要直连 webshare.io。</p>
 */
public class TonghuashunProxyDemo {

    private static final String POOL = "http://124.223.220.245:8088";

    /** 单页代理重试次数：一个代理不通就换一个。 */
    private static final int MAX_PROXY_RETRY = 3;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 要验证的同花顺页面。 */
    private static final String[] TARGETS = {
            "https://quote.10jqka.com.cn/center/",
            "https://stockpage.10jqka.com.cn/000001/",
            "https://data.10jqka.com.cn/market/zdfph/",
            "https://q.10jqka.com.cn/gn/",
            "https://q.10jqka.com.cn/dy/",
            "https://q.10jqka.com.cn/dy/detail/code/882021/"
    };

    public static void main(String[] args) {
        BrowserPool pool = new BrowserPool();
        // Browser 在首次 attempt 拿到 proxy 后再 acquire（acquire 依赖 cfg 选择 SELF/CLOAK 模式）
        Browser browser = null;
        try {
            for (String url : TARGETS) {
                System.out.println("\n===================================================");
                System.out.println("[TARGET] " + url);

                // 每页最多试 MAX_PROXY_RETRY 个代理，单个代理不通就换一个。
                boolean done = false;
                for (int attempt = 1; attempt <= MAX_PROXY_RETRY && !done; attempt++) {
                    String proxy = acquireProxy();
                    if (proxy == null) {
                        System.out.println("[SKIP] proxy-pool 未返回代理（attempt " + attempt + "）");
                        break;
                    }
                    System.out.println("[PROXY] " + mask(proxy) + " (attempt " + attempt + "/" + MAX_PROXY_RETRY + ")");
                    AntiCrawlConfig cfg = new MinimalAntiCrawlConfig(proxy);
                    if (browser == null) {
                        browser = pool.acquire(cfg);
                    }

                    BrowserContext ctx = new BrowserContextFactory().newContext(browser, cfg, BrowserContextFactory.hostOf(url));
                    try (Page page = ctx.newPage()) {
                        // 1) 验证代理出口 IP（非致命，仅作观测）
                        String exitIp = checkExitIp(page);
                        System.out.println("[EXIT IP] " + (exitIp != null ? exitIp : "unknown"));

                        // 2) 打开同花顺页
                        com.microsoft.playwright.Response resp = page.navigate(url, new Page.NavigateOptions().setTimeout(45_000));
                        int status = resp.status();
                        String title = safeTitle(page);
                        String body = safeBody(resp);
                        boolean blocked = detectBlocked(status, title, body);
                        System.out.println("[STATUS] " + status);
                        System.out.println("[TITLE ] " + title);
                        System.out.println("[LEN   ] " + body.length() + " chars");
                        System.out.println("[BLOCK  ] " + (blocked ? "⚠ POSSIBLY BLOCKED" : "✅ ok"));
                        if (blocked || status >= 400) {
                            System.out.println("[PREVIEW] " + body.substring(0, Math.min(300, body.length())));
                        }
                        done = true;
                    } catch (Exception e) {
                        // 单页/单代理失败不崩溃，换一个代理重试。
                        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        System.out.println("[RETRY] 代理不通或页面超时（attempt " + attempt + "）: "
                                + msg.substring(0, Math.min(160, msg.length())));
                    } finally {
                        ctx.close();
                    }

                    sleep(1000);
                }
                if (!done) {
                    System.out.println("[GIVE UP] 已用尽 " + MAX_PROXY_RETRY + " 个代理，跳过该页");
                }
                sleep(1500);
            }
        } finally {
            pool.closeAll();
            System.out.println("\n=== DONE ===");
        }
    }

    /** 访问 httpbin.org/ip，返回出口 IP（验证代理是否生效）。 */
    private static String checkExitIp(Page page) {
        try {
            com.microsoft.playwright.Response r = page.navigate("https://httpbin.org/ip", new Page.NavigateOptions().setTimeout(15_000));
            if (r.status() != 200) {
                return null;
            }
            String json = r.text();
            JsonNode n = MAPPER.readTree(json);
            return n.path("origin").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 proxy-pool 获取一个代理（http://user:pass@ip:port）。 */
    private static String acquireProxy() {
        Request req = new Request.Builder().url(POOL + "/proxy/acquire").get().build();
        try (Response resp = HTTP.newCall(req).execute()) {
            if (resp.body() == null) {
                return null;
            }
            JsonNode node = MAPPER.readTree(resp.body().string());
            return node.path("proxy").asText(null);
        } catch (Exception e) {
            System.err.println("[acquire] " + e.getMessage());
            return null;
        }
    }

    /** 判断是否被反爬/拦截（状态码 + 标题/内容启发式）。 */
    private static boolean detectBlocked(int status, String title, String body) {
        if (status == 403 || status == 429 || status == 503) {
            return true;
        }
        String t = title == null ? "" : title;
        String b = body == null ? "" : body;
        if (t.contains("验证") || t.contains("验证码") || t.contains("Access Denied")) {
            return true;
        }
        if (b.contains("您的访问被拒绝") || b.contains("Please verify") || b.contains("chkVerify")) {
            return true;
        }
        return false;
    }

    private static String safeTitle(Page page) {
        try {
            return page.title();
        } catch (Exception e) {
            return "(none)";
        }
    }

    private static String safeBody(com.microsoft.playwright.Response resp) {
        try {
            return resp.text();
        } catch (Exception e) {
            return "";
        }
    }

    private static String mask(String proxy) {
        // http://user:pass@ip:port → http://***:***@ip:port
        try {
            java.net.URI uri = java.net.URI.create(proxy);
            String host = uri.getHost();
            int port = uri.getPort();
            return "http://***:***@" + host + ":" + port;
        } catch (Exception e) {
            return "***";
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 最小反爬配置（仅代理 + stealth，供 demo 用；worker 的配置从 application.yml 注入）。
     * 代理通过 Playwright 原生 Proxy 对象按 source 注入，与项目 worker 的 AntiCrawlConfig 行为一致。
     */
    private static class MinimalAntiCrawlConfig implements AntiCrawlConfig {

        private final String proxy;

        MinimalAntiCrawlConfig(String proxy) {
            this.proxy = proxy;
        }

        @Override
        public List<String> getUaPool() {
            return new ArrayList<>();
        }

        @Override
        public double getRateLimitPerSec() {
            return 10.0;
        }

        @Override
        public boolean isProxyEnabled() {
            return true;
        }

        @Override
        public String getProxyFor(SourceType source) {
            // 返回 demo 预先 acquire 的同一个代理，保证出口验证与页面访问一致。
            return proxy;
        }

        @Override
        public boolean isStealthEnabled() {
            return true;
        }

        @Override
        public String getCookieDir() {
            return null;
        }

        @Override
        public List<String> getBrowserArgs() {
            return new ArrayList<>();
        }

        @Override
        public String getProxyRotation() {
            return "RANDOM";
        }

        @Override
        public String getStealthMode() {
            return "SELF";
        }

        @Override
        public String getCloakCdpUrl() {
            return null;
        }

        @Override
        public String getCloakLicenseKey() {
            return "";
        }

        @Override
        public boolean isCloakHumanize() {
            return false;
        }

        @Override
        public String getCloakFingerprintSeed() {
            return "";
        }

        @Override
        public int getCloakLocalPort() {
            return 9222;
        }

        @Override
        public String getCloakServeScript() {
            return "scripts/cloak_serve.py";
        }
    }
}
