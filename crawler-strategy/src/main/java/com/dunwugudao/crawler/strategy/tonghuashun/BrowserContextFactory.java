package com.dunwugudao.crawler.strategy.tonghuashun;

import com.dunwugudao.crawler.core.config.AntiCrawlConfig;
import com.dunwugudao.crawler.core.model.SourceType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.options.SameSiteAttribute;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 浏览器上下文工厂（同花顺反爬）。
 * <p>基于 {@link AntiCrawlConfig} 注入：随机 UA / viewport / locale / timezone、stealth init script、
 * 代理（perSource）、以及 Cookie 登录态（从 {@code cookieDir/<host>.json} 载入/保存）。</p>
 */
public class BrowserContextFactory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 基础 stealth 脚本：覆盖 webdriver 标志、伪造 plugins/languages/chrome，能过多数基础检测（非指纹级）。 */
    public static final String STEALTH_JS = "() => {\n"
            + "  Object.defineProperty(navigator, 'webdriver', { get: () => undefined });\n"
            + "  Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3] });\n"
            + "  Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN', 'zh'] });\n"
            + "  Object.defineProperty(navigator, 'platform', { get: () => 'Win32' });\n"
            + "  try { window.chrome = { runtime: {}, loadTimes: function(){}, csi: function(){} }; } catch(e) {}\n"
            + "}";

    /**
     * 新建带反爬配置的浏览器上下文。
     *
     * @param browser 已启动的（常驻）浏览器实例
     * @param cfg     反爬配置
     * @param host    目标 host（用于定位 Cookie 文件，如 quote.10jqka.com.cn）
     */
    public BrowserContext newContext(Browser browser, AntiCrawlConfig cfg, String host) {
        StealthSpec spec = new StealthSpec();
        spec.setEnabled(cfg.isStealthEnabled());
        StealthSpec.Fingerprint fp = spec.randomize();

        Browser.NewContextOptions opts = new Browser.NewContextOptions();
        if (fp.getUserAgent() != null) {
            opts.setUserAgent(fp.getUserAgent());
        }
        opts.setViewportSize(fp.getWidth(), fp.getHeight());
        if (fp.getLocale() != null) {
            opts.setLocale(fp.getLocale());
        }
        if (fp.getTimezone() != null) {
            opts.setTimezoneId(fp.getTimezone());
        }
        opts.setJavaScriptEnabled(true);
        opts.setPermissions(new ArrayList<>());

        String proxy = cfg.getProxyFor(SourceType.TONGHUASHUN);
        if (proxy != null && !proxy.isBlank()) {
            opts.setProxy(parseProxy(proxy));
        }

        BrowserContext ctx = browser.newContext(opts);

        // stealth：在页面脚本执行前注入
        ctx.addInitScript(STEALTH_JS);

        // Cookie 登录态
        if (cfg.getCookieDir() != null && host != null) {
            List<Cookie> cookies = loadCookies(host, cfg.getCookieDir());
            if (!cookies.isEmpty()) {
                ctx.addCookies(cookies);
            }
        }
        return ctx;
    }

    /** 从 cookieDir/<host>.json 读取 Cookie 列表（Playwright 导出格式）。 */
    public List<Cookie> loadCookies(String host, String cookieDir) {
        List<Cookie> result = new ArrayList<>();
        if (cookieDir == null || host == null) {
            return result;
        }
        Path file = Paths.get(cookieDir, host + ".json");
        if (!Files.exists(file)) {
            return result;
        }
        try {
            List<Map<String, Object>> raw = MAPPER.readValue(
                    Files.readString(file), new TypeReference<List<Map<String, Object>>>() {
                    });
            for (Map<String, Object> c : raw) {
                String name = str(c, "name");
                String value = str(c, "value");
                if (name == null) {
                    continue;
                }
                Cookie ck = new Cookie(name, value)
                        .setDomain(str(c, "domain"))
                        .setPath(str(c, "path"));
                // 注意：不要 setUrl——Playwright Java Cookie 代理里 setPath() 会冲掉 url/domain，
                // 导致 addCookies 报 "should have either url or domain"。用 domain+path 组合即可通过校验。
                if (c.get("expires") instanceof Number) {
                    ck.setExpires(((Number) c.get("expires")).doubleValue());
                }
                if (Boolean.TRUE.equals(c.get("httpOnly"))) {
                    ck.setHttpOnly(true);
                }
                if (Boolean.TRUE.equals(c.get("secure"))) {
                    ck.setSecure(true);
                }
                if (c.get("sameSite") instanceof String) {
                    mapSameSite(ck, (String) c.get("sameSite"));
                }
                result.add(ck);
            }
        } catch (Exception e) {
            // Cookie 载入失败不阻断抓取，仅记录
            System.err.println("loadCookies failed for " + host + ": " + e.getMessage());
        }
        return result;
    }

    /** 保存 Cookie 到 cookieDir/<host>.json（登录态维持用）。 */
    public void saveCookies(BrowserContext context, String host, String cookieDir) {
        if (cookieDir == null || host == null) {
            return;
        }
        try {
            List<Cookie> cookies = context.cookies();
            Path dir = Paths.get(cookieDir);
            Files.createDirectories(dir);
            Files.writeString(Paths.get(cookieDir, host + ".json"), MAPPER.writeValueAsString(cookies));
        } catch (Exception e) {
            System.err.println("saveCookies failed for " + host + ": " + e.getMessage());
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static void mapSameSite(Cookie ck, String v) {
        try {
            ck.setSameSite(SameSiteAttribute.valueOf(v.toUpperCase()));
        } catch (IllegalArgumentException e) {
            // Cookie-编辑器导出常用 "unspecified"，Playwright 无此枚举，回退 Lax。
            ck.setSameSite(SameSiteAttribute.LAX);
        }
    }

    /** 从 url 提取 host（用于 Cookie 文件名）。 */
    public static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 proxy 字符串（http://user:pass@host:port）为 Playwright Proxy，显式设用户名/密码。
     * <p>注意：{@code new Proxy("http://user:pass@host:port")} 不会自动把 userinfo 作为代理认证发送，
     * 会导致 407；必须拆出来调 setUsername/setPassword（见 ProxySanityCheck3 验证）。</p>
     */
    public static Proxy parseProxy(String proxy) {
        if (proxy == null || proxy.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(proxy);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || port <= 0) {
                return new Proxy(proxy);
            }
            String userinfo = uri.getUserInfo();
            if (userinfo != null && userinfo.contains(":")) {
                String[] parts = userinfo.split(":", 2);
                return new Proxy(host + ":" + port).setUsername(parts[0]).setPassword(parts[1]);
            }
            return new Proxy(host + ":" + port);
        } catch (Exception e) {
            // 解析失败回退原行为
            return new Proxy(proxy);
        }
    }
}
