package com.dunwugudao.crawler.strategy.eastmoney;

import com.dunwugudao.crawler.core.model.CrawlContext;
import com.dunwugudao.crawler.strategy.parser.PageIterator;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 东财分页迭代器（实现 {@link PageIterator}）。
 * <p>clist 场景：首次请求探明 {@code data.pages} 后注入 totalPages，按页码递增直到末页。
 * 池/Datacenter 场景建议用「返回空数组即停」模式（见 EastmoneyApiStrategy 的 while 循环），
 * 本迭代器也可用于「已知总页数」的分页；未知总数时由策略侧用空数组提前 break。</p>
 */
public class EastmoneyPageIterator implements PageIterator {

    private final CrawlContext base;
    private final int totalPages;
    private int currentPage;

    public EastmoneyPageIterator(CrawlContext base, int totalPages) {
        this.base = base;
        this.totalPages = Math.max(1, totalPages);
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
