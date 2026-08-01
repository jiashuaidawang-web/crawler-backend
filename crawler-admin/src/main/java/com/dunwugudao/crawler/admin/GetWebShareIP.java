package com.dunwugudao.crawler.admin;

/**
 * GetWebShareIP控制器
 *
 * @author null
 * @date 2026/8/1 16:49
 */
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.fluent.*;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import javax.net.ssl.SSLContext;

public class GetWebShareIP {
    public static void main(String[] args) throws Exception {

        // 0 去网站拿ip列表
        // 替换成你的WebShare API Key
        String apiKey = "svw6wipe4tmbqu4p8ezhxzehlbr1f2tf6cljk0am";


        // 1. 构建跳过SSL验证的上下文
        SSLContext sslContext = SSLContexts.custom()
                .loadTrustMaterial(null, (chain, authType) -> true)
                .build();
        SSLConnectionSocketFactory sslFactory = new SSLConnectionSocketFactory(
                sslContext,
                NoopHostnameVerifier.INSTANCE
        );

        // 2. 自定义HttpClient，配置SSL工厂
        CloseableHttpClient httpClient = HttpClients.custom()
                .setSSLSocketFactory(sslFactory)
                .build();

        // 3. 把自定义HttpClient传入Executor
        String res = Executor.newInstance(httpClient)
                .execute(
                        Request.Get("https://proxy.webshare.io/api/v2/proxy/list/?page_size=10")
                                .addHeader("Authorization", "Token " + apiKey)
                )
                .returnContent()
                .asString();

        System.out.println(res);

        // 4. 关闭连接
        httpClient.close();
    }
}
