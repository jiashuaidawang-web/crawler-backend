package com.dunwugudao.crawler.strategy.parser;

import com.dunwugudao.crawler.core.model.CrawlContext;

/**
 * 分页/滚动迭代器抽象（能力5）。
 * <p>策略实现 {@link #hasNext()} / {@link #next()} 自动识别页码、无限滚动、
 * IntersectionObserver 懒加载、点击“加载更多”，防漏采。</p>
 */
public interface PageIterator {

    boolean hasNext();

    CrawlContext next();
}
