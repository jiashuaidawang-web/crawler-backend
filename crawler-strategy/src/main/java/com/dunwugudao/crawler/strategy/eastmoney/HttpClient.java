package com.dunwugudao.crawler.strategy.eastmoney;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;

/**
 * 简单 HTTP 客户端（给 ProxyClient 用）。
 */
public class HttpClient {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public static String get(String url, int timeoutMs) throws Exception {
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            if (resp.body() == null) throw new Exception("empty response");
            return resp.body().string();
        }
    }

    public static String postJson(String url, String json, int timeoutMs) throws Exception {
        RequestBody body = RequestBody.create(json, JSON);
        Request req = new Request.Builder().url(url).post(body).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            if (resp.body() == null) return "";
            return resp.body().string();
        }
    }
}
