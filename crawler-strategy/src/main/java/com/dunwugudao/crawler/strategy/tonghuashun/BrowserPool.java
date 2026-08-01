package com.dunwugudao.crawler.strategy.tonghuashun;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;

/**
 * 浏览器对象池（同花顺策略）。
 * <p>懒初始化并复用单例 {@link Browser}（无头 Chromium）。高并发场景可扩展为多实例/按节点分片，
 * 当前 M2 仅常驻单例；{@link #closeAll()} 在 JVM 关闭钩子调用。</p>
 */
public class BrowserPool {

    private Playwright playwright;
    private Browser browser;
    private final Object lock = new Object();

    public BrowserPool() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeAll));
    }

    /** 获取常驻浏览器实例（线程安全，首次懒初始化）。 */
    public Browser acquire() {
        synchronized (lock) {
            if (browser == null) {
                playwright = Playwright.create();
                BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions().setHeadless(true);
                browser = playwright.chromium().launch(opts);
            }
            return browser;
        }
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
