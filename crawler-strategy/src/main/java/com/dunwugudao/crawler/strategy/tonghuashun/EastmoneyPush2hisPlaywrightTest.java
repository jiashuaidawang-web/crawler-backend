package com.dunwugudao.crawler.strategy.tonghuashun;

import com.dunwugudao.crawler.core.config.AntiCrawlConfig;
import com.dunwugudao.crawler.core.model.SourceType;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.List;

/**
 * 用 Playwright（stealth + 代理）测试东财 push2his 端点。
 * <p>复用同花顺的 BrowserContextFactory + 指纹，验证 push2his 是否能通。</p>
 */
public class EastmoneyPush2hisPlaywrightTest {

    // 当前快代理 IP（从 API 获取）
    private static final String PROXY = "http://d2383368918:tft4pvct@58.19.54.5:14249";

    // push2his 测试 URL（上证综指日线，2026-08-01 周五）
    private static final String TARGET =
            "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=1.000001&klt=101&fqt=0"
                    + "&fields1=f1,f2,f3,f4,f5,f6"
                    + "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61"
                    + "&end=2026-08-01&lmt=5";

    public static void main(String[] args) {
        BrowserPool pool = new BrowserPool();
        AntiCrawlConfig cfg = new DemoAntiCrawlConfig(PROXY);
        Browser browser = pool.acquire(cfg);
        try {
            System.out.println("[TARGET] " + TARGET);
            System.out.println("[PROXY] " + PROXY);

            BrowserContext ctx = new BrowserContextFactory().newContext(browser, cfg, "push2his.eastmoney.com");

            try (Page page = ctx.newPage()) {
                // 直接访问 push2his（跳过 httpbin，代理已验证可用）
                System.out.println("\n=== 访问 push2his ===");
                com.microsoft.playwright.Response resp = page.navigate(TARGET,
                        new Page.NavigateOptions().setTimeout(30_000));
                System.out.println("[status] " + resp.status());
                String body = resp.text();
                System.out.println("[body length] " + body.length());
                System.out.println("[body] " + body.substring(0, Math.min(800, body.length())));
            } catch (Exception e) {
                System.out.println("[ERROR] " + e.getMessage());
                e.printStackTrace();
            } finally {
                ctx.close();
            }
        } finally {
            pool.closeAll();
            System.out.println("\n=== DONE ===");
        }
    }

    private static class DemoAntiCrawlConfig implements AntiCrawlConfig {
        private final String proxy;

        DemoAntiCrawlConfig(String proxy) {
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
