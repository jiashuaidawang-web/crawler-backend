import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Proxy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Playwright 抓取东财龙虎榜个股明细页的网络请求，找出席位明细（dt_detail）的 reportName。
 * 运行：cd hexin-v-extractor && mvn -q exec:java -Dexec.mainClass=PlaywrightLhbSeatDetailFinder -Dexec.classpathScope=test 2>&1
 * 或直接 main 里跑。
 */
public class PlaywrightLhbSeatDetailFinder {

    // 青果代理（与 SeedStrategyBeans 同配置）
    private static final String PROXY_API = "http://124.223.220.245:8088/proxy/acquire";

    public static void main(String[] args) throws Exception {
        // 1) 获取代理
        String proxyStr = acquireProxy();
        System.out.println("[proxy] " + proxyStr);
        if (proxyStr == null) {
            System.out.println("获取代理失败，退出");
            return;
        }
        String[] p = parseProxy(proxyStr);
        String host = p[0];
        int port = Integer.parseInt(p[1]);
        String user = p[2];
        String pass = p[3];

        // 2) 启动 Playwright
        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions().setHeadless(true);
            try (Browser browser = playwright.chromium().launch(launch)) {
                Browser.NewContextOptions ctxOpts = new Browser.NewContextOptions()
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                        .setProxy(new Proxy(host + ":" + port).setUsername(user).setPassword(pass));

                try (BrowserContext context = browser.newContext(ctxOpts)) {
                    Page page = context.newPage();

                    // 3) 收集所有 datacenter-web.eastmoney.com 的请求 URL
                    List<String> dcUrls = new ArrayList<>();
                    page.onRequest(req -> {
                        String url = req.url();
                        if (url.contains("datacenter-web.eastmoney.com")) {
                            dcUrls.add(url);
                        }
                    });

                    // 4) 打开龙虎榜个股明细页（盛达资源 000603，昨日上榜）
                    //    页面 URL 格式：https://data.eastmoney.com/lhb/StockHdStatistics/{code}.html
                    String pageUrl = "https://data.eastmoney.com/lhb/StockHdStatistics/000603.html";
                    System.out.println("[navigate] " + pageUrl);
                    page.navigate(pageUrl);

                    // 等页面加载 + 等额外的 XHR（席位明细是异步加载）
                    page.waitForLoadState(LoadState.NETWORKIDLE);
                    Thread.sleep(3000); // 再等 3 秒让异步请求完成

                    // 5) 输出抓到的 datacenter 请求
                    System.out.println("\n=== 抓到 " + dcUrls.size() + " 个 datacenter-web 请求 ===");
                    for (String u : dcUrls) {
                        System.out.println(u);
                    }

                    // 6) 解析每个 URL 的 reportName 和 filter（找含 SECURITY_CODE/SEAT_NAME 的）
                    System.out.println("\n=== 解析 reportName ===");
                    for (String u : dcUrls) {
                        try {
                            String query = java.net.URI.create(u).getQuery();
                            if (query == null) continue;
                            for (String kv : query.split("&")) {
                                if (kv.startsWith("reportName=")) {
                                    String rn = java.net.URLDecoder.decode(kv.substring("reportName=".length()), "UTF-8");
                                    System.out.println("reportName = " + rn);
                                }
                                if (kv.startsWith("filter=")) {
                                    String filter = java.net.URLDecoder.decode(kv.substring("filter=".length()), "UTF-8");
                                    System.out.println("  filter   = " + filter);
                                }
                                if (kv.startsWith("columns=")) {
                                    String cols = java.net.URLDecoder.decode(kv.substring("columns=".length()), "UTF-8");
                                    System.out.println("  columns  = " + cols);
                                }
                            }
                        } catch (Exception ignore) {}
                    }
                }
            }
        }
    }

    private static String acquireProxy() {
        try {
            String resp = org.apache.http.client.fluent.Executor.newInstance()
                    .execute(org.apache.http.client.fluent.Request.Get(PROXY_API))
                    .returnContent().asString();
            JsonNode node = new ObjectMapper().readTree(resp);
            return node.path("proxy").asText(null);
        } catch (Exception e) {
            System.out.println("获取代理失败: " + e.getMessage());
            return null;
        }
    }

    private static String[] parseProxy(String proxy) {
        String[] r = new String[4];
        try {
            java.net.URI uri = java.net.URI.create(proxy);
            r[0] = uri.getHost();
            r[1] = String.valueOf(uri.getPort());
            String info = uri.getUserInfo();
            if (info != null && info.contains(":")) {
                String[] ps = info.split(":", 2);
                r[2] = ps[0];
                r[3] = ps[1];
            }
        } catch (Exception e) {
            System.out.println("解析代理失败: " + e.getMessage());
        }
        return r;
    }
}
