// hexin-v-extractor/EastmoneyProxyStabilityTest.java
//
// 东财 OkHttp + 普通代理池 稳定性/成功率压测。
// 模拟 EastmoneyClient 的请求模式：UA + Referer + 代理认证。
// 纯 Java（HttpURLConnection），无需外部依赖。
//
// 编译运行：
//   javac EastmoneyProxyStabilityTest.java
//   java EastmoneyProxyStabilityTest

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class EastmoneyProxyStabilityTest {

    // 代理池（与 TonghuashunProxyDemo 同一池子）
    static final String POOL = "http://124.223.220.245:8088/proxy/acquire";
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    static final String REFERER = "https://quote.eastmoney.com/";

    // 从池子取一个代理（返回 "http://user:pass@host:port" 或 null）
    static String acquireProxy() {
        try {
            String json = httpGet(POOL, null);
            if (json == null) return null;
            int i = json.indexOf("\"proxy\"");
            if (i < 0) return null;
            int s = json.indexOf('"', i + 7);
            int e = json.indexOf('"', s + 1);
            return json.substring(s + 1, e);
        } catch (Exception e) {
            return null;
        }
    }

    // 简单 GET（无代理，用于取代理）
    static String httpGet(String url, String proxyStr) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        int code = c.getResponseCode();
        InputStream is = (code < 400) ? c.getInputStream() : c.getErrorStream();
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    // 通过代理 GET（模拟 EastmoneyClient：UA + Referer + 代理认证）
    static Result httpGetViaProxy(String url, String proxyStr) {
        Result r = new Result();
        try {
            // 解析代理
            String rest = proxyStr;
            int idx = rest.indexOf("://");
            if (idx > 0) rest = rest.substring(idx + 3);
            String auth = null;
            int at = rest.lastIndexOf('@');
            if (at > 0) {
                auth = rest.substring(0, at);
                rest = rest.substring(at + 1);
            }
            int colon = rest.lastIndexOf(':');
            String host = rest.substring(0, colon);
            int port = Integer.parseInt(rest.substring(colon + 1));

            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(proxy);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Referer", REFERER);
            if (auth != null) {
                c.setRequestProperty("Proxy-Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8)));
            }
            long t0 = System.currentTimeMillis();
            r.httpCode = c.getResponseCode();
            r.latencyMs = System.currentTimeMillis() - t0;
            InputStream is = (r.httpCode < 400) ? c.getInputStream() : c.getErrorStream();
            if (is != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) >= 0) bos.write(buf, 0, n);
                r.body = bos.toString("UTF-8");
            }
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return r;
    }

    static class Result {
        int httpCode = -1;
        long latencyMs = -1;
        String body = "";
        String error = null;
    }

    // 判断东财响应是否"真正拿到数据"
    static boolean isData(Result r) {
        if (r.error != null) return false;
        if (r.httpCode != 200) return false;
        String b = r.body.trim();
        if (b.isEmpty()) return false;
        // 被拦截：返回反爬 JS / HTML
        if (b.startsWith("<") || b.contains("chameleon") || b.contains("window.location")) return false;
        // 正常 JSON 数据
        if (b.startsWith("{")) {
            // data 非空 / rc===0 / success===true
            if (b.contains("\"data\":null") || b.contains("\"result\":null")) return false;
            if (b.contains("\"rc\":102") || b.contains("\"success\":false")) return false;
            return true;
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        // 4 个东财核心接口
        List<String[]> targets = Arrays.asList(
            new String[]{"push2his/个股日线", "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=1.600000&klt=101&fqt=0&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61&end=20500101&lmt=3"},
            new String[]{"push2/板块",      "https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=5&po=1&np=1&fltt=2&invt=2&fs=m:90+t:2&fields=f12,f14,f3,f62"},
            new String[]{"push2ex/涨停池",   "https://push2ex.eastmoney.com/getTopicZTPool?ut=7eea3edcaed734bea9cbfc24409ed989&d=20260801&Pageindex=0&pagesize=5"},
            new String[]{"datacenter/龙虎榜","https://datacenter-web.eastmoney.com/api/data/get?type=RPT_DAILYBILLBOARD_DETAILS&filter=(TRADE_DATE%3D%2720260801%27)&page_size=5&pz=5&po=1&fields1=f1&fields2=f2,f3,f4,f5,f6,f7"}
        );

        int roundsPerTarget = 8; // 每个接口跑 8 轮
        System.out.println("=== 东财 OkHttp + 普通代理池 稳定性压测 ===");
        System.out.println("池子: " + POOL);
        System.out.println("每接口 " + roundsPerTarget + " 轮，每轮换一个代理\n");

        int totalOk = 0, totalAll = 0;
        List<Long> allLatencies = new ArrayList<>();

        for (String[] t : targets) {
            String name = t[0], url = t[1];
            int ok = 0;
            List<Long> lats = new ArrayList<>();
            System.out.println("--- " + name + " ---");
            for (int i = 1; i <= roundsPerTarget; i++) {
                String proxy = acquireProxy();
                if (proxy == null) {
                    System.out.printf("  [%d] 取代理失败，跳过%n", i);
                    continue;
                }
                Result r = httpGetViaProxy(url, proxy);
                totalAll++;
                if (isData(r)) {
                    ok++;
                    totalOk++;
                    lats.add(r.latencyMs);
                    allLatencies.add(r.latencyMs);
                    System.out.printf("  [%d] OK   %5dB %5dms  proxy=%s%n", i, r.body.length(), r.latencyMs, proxy.substring(Math.max(0, proxy.length() - 20)));
                } else {
                    String reason = r.error != null ? r.error.substring(0, Math.min(40, r.error.length()))
                        : ("HTTP " + r.httpCode + " body=" + r.body.substring(0, Math.min(30, r.body.length())));
                    System.out.printf("  [%d] XX   %s%n", i, reason);
                }
                Thread.sleep(300);
            }
            double avg = lats.isEmpty() ? 0 : lats.stream().mapToLong(Long::longValue).average().getAsDouble();
            System.out.printf("  >> %s: %d/%d 成功, 平均延迟 %.0fms%n%n", name, ok, roundsPerTarget, avg);
        }

        double avgAll = allLatencies.isEmpty() ? 0 : allLatencies.stream().mapToLong(Long::longValue).average().getAsDouble();
        System.out.println("================== 总 览 ==================");
        System.out.printf("总成功率: %d/%d (%.0f%%)%n", totalOk, totalAll, totalAll == 0 ? 0 : 100.0 * totalOk / totalAll);
        System.out.printf("平均延迟: %.0fms (仅成功请求)%n", avgAll);
    }
}
