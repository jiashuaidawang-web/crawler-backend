package com.dunwugudao.crawler.strategy.tonghuashun;

import com.dunwugudao.crawler.core.config.AntiCrawlConfig;
import com.dunwugudao.crawler.core.model.CrawlContext;
import com.dunwugudao.crawler.core.model.CrawlResult;
import com.dunwugudao.crawler.core.model.CrawlTask;
import com.dunwugudao.crawler.core.model.SourceType;
import com.dunwugudao.crawler.core.strategy.SourceStrategy;
import com.dunwugudao.crawler.core.util.JsonCheckpoint;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 同花顺板块直连策略(Playwright 直连代理,不用 CloakBrowser)。
 * <p>处理 {@code THS_PLATE_DIRECT} 任务类型。</p>
 */
@Slf4j
public class ThsPlateDirectStrategy implements SourceStrategy {

    private final ThsPlateDirectCrawler crawler;

    public ThsPlateDirectStrategy(ThsPlateDirectCrawler crawler) {
        this.crawler = crawler;
    }

    @Override
    public boolean supports(SourceType source) {
        return source == SourceType.TONGHUASHUN;
    }

    @Override
    public boolean supports(String taskType) {
        return "THS_PLATE_DIRECT".equals(taskType);
    }

    @Override
    public CrawlResult fetch(CrawlContext ctx) {
        CrawlTask task = ctx.getTask();
        Map<String, Object> params = JsonCheckpoint.deserialize(task.getParamsJson());

        int parseInt = parseInt(params.get("plate_type"), 6);
        String tradeDate = String.valueOf(params.getOrDefault("tradeDate",
                java.time.LocalDate.now().toString()));

        log.info("[ThsPlateDirectStrategy] THS_PLATE_DIRECT 开始: plateType={}, tradeDate={}", parseInt, tradeDate);

        try {
            // ThsPlateDirectCrawler 已注入 ProxyProvider,不需要 AntiCrawlConfig
            List<Map<String, Object>> rows = crawler.crawl(parseInt, tradeDate, null);
            log.info("[ThsPlateDirectStrategy] THS_PLATE_DIRECT 完成: plateType={}, rows={}", parseInt, rows.size());
            CrawlResult result = new CrawlResult();
            result.setSuccess(true);
            result.setData(rows);
            result.setRowCount(rows.size());
            result.setHttpStatus(200);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("THS_PLATE_DIRECT crawl failed: " + e.getMessage(), e);
        }
    }

    private static int parseInt(Object o, int fallback) {
        if (o == null) return fallback;
        if (o instanceof Number n) return n.intValue();
        String s = String.valueOf(o).trim();
        if (s.isEmpty() || "-".equals(s)) return fallback;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
