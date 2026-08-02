// hexin-v-extractor/EastmoneyProxyQuickTest.java
// 东财 + 普通代理池 快速稳定性测试（短超时、少轮次、实时输出）
//
// 编译运行：
//   javac EastmoneyProxyQuickTest.java
//   java EastmoneyProxyQuickTest

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class EastmoneyProxyQuickTest {
    static final String POOL = "http://124.223.220.245:8088/proxy/acquire";
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    static final String REFERER = "https://quote.eastmoney.com/";

    static void log(String s) {
        System.out.println(s);
        System.out.flush();
    }

    static String acquireProxy() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(POOL).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String json = br.lines().reduce("", (a, b) -> a + b);
                int i = json.indexOf("\"proxy\"");
                if (i < 0) return null;
                int s = json.indexOf('"', i + 7);
                int e = json.indexOf('"', s + 1);
                if (s < 0 || e < 0) return null;
                return json.substring(s + 1, e);
            }
        } catch (Exception e) {
            return null;
        }
    }

    // 通过代理 GET，返回 [httpCode, body, latencyMs, error]
    static Object[] get(String url, String proxyStr) {
        String rest = proxyStr;
        int idx = rest.indexOf("://");
        if (idx > 0) rest = rest.substring(idx + 3);
        String auth = null;
        int at = rest.lastIndexOf('@');
        if (at > 0) { auth = rest.substring(0, at); rest = rest.substring(at + 1); }
        int colon = rest.lastIndexOf(':');
        String host = rest.substring(0, colon);
        int port = Integer.parseInt(rest.substring(colon + 1));
        try {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(proxy);
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Referer", REFERER);
            if (auth != null)
                c.setRequestProperty("Proxy-Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8)));
            long t0 = System.currentTimeMillis();
            int httpCode = c.getResponseCode();
            long ms = System.currentTimeMillis() - t0;
            InputStream is = (httpCode < 400) ? c.getInputStream() : c.getErrorStream();
            String body = "";
            if (is != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int n;
                while ((n = is.read(buf)) >= 0) bos.write(buf, 0, n);
                body = bos.toString("UTF-8");
            }
            return new Object[]{httpCode, body, ms, null};
        } catch (Exception e) {
            return new Object[]{-1, "", System.currentTimeMillis(), e.getClass().getSimpleName() + ": " + e.getMessage()};
        }
    }

    static boolean isData(Object[] r) {
        int code = (int) r[0];
        String body = ((String) r[1]).trim();
        if (code != 200 || body.isEmpty()) return false;
        // 劫持页/反爬：返回 HTML（路由器门户、ISP 登录页、反爬 JS）
        if (body.startsWith("<") || body.contains("<!DOCTYPE") || body.contains("<html")) return false;
        if (body.contains("chameleon") || body.contains("window.location")) return false;
        // 常见劫持页特征
        if (body.contains("ZXHN") || body.contains("login") && body.contains("portal")) return false;
        // 正常 JSON 数据
        if (body.startsWith("{")) {
            if (body.contains("\"data\":null") || body.contains("\"result\":null")) return false;
            if (body.contains("\"rc\":102") || body.contains("\"success\":false")) return false;
            return true;
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        String[][] targets = {
            {"push2his/个股日线", "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=1.600000&klt=101&fqt=0&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61&end=20500101&lmt=3"},
            {"push2/板块",       "https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=5&po=1&np=1&fltt=2&invt=2&fs=m:90+t:2&fields=f12,f14,f3,f62"},
            {"push2ex/涨停池",   "https://push2ex.eastmoney.com/getTopicZTPool?ut=7eea3edcaed734bea9cbfc24409ed989&d=20260801&Pageindex=0&pagesize=5"},
            {"datacenter/龙虎","https://datacenter-web.eastmoney.com/api/data/get?type=RPT_DAILYBILLBOARD_DETAILS&filter=(TRADE_DATE%3D%2720260801%27)&page_size=5&pz=5&po=1&fields1=f1&fields2=f2,f3,f4,f5,f6,f7"},
        };
        int rounds = 5;
        log("=== 东财 OkHttp + 普通代理池 快速稳定性 ===");
        log("池: " + POOL + "  每接口 " + rounds + " 轮\n");
        int totalOk = 0, totalAll = 0;
        List<Long> okLats = new ArrayList<>();

        for (String[] t : targets) {
            String name = t[0], url = t[1];
            int ok = 0;
            List<Long> lats = new ArrayList<>();
            log("--- " + name + " ---");
            for (int i = 1; i <= rounds; i++) {
                String proxy = acquireProxy();
                if (proxy == null) { log("  [" + i + "] 取代理失败"); continue; }
                Object[] r = get(url, proxy);
                totalAll++;
                if (isData(r)) {
                    ok++; totalOk++;
                    long ms = (long) r[2];
                    lats.add(ms); okLats.add(ms);
                    log(String.format("  [%d] OK   %5dB %5dmS  %s", i, ((String) r[1]).length(), ms, proxy.substring(Math.max(0, proxy.length() - 22))));
                } else {
                    int code = (int) r[0];
                    String body = ((String) r[1]);
                    String err = (String) r[3];
                    // 区分真 200 劫持页 vs 真实数据
                    String reason;
                    if (code == 200 && !body.isEmpty() && (body.startsWith("<") || body.contains("<html"))) {
                        String tag = body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                        reason = "HIJACKED_200: " + tag.substring(0, Math.min(30, tag.length()));
                    } else if (err != null) {
                        reason = err.substring(0, Math.min(35, err.length()));
                    } else {
                        reason = "HTTP_" + code + " " + body.substring(0, Math.min(25, body.length()));
                    }
                    log(String.format("  [%d] XX   %s", i, reason));
                }
                Thread.sleep(200);
            }
            double avg = lats.isEmpty() ? 0 : lats.stream().mapToLong(Long::longValue).average().getAsDouble();
            log(String.format("  >> %s: %d/%d 成功, 均迟 %.0fms%n", name, ok, rounds, avg));
        }
        double avgAll = okLats.isEmpty() ? 0 : okLats.stream().mapToLong(Long::longValue).average().getAsDouble();
        log("\n================== 总 览 ==================");
        log(String.format("成功率: %d/%d (%.0f%%)", totalOk, totalAll, totalAll == 0 ? 0 : 100.0 * totalOk / totalAll));
        log(String.format("均迟:   %.0fms (成功请求)", avgAll));
    }
}
