package com.dunwugudao.crawler.admin.service;

import com.microsoft.playwright.*;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * Playwright 方式请求东财接口 demo。
 * 模拟真实浏览器行为，绕过反爬限制。
 */
@Slf4j
public class PlaywrightEastmoneyDemo {

    private static final String PROXY_POOL_URL = "http://124.223.220.245:8088";

    public static void main(String[] args) {
        // 从代理池获取 IP
        String proxyStr = acquireProxy();
        log.info("获取代理: {}", proxyStr);

        if (proxyStr == null) {
            log.error("获取代理失败");
            return;
        }

        // 解析代理
        String[] proxyParts = parseProxy(proxyStr);
        String host = proxyParts[0];
        int port = Integer.parseInt(proxyParts[1]);
        String user = proxyParts[2];
        String pass = proxyParts[3];

        // 启动 Playwright
        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(true);

            try (Browser browser = playwright.chromium().launch(launchOptions)) {
                Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .setProxy(new com.microsoft.playwright.options.Proxy(host + ":" + port)
                                .setUsername(user)
                                .setPassword(pass));

                try (BrowserContext context = browser.newContext(contextOptions)) {
                    Page page = context.newPage();

                    // 请求东财 clist 接口
                    String url = "https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=100&po=1&np=1&fltt=2&invt=2&fid=f3&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23&fields=f12,f14,f2,f3";

                    log.info("请求 URL: {}", url);

                    // 导航到 API URL
                    Response response = page.navigate(url);

                    log.info("响应状态: {}", response.status());

                    // 获取响应内容
                    String body = response.text();
                    log.info("响应长度: {}", body.length());
                    log.info("响应内容: {}", body.substring(0, Math.min(500, body.length())));
                }
            }
        } catch (Exception e) {
            log.error("请求失败", e);
        }
    }

    private static String acquireProxy() {
        try {
            String resp = org.apache.http.client.fluent.Executor.newInstance()
                    .execute(org.apache.http.client.fluent.Request.Get(PROXY_POOL_URL + "/proxy/acquire"))
                    .returnContent().asString();
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp).path("proxy").asText(null);
        } catch (Exception e) {
            log.error("获取代理失败: {}", e.getMessage());
            return null;
        }
    }

    private static String[] parseProxy(String proxy) {
        // http://user:pass@ip:port
        String[] result = new String[4];
        try {
            java.net.URI uri = java.net.URI.create(proxy);
            result[0] = uri.getHost();
            result[1] = String.valueOf(uri.getPort());
            String userinfo = uri.getUserInfo();
            if (userinfo != null && userinfo.contains(":")) {
                String[] parts = userinfo.split(":", 2);
                result[2] = parts[0];
                result[3] = parts[1];
            }
        } catch (Exception e) {
            log.error("解析代理失败: {}", e.getMessage());
        }
        return result;
    }
}
