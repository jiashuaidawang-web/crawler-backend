package com.dunwugudao.crawler.strategy.eastmoney;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;

/**
 * proxy-pool 客户端（Java 版）。
 * 调用 proxy-pool API：acquire / report / stats / notify_refill。
 */
public class ProxyClient {

    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public ProxyClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
    }

    public ProxyInfo acquire() {
        try {
            String resp = httpGet(baseUrl + "/proxy/acquire");
            JsonNode node = mapper.readTree(resp);
            if (node.has("proxy") && !node.get("proxy").isNull()) {
                String proxy = node.get("proxy").asText();
                String supplier = node.has("supplier") ? node.get("supplier").asText() : "";
                return new ProxyInfo(proxy, supplier);
            }
            notifyRefill();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public void report(String proxy, boolean success, int latencyMs, String supplier) {
        try {
            String json = String.format("{\"proxy\":\"%s\",\"success\":%s,\"latency_ms\":%d,\"supplier\":\"%s\"}",
                    proxy.replace("\"", "\\\""), success, latencyMs, supplier != null ? supplier : "");
            httpPostJson(baseUrl + "/proxy/report", json);
        } catch (Exception e) {
            // 忽略
        }
    }

    public void notifyRefill() {
        try {
            httpPostJson(baseUrl + "/proxy/notify_refill", "{}");
        } catch (Exception e) {
            // 忽略
        }
    }

    public Stats stats() {
        try {
            String resp = httpGet(baseUrl + "/proxy/stats");
            JsonNode node = mapper.readTree(resp);
            Stats stats = new Stats();
            stats.availableTotal = node.has("available_total") ? node.get("available_total").asInt() : 0;
            stats.coolingTotal = node.has("cooling_total") ? node.get("cooling_total").asInt() : 0;
            stats.used = node.has("used") ? node.get("used").asInt() : 0;
            return stats;
        } catch (Exception e) {
            return new Stats();
        }
    }

    private String httpGet(String url) throws Exception {
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            if (resp.body() == null) throw new Exception("empty response");
            return resp.body().string();
        }
    }

    private String httpPostJson(String url, String json) throws Exception {
        RequestBody body = RequestBody.create(json, JSON);
        Request req = new Request.Builder().url(url).post(body).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            if (resp.body() == null) return "";
            return resp.body().string();
        }
    }

    public static class ProxyInfo {
        private final String proxy;
        private final String supplier;

        public ProxyInfo(String proxy, String supplier) {
            this.proxy = proxy;
            this.supplier = supplier;
        }

        public String getProxy() { return proxy; }
        public String getSupplier() { return supplier; }
        public String getProxyClean() {
            if (proxy != null && proxy.contains("://")) {
                return proxy.split("://", 2)[1];
            }
            return proxy;
        }
    }

    public static class Stats {
        public int availableTotal;
        public int coolingTotal;
        public int used;
    }
}
