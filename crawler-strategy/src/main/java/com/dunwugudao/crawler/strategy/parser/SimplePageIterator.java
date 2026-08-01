package com.dunwugudao.crawler.strategy.parser;

import com.dunwugudao.crawler.core.model.CrawlContext;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 示例分页迭代器：按页码递增，到 totalPages 停止。
 * <p>每次 {@link #next()} 在原始 CrawlContext 的策略配置上写入 {@code page} 字段。滚动型分页在 M2 扩展。</p>
 */
public class SimplePageIterator implements PageIterator {

    private final CrawlContext base;
    private final int totalPages;
    private int currentPage;

    public SimplePageIterator(CrawlContext base, int totalPages) {
        this.base = base;
        this.totalPages = totalPages;
        this.currentPage = 1;
    }

    @Override
    public boolean hasNext() {
        return currentPage <= totalPages;
    }

    @Override
    public CrawlContext next() {
        if (!hasNext()) {
            throw new NoSuchElementException("no more pages");
        }
        Map<String, Object> cfg = new HashMap<>();
        if (base.getStrategyConfig() != null) {
            cfg.putAll(base.getStrategyConfig());
        }
        cfg.put("page", currentPage);

        CrawlContext ctx = new CrawlContext();
        ctx.setTask(base.getTask());
        ctx.setStrategyConfig(cfg);
        ctx.setRetryPolicy(base.getRetryPolicy());
        currentPage++;
        return ctx;
    }
}
