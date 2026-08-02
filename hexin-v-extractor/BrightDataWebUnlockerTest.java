// hexin-v-extractor/BrightDataWebUnlockerTest.java
//
// 严格按 Bright Data 给的请求格式验证 Web Unlocker API 对东财域名的可用性：
//   curl https://api.brightdata.com/request
//     -H "Content-Type: application/json"
//     -H "Authorization: Bearer <KEY>"
//     -d '{"zone":"web_unlocker1","url":"<target>","format":"raw"}'
//
// 编译运行：
//   javac BrightDataWebUnlockerTest.java
//   java BrightDataWebUnlockerTest

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class BrightDataWebUnlockerTest {

    static final String API_KEY = "27fff2a2-4b09-4f34-aa30-6a8bfd9ad9d9";
    static final String ZONE = "web_unlocker1";
    static final String ENDPOINT = "https://api.brightdata.com/request";

    // 严格按给定格式的请求方法
    static Response unlock(String targetUrl) throws IOException {
        String body = "{\"zone\":\"" + ZONE + "\",\"url\":\"" + targetUrl + "\",\"format\":\"raw\"}";
        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + API_KEY);
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return new Response(code, sb.toString());
    }

    static class Response {
        int code;
        String body;
        Response(int code, String body) { this.code = code; this.body = body; }
    }

    static void test(String name, String url) {
        System.out.print("[" + name + "] ");
        try {
            long t0 = System.currentTimeMillis();
            Response r = unlock(url);
            long ms = System.currentTimeMillis() - t0;
            String b = r.body == null ? "" : r.body.trim();
            if (r.code == 200) {
                if (b.contains("\"data\"") && !b.contains("\"data\":null")) {
                    System.out.printf("OK   %5dB %5dms  DATA\n", b.length(), ms);
                } else if (b.contains("\"data\":null") || b.contains("\"rc\":102") || b.contains("\"result\":null")) {
                    System.out.printf("OK   %5dB %5dms  EMPTY/business-error  body=%s\n", b.length(), ms, b.substring(0, Math.min(80, b.length())).replaceAll("\\s+", " "));
                } else if (b.contains("<script") || b.contains("window.location")) {
                    System.out.printf("OK   %5dB %5dms  BLOCKED_HTML\n", b.length(), ms);
                } else if (b.isEmpty()) {
                    System.out.printf("OK   %5dB %5dms  EMPTY_BODY\n", b.length(), ms);
                } else {
                    System.out.printf("OK   %5dB %5dms  body=%s\n", b.length(), ms, b.substring(0, Math.min(80, b.length())).replaceAll("\\s+", " "));
                }
            } else {
                System.out.printf("XX   %5dB %5dms  HTTP %d  %s\n", b.length(), ms, r.code, b.substring(0, Math.min(80, b.length())).replaceAll("\\s+", " "));
            }
        } catch (Exception e) {
            System.out.println("ERR  " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 1. 自检：Bright Data 测试 URL ===");
        test("brdtest", "https://geo.brdtest.com/welcome.txt?product=unlocker&method=api");

        System.out.println("\n=== 2. 东财 4 大数据域名 ===");
        test("push2his 个股日线",
            "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=1.600000&klt=101&fqt=0&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61&end=20500101&lmt=3");
        test("push2 板块",
            "https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=5&po=1&np=1&fltt=2&invt=2&fs=m:90+t:2&fields=f12,f14,f3,f62");
        test("push2ex 涨停池",
            "https://push2ex.eastmoney.com/getTopicZTPool?ut=7eea3edcaed734bea9cbfc24409ed989&d=20260801&Pageindex=0&pagesize=5");
        test("datacenter 龙虎榜",
            "https://datacenter-web.eastmoney.com/api/data/get?type=RPT_DAILYBILLBOARD_DETAILS&filter=(TRADE_DATE%3D%2720260801%27)&page_size=5&pz=5&po=1&fields1=f1&fields2=f2,f3,f4,f5,f6,f7");
    }
}
