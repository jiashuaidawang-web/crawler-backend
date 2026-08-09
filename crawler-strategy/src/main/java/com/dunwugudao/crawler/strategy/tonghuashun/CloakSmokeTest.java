package com.dunwugudao.crawler.strategy.tonghuashun;

import com.dunwugudao.crawler.core.config.AntiCrawlConfig;
import com.dunwugudao.crawler.core.model.CrawlContext;
import com.dunwugudao.crawler.core.model.CrawlResult;
import com.dunwugudao.crawler.core.model.CrawlTask;
import com.dunwugudao.crawler.core.model.SourceType;
import com.dunwugudao.crawler.strategy.tonghuashun.ThsPlateCrawler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 快速验证 CLOAK vs SELF 模式的独立测试入口。
 *
 * <p>直接运行 main,不需要数据库、Redis、Spring。
 * 用于本机对比 CloakBrowser 与现状的抓取效果。</p>
 *
 * <p>用法:
 * <ul>
 *   <li>SELF 模式(默认): {@code java CloakSmokeTest}</li>
 *   <li>CLOAK 模式: {@code STEALTH_MODE=CLOAK java CloakSmokeTest}</li>
 *   <li>CLOAK + 代理: {@code STEALTH_MODE=CLOAK CLOAK_PROXY=http://u:p@host:port java CloakSmokeTest}</li>
 * </ul>
 * </p>
 */
public class CloakSmokeTest {

    private static final String TARGET = "https://stockpage.10jqka.com.cn/600519/";
    // 反爬检测站(可选)
    private static final String FPJS_TARGET = "https://bot.incolumitas.com/";

    public static void main(String[] args) throws Exception {
        System.out.println("=== CloakSmokeTest ===");
        System.out.println("[stealth-mode] " + System.getenv().getOrDefault("STEALTH_MODE", "SELF"));

        SimpleAntiCrawlConfig cfg = new SimpleAntiCrawlConfig();
        System.out.println("[cloak-cdp-url] " + cfg.getCloakCdpUrl());
        System.out.println("[cloak-license-key] " + (cfg.getCloakLicenseKey().isEmpty() ? "(empty=free)" : "***"));
        BrowserPool pool = new BrowserPool();
        ThsPlateCrawler thsPlateCrawler = new ThsPlateCrawler(pool);
        TonghuashunBrowserStrategy strategy = new TonghuashunBrowserStrategy(cfg, pool, thsPlateCrawler);

        try {
            runOnce(strategy, TARGET, "同花顺个股页(600519)");
            // 取消注释下一行可测 FPJS 检测站
            // runOnce(strategy, FPJS_TARGET, "bot.incolumitas.com 反爬检测");
        } finally {
            pool.closeAll();
            System.out.println("\n=== DONE ===");
        }
    }

    private static void runOnce(TonghuashunBrowserStrategy strategy, String url, String label) {
        System.out.println("\n----- " + label + " -----");
        System.out.println("[url] " + url);

        CrawlTask task = new CrawlTask();
        task.setUrl(url);
        task.setSource(SourceType.TONGHUASHUN);
        task.setParamsJson("{}");

        CrawlContext ctx = new CrawlContext();
        ctx.setTask(task);

        long t0 = System.currentTimeMillis();
        try {
            CrawlResult r = strategy.fetch(ctx);
            long ms = System.currentTimeMillis() - t0;
            System.out.println("[status] " + (r.isSuccess() ? "SUCCESS" : "FAIL"));
            System.out.println("[http] " + r.getHttpStatus());
            System.out.println("[rowCount] " + r.getRowCount());
            System.out.println("[time] " + ms + "ms");
            String raw = r.getRaw() != null ? r.getRaw() : "";
            System.out.println("[raw length] " + raw.length());
            System.out.println("[preview] " + raw.substring(0, Math.min(500, raw.length())).replaceAll("\\s+", " "));
        } catch (Exception e) {
            System.out.println("[ERROR] " + e.getMessage());
        }
    }

    /** 极简 AntiCrawlConfig 实现:从环境变量读 CLOAK 配置,其余走默认。 */
    private static class SimpleAntiCrawlConfig implements AntiCrawlConfig {

        private final String stealthMode;
        private final String cdpUrl;
        private final String licenseKey;
        private final boolean humanize;
        private final String fingerprintSeed;
        private final int localPort;
        private final String proxy;

        SimpleAntiCrawlConfig() {
            Map<String, String> env = System.getenv();
            this.stealthMode = env.getOrDefault("STEALTH_MODE", "SELF");
            // CDP URL:显式 CLOAK_CDP_URL > 默认指向本地 Docker cloakserve 容器
            String url = env.get("CLOAK_CDP_URL");
            if (url == null || url.isBlank()) {
                url = "http://127.0.0.1:9222";
            }
            this.cdpUrl = url;
            this.licenseKey = env.getOrDefault("CLOAK_LICENSE_KEY", "");
            this.humanize = Boolean.parseBoolean(env.getOrDefault("CLOAK_HUMANIZE", "true"));
            this.fingerprintSeed = env.getOrDefault("CLOAK_FINGERPRINT_SEED", "");
            this.localPort = Integer.parseInt(env.getOrDefault("CLOAK_LOCAL_PORT", "9222"));
            // 代理:优先 CLOAK_PROXY,否则空(不代理)
            String p = env.get("CLOAK_PROXY");
            this.proxy = (p == null || p.isBlank()) ? "" : p;
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
            return proxy != null && !proxy.isBlank();
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
        public String getStealthMode() {
            return stealthMode;
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
        public String getCloakCdpUrl() {
            return cdpUrl;
        }

        @Override
        public String getCloakLicenseKey() {
            return licenseKey;
        }

        @Override
        public boolean isCloakHumanize() {
            return humanize;
        }

        @Override
        public String getCloakFingerprintSeed() {
            return fingerprintSeed;
        }

        @Override
        public int getCloakLocalPort() {
            return localPort;
        }

        @Override
        public String getCloakServeScript() {
            return "scripts/cloak_serve.py";
        }
    }
}
