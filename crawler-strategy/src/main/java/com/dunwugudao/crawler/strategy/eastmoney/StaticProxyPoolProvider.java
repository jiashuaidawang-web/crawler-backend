package com.dunwugudao.crawler.strategy.eastmoney;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 静态代理池（随机轮转）。
 * <p>从配置注入一批 {@code host:port:user:pwd} 格式的代理，每次 acquire 随机取一个。</p>
 */
public class StaticProxyPoolProvider implements ProxyProvider {

    private static final Logger log = LoggerFactory.getLogger(StaticProxyPoolProvider.class);

    private final List<String> proxies;
    private final AtomicInteger index = new AtomicInteger(0);

    /**
     * @param proxyLines 代理列表，每行格式 {@code host:port:username:password}
     */
    public StaticProxyPoolProvider(List<String> proxyLines) {
        List<String> parsed = new ArrayList<>();
        for (String line : proxyLines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split(":");
            if (parts.length == 4) {
                // host:port:user:pwd → http://user:pass@host:port
                parsed.add("http://" + parts[2] + ":" + parts[3] + "@" + parts[0] + ":" + parts[1]);
            } else if (parts.length == 2) {
                // host:port（无认证）
                parsed.add("http://" + parts[0] + ":" + parts[1]);
            } else {
                log.warn("[StaticProxyPool] 忽略格式错误的代理行: {}", line);
            }
        }
        this.proxies = Collections.unmodifiableList(parsed);
        log.info("[StaticProxyPool] 加载 {} 个静态代理", this.proxies.size());
    }

    @Override
    public String acquire() {
        if (proxies.isEmpty()) {
            return null;
        }
        // 随机取一个（避免所有线程用同一个）
        int i = ThreadLocalRandom.current().nextInt(proxies.size());
        return proxies.get(i);
    }
}
