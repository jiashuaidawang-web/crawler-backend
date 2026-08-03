package com.dunwugudao.crawler.strategy.tonghuashun;

import com.dunwugudao.crawler.core.config.AntiCrawlConfig;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 浏览器对象池（同花顺策略）。
 *
 * <p>两种模式,由 {@link AntiCrawlConfig#getStealthMode()} 决定：
 * <ul>
 *   <li><b>SELF</b>（默认/现状）：懒初始化并复用单例无头 Chromium。</li>
 *   <li><b>CLOAK</b>：通过 CDP 连接本机/sidecar 的 {@code cloakbrowser cloakserve},
 *       首次连接前会经 {@link CloakServerProcess} 自动拉起本地进程（若未在运行）。</li>
 * </ul>
 *
 * <p>高并发场景可扩展为多实例/按节点分片,当前 M2 仅常驻单例；{@link #closeAll()} 在 JVM 关闭钩子调用。
 */
public class BrowserPool {

    private static final Logger log = LoggerFactory.getLogger(BrowserPool.class);

    private Playwright playwright;
    private Browser browser;
    private final Object lock = new Object();

    public BrowserPool() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeAll, "browser-pool-shutdown"));
    }

    /**
     * 获取常驻浏览器实例（线程安全,首次懒初始化）。
     *
     * @param cfg 反爬配置（决定 SELF/CLOAK 模式及 CLOAK 连接参数）
     */
    public Browser acquire(AntiCrawlConfig cfg) {
        synchronized (lock) {
            if (browser != null) {
                return browser;
            }
            if ("CLOAK".equalsIgnoreCase(cfg.getStealthMode())) {
                browser = acquireClover(cfg);
            } else {
                browser = acquireSelf(cfg);
            }
            return browser;
        }
    }

    /** SELF 模式：现状逻辑,自管 Playwright 无头 Chromium。 */
    private Browser acquireSelf(AntiCrawlConfig cfg) {
        log.info("[BrowserPool] SELF mode: launching Chromium (headless={})", isHeadless());
        playwright = Playwright.create();
        BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions().setHeadless(isHeadless());
        if (cfg.getBrowserArgs() != null && !cfg.getBrowserArgs().isEmpty()) {
            opts.setArgs(cfg.getBrowserArgs());
        }
        return playwright.chromium().launch(opts);
    }

    private static boolean isHeadless() {
        // macOS 上 Playwright 1.40 的 headless Chromium 会 SIGSEGV 崩溃,默认用 headed 模式。
        // 环境变量 SELF_HEADLESS=true 可强制 headless(Linux Docker 生产环境建议设这个)。
        String v = System.getenv("SELF_HEADLESS");
        if (v != null) {
            return "true".equalsIgnoreCase(v) || "1".equals(v);
        }
        // 启发式:macOS 默认 headed,其他系统默认 headless
        String os = System.getProperty("os.name", "").toLowerCase();
        return !os.contains("mac");
    }

    /** CLOAK 模式：CDP 连接 cloakserve,并在需要时本地自动拉起。 */
    private Browser acquireClover(AntiCrawlConfig cfg) {
        String cdpUrl = cfg.getCloakCdpUrl();
        log.info("[BrowserPool] CLOAK mode: connecting CDP server at {}", cdpUrl);
        // 确保本地 cloakserve 在跑（Docker sidecar 模式下端口通常已就绪,此步是 no-op）
        CloakServerProcess.ensureRunning(cfg);
        playwright = Playwright.create();
        return playwright.chromium().connectOverCDP(cdpUrl);
    }

    /** 释放：常驻不动（无操作）。 */
    public void release(Browser b) {
        // 常驻复用，不做关闭
    }

    /** 关闭全部资源（JVM 关闭钩子）。 */
    public void closeAll() {
        synchronized (lock) {
            if (browser != null) {
                try {
                    browser.close();
                } catch (Exception ignored) {
                }
                browser = null;
            }
            if (playwright != null) {
                try {
                    playwright.close();
                } catch (Exception ignored) {
                }
                playwright = null;
            }
        }
    }
}
