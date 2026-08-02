package com.dunwugudao.crawler.strategy.eastmoney;

/**
 * 代理 IP 提供者（数据源无关）。
 * <p>实现类封装具体代理供应商（快代理私密代理、WebUnlocker 等）。
 * 每次调用返回一个可用代理字符串，格式统一为：
 * {@code http://user:pass@host:port} 或 {@code user:pass@host:port}。</p>
 *
 * <p>调用方不关心 IP 从哪来、怎么提取——只关心拿到的字符串能直接传给 OkHttp 代理。</p>
 */
public interface ProxyProvider {

    /**
     * 获取一个代理。
     *
     * @return 代理字符串（如 "http://user:pass@host:port"）；获取失败返回 null
     */
    String acquire();

    /**
     * 释放/报告一个代理不可用（可选实现，供提供者内部统计或拉黑）。
     *
     * @param proxy 要释放的代理字符串
     */
    default void release(String proxy) {
        // 默认空实现，提供者可按需覆盖
    }
}
