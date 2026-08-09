package com.dunwugudao.crawler.strategy.tonghuashun;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.options.LoadState;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Scanner;

/**
 * 同花顺登录 + Cookie 导出。
 *
 * <p>流程：打开同花顺登录页 → 填账号密码 → 处理滑块/验证码（分两级）：
 * <ol>
 *   <li>全自动：stealth + 模拟人类拖滑块，能过就直接导出 Cookie</li>
 *   <li>半自动：脚本停在滑块/验证码前，等人工在同一个浏览器窗口里手动拖一下，
 *       检测到登录成功后自动导出 Cookie</li>
 * </ol>
 * 登录成功后把 Cookie 写到 cookieDir/quote.10jqka.com.cn.json（Playwright 导出格式），
 * 供 BrowserContextFactory.loadCookies 使用。</p>
 */
public class TonghuashunLogin {

    private static final String COOKIE_DIR = "cookies";
    private static final String PROXY = "http://124.223.220.245:8088";

    public static void main(String[] args) throws Exception {
        String username = args.length > 0 ? args[0] : "";
        String password = args.length > 1 ? args[1] : "";
        if (username.isEmpty() || password.isEmpty()) {
            System.out.println("用法: TonghuashunLogin <username> <password>");
            return;
        }

        // 用 CLOAK 模式(连接本机 cloakserve)还是 SELF 模式(自管 Playwright)
        String stealthMode = System.getenv().getOrDefault("STEALTH_MODE", "CLOAK");
        System.out.println("[模式] " + stealthMode);

        // 用 proxy-pool 拿一个代理
        String proxyStr = acquireProxy();
        System.out.println("[代理] " + (proxyStr != null ? mask(proxyStr) : "无"));

        try (Playwright pw = Playwright.create()) {
            Browser browser;
            if ("CLOAK".equalsIgnoreCase(stealthMode)) {
                // CLOAK: 连接本机 cloakserve(CDP 9222)
                System.out.println("[CLOAK] 连接 cloakserve CDP...");
                browser = pw.chromium().connectOverCDP("http://127.0.0.1:9222");
            } else {
                // SELF: 自管 Playwright
                BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions().setHeadless(false);
                browser = pw.chromium().launch(launch);
            }
            try (browser) {
                Browser.NewContextOptions opts = new Browser.NewContextOptions();
                if ("CLOAK".equalsIgnoreCase(stealthMode)) {
                    // CLOAK: 指纹/代理/时区全由 cloakserve 处理,这里只建上下文
                    opts.setJavaScriptEnabled(true);
                } else {
                    // SELF: 手动注入 stealth + 代理
                    opts.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .setViewportSize(1366, 900)
                            .setLocale("zh-CN")
                            .setTimezoneId("Asia/Shanghai")
                            .setJavaScriptEnabled(true);
                    if (proxyStr != null) {
                        URI u = URI.create(proxyStr);
                        String[] up = u.getUserInfo().split(":", 2);
                        opts.setProxy(new Proxy(u.getHost() + ":" + u.getPort())
                                .setUsername(up[0]).setPassword(up[1]));
                    }
                }
                BrowserContext ctx = browser.newContext(opts);
                if (!"CLOAK".equalsIgnoreCase(stealthMode)) {
                    ctx.addInitScript(BrowserContextFactory.STEALTH_JS);
                }

                Page page = ctx.newPage();
                System.out.println("[打开] 同花顺登录页...");
                page.navigate("https://upass.10jqka.com.cn/login", new Page.NavigateOptions().setTimeout(30000));
                page.waitForLoadState(LoadState.NETWORKIDLE);
                sleep(1500);

                // 截图看当前页面状态
                shot(page, "step1-login-page");
                System.out.println("[状态] 已截图 step1-login-page.png，看看是不是滑块页");

                // 尝试填账号密码
                tryFillCredentials(page, username, password);
                sleep(800);

                // 尝试全自动过滑块
                boolean autoSolved = tryAutoSolveSlider(page);
                shot(page, "step2-after-attempt");

                if (isLoggedIn(page)) {
                    System.out.println("[成功] 全自动登录成功！");
                } else {
                    // 半自动：提示人工介入（窗口还开着）
                    System.out.println("\n========================================");
                    System.out.println("[半自动] 脚本无法自动过滑块/验证码。");
                    System.out.println("  → Playwright 浏览器窗口还开着，请手动：");
                    System.out.println("     1. 拖滑块 / 点验证码");
                    System.out.println("     2. 完成登录");
                    System.out.println("  → 脚本每 3s 检测登录状态，成功后自动导出 Cookie。");
                    System.out.println("  → 登录后按回车继续（或等脚本自动检测）...");
                    System.out.println("========================================");

                    // 每 3s 检测是否已登录，最多等 120s
                    Scanner scanner = new Scanner(System.in);
                    long deadline = System.currentTimeMillis() + 120_000;
                    boolean humanDone = false;
                    while (System.currentTimeMillis() < deadline) {
                        if (isLoggedIn(page)) {
                            humanDone = true;
                            break;
                        }
                        // 也检测回车
                        try {
                            if (System.in.available() > 0) {
                                scanner.nextLine();
                                break;
                            }
                        } catch (Exception ignored) {}
                        sleep(3000);
                    }
                    if (humanDone) {
                        System.out.println("[检测] 登录成功，自动导出 Cookie");
                    } else {
                        System.out.println("[提示] 窗口还在，请完成登录后按回车");
                        scanner.nextLine();
                    }
                }

                // 导出 Cookie —— 用 Playwright 原生 storageState()（JSON），
                // 不要手动拼：com.microsoft.playwright.options.Cookie 是空接口，实现类包私有，方法会丢。
                exportStorage(ctx, "quote.10jqka.com.cn");
                exportStorage(ctx, "stockpage.10jqka.com.cn");
                System.out.println("[完成] 登录态已导出到 " + COOKIE_DIR);
            }
        }
    }

    private static void tryFillCredentials(Page page, String username, String password) {
        try {
            // 常见选择器：同花顺通行证页
            Locator user = page.locator("input[name='username'], input#username, input[placeholder*='手机'], input[placeholder*='用户名'], input[type='text']").first();
            Locator pass = page.locator("input[name='password'], input#password, input[type='password']").first();
            if (user.count() > 0 && pass.count() > 0) {
                user.click();
                user.type(username, new Locator.TypeOptions().setDelay(80));
                sleep(400);
                pass.click();
                pass.type(password, new Locator.TypeOptions().setDelay(80));
                sleep(400);
                System.out.println("[填写] 账号密码已填");
                // 尝试点登录按钮
                Locator loginBtn = page.locator("button:has-text('登录'), input[type='submit'], a:has-text('登录'), .login-btn, #loginBtn").first();
                if (loginBtn.count() > 0) {
                    loginBtn.click();
                    page.waitForLoadState(LoadState.NETWORKIDLE);
                    sleep(1500);
                    System.out.println("[点击] 已点登录");
                }
            } else {
                System.out.println("[填写] 没找到账号/密码输入框，可能需要先点‘登录’弹出表单");
            }
        } catch (Exception e) {
            System.out.println("[填写] 填表异常: " + e.getMessage());
        }
    }

    /** 自动过滑块：找滑块轨道，模拟人类拖动。返回是否疑似通过。 */
    private static boolean tryAutoSolveSlider(Page page) {
        try {
            // 常见滑块选择器
            Locator slider = page.locator(
                    ".slide-verify-slider, .verify-slider, [class*='slider'], [class*='drag'], .nc_iconfont, .geetest_slider_button"
            ).first();
            if (slider.count() == 0 || !slider.isVisible()) {
                System.out.println("[滑块] 没看到滑块");
                return false;
            }
            // 找滑块按钮（需要拖动的那个）
            Locator knob = page.locator(
                    ".slide-verify-slider-knob, .verify-slider-knob, [class*='knob'], [class*='button'], .geetest_slider_button, .nc_iconfont.btn_slide"
            ).first();
            if (knob.count() == 0) {
                knob = slider;
            }

            // 轨道宽度
            double trackW = slider.boundingBox() != null ? slider.boundingBox().width : 260;
            if (trackW <= 0) trackW = 260;

            com.microsoft.playwright.options.BoundingBox knobBox = knob.boundingBox();
            if (knobBox == null) return false;

            System.out.println("[滑块] 开始自动拖动，轨道宽=" + trackW);

            // 人类式拖动：先加速后减速，加一点上下抖动
            double startX = knobBox.x + knobBox.width / 2;
            double startY = knobBox.y + knobBox.height / 2;
            page.mouse().move(startX, startY);
            page.mouse().down();

            double moved = 0;
            double target = trackW - knobBox.width / 2;
            while (moved < target) {
                double remain = target - moved;
                // 速度曲线：前 70% 快，后 30% 慢
                double speed;
                if (moved < target * 0.7) {
                    speed = 8 + Math.random() * 6;
                } else {
                    speed = 2 + Math.random() * 3;
                }
                if (speed > remain) speed = speed / 2;
                double jitter = (Math.random() - 0.5) * 2.5;
                page.mouse().move(startX + moved + speed, startY + jitter);
                moved += speed;
                sleep((long) (10 + Math.random() * 20));
            }
            // 精确到位
            page.mouse().move(startX + target, startY);
            sleep(100);
            page.mouse().up();
            sleep(1500);

            System.out.println("[滑块] 拖动完成");
            shot(page, "step-slider-done");
            return true;
        } catch (Exception e) {
            System.out.println("[滑块] 自动拖动异常: " + e.getMessage());
            return false;
        }
    }

    /** 判断是否已登录：页面出现用户信息 / 跳转离开登录页。 */
    private static boolean isLoggedIn(Page page) {
        try {
            String url = page.url();
            if (!url.contains("login") && !url.contains("upass")) {
                return true;
            }
            // 出现"退出"/"我的"等
            String html = page.content();
            if (html.contains("我的同花顺") || html.contains("退出登录") || html.contains("user-center")) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 用 Playwright 原生 storageState() 导出登录态（JSON 文件），可回灌到新上下文。 */
    private static void exportStorage(BrowserContext ctx, String host) {
        try {
            String json = ctx.storageState();
            Path dir = Paths.get(COOKIE_DIR);
            Files.createDirectories(dir);
            Path file = dir.resolve(host + ".json");
            Files.writeString(file, json);
            System.out.println("[导出] " + file + " (" + json.length() + " chars)");
        } catch (Exception e) {
            System.out.println("[导出] " + host + " 失败: " + e.getMessage());
        }
    }

    private static void shot(Page page, String name) {
        try {
            page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get(name + ".png")));
        } catch (Exception ignored) {}
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String acquireProxy() {
        try {
            okhttp3.OkHttpClient c = new okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build();
            okhttp3.Response r = c.newCall(new okhttp3.Request.Builder().url(PROXY + "/proxy/acquire").get().build()).execute();
            if (r.body() == null) return null;
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.body().string()).path("proxy").asText(null);
        } catch (Exception e) { return null; }
    }

    private static String mask(String proxy) {
        try {
            URI u = URI.create(proxy);
            return "http://***:***@" + u.getHost() + ":" + u.getPort();
        } catch (Exception e) { return "***"; }
    }
}
