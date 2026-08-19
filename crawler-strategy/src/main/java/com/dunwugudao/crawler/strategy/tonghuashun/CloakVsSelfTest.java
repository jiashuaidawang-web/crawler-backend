package com.dunwugudao.crawler.strategy.tonghuashun;

import com.dunwugudao.crawler.core.config.AntiCrawlConfig;
import com.dunwugudao.crawler.core.model.CrawlContext;
import com.dunwugudao.crawler.core.model.CrawlResult;
import com.dunwugudao.crawler.core.model.CrawlTask;
import com.dunwugudao.crawler.core.model.SourceType;
import com.dunwugudao.crawler.strategy.eastmoney.EastmoneyPlaywrightStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CLOAK vs SELF 对比测试:用真实业务 URL(同花顺/东方财富的高反爬子域名)跑一遍,对比抓取效果。
 *
 * <p>用法:
 * <ul>
 *   <li>CLOAK 模式: {@code STEALTH_MODE=CLOAK java CloakVsSelfTest}</li>
 *   <li>SELF 模式: {@code STEALTH_MODE=SELF java CloakVsSelfTest}</li>
 * </ul>
 * </p>
 *
 * <p>对比指标:HTTP 状态、内容长度、反爬迹象(封禁/验证码/空内容)、耗时。</p>
 */
public class CloakVsSelfTest {

    static final class UrlCase {
        final String url;
        final String label;
        final SourceType source;
        final String paramsJson;

        UrlCase(String url, String label, SourceType source, String paramsJson) {
            this.url = url;
            this.label = label;
            this.source = source;
            this.paramsJson = paramsJson;
        }
    }

    // 测试用例:用户项目真实抓取的高反爬端点
    private static final List<UrlCase> CASES = new ArrayList<>();

    static {
        // ---- 东方财富(不同子域名反爬力度不同) ----
        // 1. 所有股票(clist)
        CASES.add(new UrlCase(
                "http://83.push2.eastmoney.com/api/qt/clist/get?cb=jQuery112401832385794779421_1634565291536&pn=1&pz=20&po=1&np=1&ut=bd1d9ddb04089700cf9c27f6f7426281&fltt=2&invt=2&fid=f3&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23&fields=f1,f2,f3,f12,f13,f14&_=1634565291549",
                "东财-push2-所有股票(clist)", SourceType.EASTMONEY, "{}"));
        // 2. 主力净流入
        CASES.add(new UrlCase(
                "https://push2.eastmoney.com/api/qt/clist/get?cb=jQuery112307230384536031885_1785770663892&fid=f184&po=1&pz=10&pn=1&np=1&fltt=2&invt=2&fields=f2,f3,f12,f13,f14,f62,f184&ut=8dec03ba335b81bf4ebdf7b29ec27d15&fs=m:0+t:6+f:!2,m:0+t:13+f:!2,m:0+t:80+f:!2,m:1+t:2+f:!2,m:1+t:23+f:!2",
                "东财-push2-主力净流入", SourceType.EASTMONEY, "{}"));
        // 3. 涨跌停(TopicDTPool)
        CASES.add(new UrlCase(
                "https://push2ex.eastmoney.com/getTopicDTPool?cb=callbackdata5851472&ut=7eea3edcaed734bea9cbfc24409ed989&dpt=wz.ztzt&Pageindex=0&pagesize=20&sort=fund:asc&date=20260803",
                "东财-push2ex-涨跌停(DTPool)", SourceType.EASTMONEY, "{}"));
        // 4. 股票主力流入情况(daykline)
        CASES.add(new UrlCase(
                "https://push2his.eastmoney.com/api/qt/stock/fflow/daykline/get?cb=jQuery1123016124839051358653_1785770781323&lmt=0&klt=101&fields1=f1,f2,f3,f7&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61,f62,f63,f64,f65&ut=b2884a393a59ad64002292a3e90d46a5&secid=0.300615",
                "东财-push2his-主力流入(daykline)", SourceType.EASTMONEY, "{}"));
        // 5. 概念相关
        CASES.add(new UrlCase(
                "http://81.push2.eastmoney.com/api/qt/clist/get?cb=jQuery112407985290521908095_1635431040166&pn=1&pz=20&po=1&np=1&ut=bd1d9ddb04089700cf9c27f6f7426281&fltt=2&invt=2&fid=f3&fs=m:90+t:1+f:!50&fields=f1,f2,f3,f12,f13,f14&_=1635431040206",
                "东财-push81-概念(clist)", SourceType.EASTMONEY, "{}"));
        // 6. 涨停(TopicZTPool)
        CASES.add(new UrlCase(
                "https://push2ex.eastmoney.com/getTopicZTPool?cb=callbackdata6233583&ut=7eea3edcaed734bea9cbfc24409ed989&dpt=wz.ztzt&date=20260803&Pageindex=0&pagesize=100&sort=fbt:asc",
                "东财-push2ex-涨停(ZTPool)", SourceType.EASTMONEY, "{}"));

        // ---- 同花顺(必须浏览器+Cookie) ----
        // 7. 上证A股
        CASES.add(new UrlCase(
                "https://q.10jqka.com.cn/index/index/board/ss/field/zdf/order/desc/page/1/ajax/1/",
                "同花顺-上证A股(q.10jqka)", SourceType.TONGHUASHUN, "{}"));
        // 8. 板块行情
        CASES.add(new UrlCase(
                "https://q.10jqka.com.cn/thshy/index/field/199112/order/asc/page/1/ajax/1/",
                "同花顺-板块行情(thshy)", SourceType.TONGHUASHUN, "{}"));
    }

    public static void main(String[] args) throws Exception {
        String mode = System.getenv().getOrDefault("STEALTH_MODE", "SELF").toUpperCase();
        System.out.println("=== CloakVsSelfTest ===");
        System.out.println("[mode] " + mode);

        // 注意:SELF 模式在 Apple Silicon Mac 上会 SIGSEGV 崩溃(Playwright 1.40 已知 bug)
        // 本机测试请用 CLOAK + Docker cloakserve 容器
        if ("SELF".equals(mode)) {
            System.out.println("[WARN] SELF mode on Apple Silicon Mac will crash (Playwright 1.40 bug).");
            System.out.println("[WARN] Use STEALTH_MODE=CLOAK + 'bash scripts/cloak_docker_up.sh' instead.");
        }

        SimpleAntiCrawlConfig cfg = new SimpleAntiCrawlConfig();
        System.out.println("[cloak-cdp-url] " + cfg.getCloakCdpUrl());

        BrowserPool pool = new BrowserPool();
        TonghuashunBrowserStrategy tonghuashun = new TonghuashunBrowserStrategy(cfg, pool);
        EastmoneyPlaywrightStrategy eastmoney = new EastmoneyPlaywrightStrategy(cfg, pool);

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"label", "status", "http", "length", "time_ms", "blocked", "preview"});

        try {
            for (UrlCase c : CASES) {
                com.dunwugudao.crawler.core.strategy.SourceStrategy strategy = (c.source == SourceType.TONGHUASHUN)
                        ? tonghuashun : eastmoney;
                runOne(rows, strategy, c);
                Thread.sleep(2000);
            }
        } finally {
            pool.closeAll();
        }

        System.out.println("\n======================= RESULT =======================");
        printTable(rows);
        System.out.println("=== DONE ===");
    }

    private static SourceType detectSource(UrlCase c) {
        return c.source;
    }

    private static void runOne(List<String[]> rows, com.dunwugudao.crawler.core.strategy.SourceStrategy strategy, UrlCase c) {
        System.out.println("\n----- " + c.label + " -----");
        System.out.println("[url] " + c.url);

        CrawlTask task = new CrawlTask();
        task.setUrl(c.url);
        task.setSource(c.source);
        task.setParamsJson(c.paramsJson);

        CrawlContext ctx = new CrawlContext();
        ctx.setTask(task);

        String status = "FAIL";
        int http = 0;
        long length = 0;
        long ms = 0;
        boolean blocked = false;
        String preview = "";

        long t0 = System.currentTimeMillis();
        try {
            CrawlResult r = strategy.fetch(ctx);
            ms = System.currentTimeMillis() - t0;
            if (r.isSuccess()) {
                status = "SUCCESS";
                http = r.getHttpStatus();
                String raw = r.getRaw() != null ? r.getRaw() : "";
                length = raw.length();
                blocked = detectBlocked(http, raw);
                preview = raw.substring(0, Math.min(120, raw.length())).replaceAll("\\s+", " ");
            }
        } catch (Exception e) {
            ms = System.currentTimeMillis() - t0;
            preview = e.getMessage();
            if (preview != null && preview.length() > 120) {
                preview = preview.substring(0, 120);
            }
        }

        System.out.println("[status] " + status + " [http] " + http + " [length] " + length
                + " [time] " + (ms / 1000.0) + "s [blocked] " + blocked);
        rows.add(new String[]{c.label, status, String.valueOf(http), String.valueOf(length),
                String.valueOf(ms), String.valueOf(blocked), preview});
    }

    /** 简单反爬检测:HTTP 码 + 内容特征。 */
    private static boolean detectBlocked(int http, String raw) {
        if (http == 403 || http == 429 || http == 503) {
            return true;
        }
        if (raw == null || raw.length() < 100) {
            return true;
        }
        String lower = raw.toLowerCase();
        return lower.contains("captcha")
                || lower.contains("verify")
                || lower.contains("访问受限")
                || lower.contains("请输入验证码")
                || lower.contains("您的请求过于频繁")
                || lower.contains("bot detection");
    }

    private static void printTable(List<String[]> rows) {
        // 简易对齐输出
        for (String[] row : rows) {
            System.out.printf("%-30s | %-7s | %-5s | %-10s | %-8s | %-7s | %s%n",
                    row[0], row[1], row[2], row[3], row[4] + "ms", row[5], row[6]);
        }
    }

    /** 极简 AntiCrawlConfig:从环境变量读配置。 */
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
            String url = env.get("CLOAK_CDP_URL");
            if (url == null || url.isBlank()) {
                url = "http://127.0.0.1:9222";
            }
            this.cdpUrl = url;
            this.licenseKey = env.getOrDefault("CLOAK_LICENSE_KEY", "");
            this.humanize = Boolean.parseBoolean(env.getOrDefault("CLOAK_HUMANIZE", "true"));
            this.fingerprintSeed = env.getOrDefault("CLOAK_FINGERPRINT_SEED", "");
            this.localPort = Integer.parseInt(env.getOrDefault("CLOAK_LOCAL_PORT", "9222"));
            String p = env.get("CLOAK_PROXY");
            this.proxy = (p == null || p.isBlank()) ? "" : p;
        }

        @Override public List<String> getUaPool() { return new ArrayList<>(); }
        @Override public double getRateLimitPerSec() { return 10.0; }
        @Override public boolean isProxyEnabled() { return proxy != null && !proxy.isBlank(); }
        @Override public String getProxyFor(SourceType source) { return proxy; }
        @Override public boolean isStealthEnabled() { return true; }
        @Override public String getStealthMode() { return stealthMode; }
        @Override public String getCookieDir() { return null; }
        @Override public List<String> getBrowserArgs() { return new ArrayList<>(); }
        @Override public String getProxyRotation() { return "RANDOM"; }
        @Override public String getCloakCdpUrl() { return cdpUrl; }
        public String getCloakCrawlUrl() { return cdpUrl; }
        @Override public String getCloakLicenseKey() { return licenseKey; }
        @Override public boolean isCloakHumanize() { return humanize; }
        @Override public String getCloakFingerprintSeed() { return fingerprintSeed; }
        @Override public int getCloakLocalPort() { return localPort; }
        @Override public String getCloakServeScript() { return "scripts/cloak_serve.py"; }
//        @Override public String getStealthMode() { return stealthMode; }
    }
}
