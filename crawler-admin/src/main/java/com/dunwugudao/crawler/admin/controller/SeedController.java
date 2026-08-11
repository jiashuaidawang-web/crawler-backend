package com.dunwugudao.crawler.admin.controller;

import com.dunwugudao.crawler.admin.dto.SeedRequest;
import com.dunwugudao.crawler.admin.dto.SeederRequest;
import com.dunwugudao.crawler.admin.seed.ConceptSeeder;
import com.dunwugudao.crawler.admin.seed.FinancialSeeder;
import com.dunwugudao.crawler.admin.seed.NewsEventSeeder;
import com.dunwugudao.crawler.admin.seed.SeedGenerator;
import com.dunwugudao.crawler.admin.seed.TradeCalendarSeeder;
import com.dunwugudao.crawler.core.model.SourceType;
import com.dunwugudao.crawler.persistence.entity.CrawlTask;
import com.dunwugudao.crawler.persistence.mapper.CrawlTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 种子任务下发：插入 crawl_task（PENDING）供 worker 认领。
 * <p>STOCK_DAILY 自动走 {@link SeedGenerator#seedStockDailyPages} 按页拆任务（每页 100 条）；
 * 其他 taskType 直接插入单个 task。</p>
 */
@RestController
@RequestMapping("/api/crawl")
@RequiredArgsConstructor
public class SeedController {

    private final CrawlTaskMapper crawlTaskMapper;
    private final SeedGenerator seedGenerator;
    private final ConceptSeeder conceptSeeder;
    private final TradeCalendarSeeder tradeCalendarSeeder;
    private final FinancialSeeder financialSeeder;
    private final NewsEventSeeder newsEventSeeder;

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestBody SeedRequest req) {
        // STOCK_DAILY：按页拆任务（每页 100 条，避免单 task 过大）
        if ("STOCK_DAILY".equals(req.taskType())) {
            String date = req.tradeDate() != null ? req.tradeDate() : "2026-08-01";
            int inserted = seedGenerator.seedStockDailyPages(req.source() == null ? 1 : req.source(), date);
            Map<String, Object> r = new HashMap<>();
            r.put("taskType", "STOCK_DAILY");
            r.put("date", date);
            r.put("pagesInserted", inserted);
            return r;
        }

        // 其他 taskType：直接插入单个 task（幂等）
        CrawlTask task = new CrawlTask();
        task.setTaskType(req.taskType());
        task.setSource(SourceType.fromCode(req.source() == null ? 1 : req.source()));
        task.setUrl(req.url());
        task.setParamsJson(req.paramsJson());
        task.setUniqueKey(req.uniqueKey());
        task.setExpectedCount(req.expectedCount());
        task.setStatus("PENDING");
        task.setPriority(req.priority() == null ? 5 : req.priority());
        task.setRetryCount(0);
        task.setMaxRetry(req.maxRetry() == null ? 3 : req.maxRetry());
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        // 幂等：重复 seed 不报错，返回已存在/新插入的 task
        int rows = crawlTaskMapper.insertIfAbsent(task);

        Map<String, Object> r = new HashMap<>();
        r.put("taskId", task.getTaskId());
        r.put("status", "PENDING");
        r.put("inserted", rows);  // 1=新插入, 0=已存在
        return r;
    }

    /**
     * 下发个股分钟K线（STOCK_KLINE_MINUTE）—— 东方财富 push2his kline klt=1。
     * <p>极简接口：source 固定东财(1)，只需 tsCode（路径）+ tradeDate（可选，默认今天）。</p>
     * <p>curl: POST /api/crawl/seed-minute/600000.SH?tradeDate=2026-08-10</p>
     */
    @PostMapping("/seed-minute/{tsCode}")
    public Map<String, Object> seedMinuteKline(@PathVariable String tsCode,
            @RequestParam(required = false) String tradeDate) {
        String date = (tradeDate != null && !tradeDate.isBlank()) ? tradeDate : LocalDate.now().toString();
        int source = 1; // 东方财富
        String paramsJson = "{\"tsCode\":\"" + tsCode + "\",\"tradeDate\":\"" + date + "\"}";
        String uniqueKey = "STOCK_KLINE_MINUTE|" + source + "|" + tsCode + "|" + date;

        CrawlTask task = new CrawlTask();
        task.setTaskType("STOCK_KLINE_MINUTE");
        task.setSource(SourceType.fromCode(source));
        task.setParamsJson(paramsJson);
        task.setUniqueKey(uniqueKey);
        task.setExpectedCount(1);
        task.setStatus("PENDING");
        task.setPriority(5);
        task.setRetryCount(0);
        task.setMaxRetry(3);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        int rows = crawlTaskMapper.insertIfAbsent(task);

        Map<String, Object> r = new HashMap<>();
        r.put("taskType", "STOCK_KLINE_MINUTE");
        r.put("tsCode", tsCode);
        r.put("tradeDate", date);
        r.put("inserted", rows);
        r.put("taskId", task.getTaskId());
        return r;
    }

    /** 从 board_basic 派生 concept 维表（非爬虫，直接写入）。 */
    @PostMapping("/seed-concept")
    public Map<String, Object> seedConcept(@RequestBody(required = false) SeedRequest req) {
        int source = req != null && req.source() != null ? req.source() : 1;
        int n = conceptSeeder.seedFromBoardBasic(source);
        Map<String, Object> r = new HashMap<>();
        r.put("table", "concept");
        r.put("inserted", n);
        return r;
    }

    /** 生成交易日历（非爬虫，直接写入）。from/to 可空，空则默认 2020-01-01 ~ 2030-12-31。 */
    @PostMapping("/seed-trade-calendar")
    public Map<String, Object> seedTradeCalendar(@RequestBody(required = false) SeedRequest req) {
        int source = req != null && req.source() != null ? req.source() : 1;
        LocalDate from = (req != null && req.from() != null && !req.from().isBlank())
                ? LocalDate.parse(req.from()) : null;
        LocalDate to = (req != null && req.to() != null && !req.to().isBlank())
                ? LocalDate.parse(req.to()) : null;
        int n = tradeCalendarSeeder.seedRange(from, to, source);
        Map<String, Object> r = new HashMap<>();
        r.put("table", "trade_calendar");
        r.put("inserted", n);
        r.put("from", from != null ? from : "2020-01-01");
        r.put("to", to != null ? to : "2030-12-31");
        return r;
    }

    /**
     * 下发北向资金抓取任务（NORTHBOUND_FLOW）—— 真实东财 kamt 端点，worker 认领后落 northbound_flow 表。
     * <p>注意：kamt 为实时端点（返回当日数据），历史回填需用 datacenter 报告（当前 API 变更暂不可回填）。</p>
     */

    /**
     * 下发指数日线全市场快照任务(INDEX_DAILY)—— 东财 push2 clist fs=b:MK0010,一次拿 43 只。
     * <p>市场级单任务,worker 认领后落 index_daily 表。</p>
     * <p>curl: POST /api/crawl/seed-index-daily?tradeDate=2026-08-11&amp;source=1</p>
     */
    @PostMapping("/seed-index-daily")
    public Map<String, Object> seedIndexDaily(@RequestBody(required = false) SeedRequest req) {
        String date = req != null && req.tradeDate() != null ? req.tradeDate() : LocalDate.now().toString();
        int source = req != null && req.source() != null ? req.source() : 1;
        int n = seedGenerator.seedIndexDaily(source, date);
        Map<String, Object> r = new HashMap<>();
        r.put("taskType", "INDEX_DAILY");
        r.put("date", date);
        r.put("inserted", n);
        return r;
    }
    @PostMapping("/seed-northbound")
    public Map<String, Object> seedNorthbound(@RequestBody(required = false) SeedRequest req) {
        String date = req != null && req.tradeDate() != null ? req.tradeDate() : "2026-08-07";
        int source = req != null && req.source() != null ? req.source() : 1;
        int n = seedGenerator.seedNorthbound(source, date);
        Map<String, Object> r = new HashMap<>();
        r.put("taskType", "NORTHBOUND_FLOW");
        r.put("date", date);
        r.put("inserted", n);
        return r;
    }

    /**
     * 初始化分时配置表:从5个池子取 distinct 股票,type=minute 插入 stock_task_config。
     * <p>curl: POST /api/crawl/init-task-config</p>
     */
    @PostMapping("/init-task-config")
    public Map<String, Object> initTaskConfig() {
        int n = seedGenerator.initTaskConfigFromPools();
        Map<String, Object> r = new HashMap<>();
        r.put("action", "init-task-config");
        r.put("inserted", n);
        return r;
    }

    /**
     * 下发分时分钟线任务(STOCK_KLINE_MINUTE):从配置表取 type=minute 的股票,逐个生成任务。
     * <p>curl: POST /api/crawl/seed-stock-kline-minute?tradeDate=2026-08-11&amp;source=1</p>
     */
    @PostMapping("/seed-stock-kline-minute")
    public Map<String, Object> seedStockKlineMinute(@RequestBody(required = false) SeedRequest req) {
        String date = req != null && req.tradeDate() != null ? req.tradeDate() : LocalDate.now().toString();
        int source = req != null && req.source() != null ? req.source() : 1;
        int n = seedGenerator.seedStockKlineMinute(source, date);
        Map<String, Object> r = new HashMap<>();
        r.put("taskType", "STOCK_KLINE_MINUTE");
        r.put("date", date);
        r.put("inserted", n);
        return r;
    }

    /**
     * 下发龙虎榜主表抓取任务（DRAGON_TIGER）—— 经东财 datacenter 新版接口（reportName=RPT_DAILYBILLBOARD_DETAILSNEW）。
     * worker 认领后落 dragon_tiger 表。市场级，每日一条。
     */
    @PostMapping("/seed-dragon-tiger")
    public Map<String, Object> seedDragonTiger(@RequestBody(required = false) SeedRequest req) {
        String date = req != null && req.tradeDate() != null ? req.tradeDate() : "2026-08-07";
        int source = req != null && req.source() != null ? req.source() : 1;
        int n = seedGenerator.seedDragonTiger(source, date);
        Map<String, Object> r = new HashMap<>();
        r.put("taskType", "DRAGON_TIGER");
        r.put("date", date);
        r.put("inserted", n);
        return r;
    }

    /**
     * 财报种子（financial）—— 经 akshare-bridge 服务获取（datacenter 当前 9501 不可用）。
     * <p>tsCodes 可空：空则从 stock_daily 最新交易日去重取股票池，封顶 akshare.bridge.max-stocks。
     * bridge 未启用时返回 inserted=0（见控制台 WARN）。</p>
     */
    @PostMapping("/seed-financial")
    public Map<String, Object> seedFinancial(@RequestBody(required = false) SeederRequest req) {
        int source = req != null && req.source() != null ? req.source() : 0;
        List<String> tsCodes = req != null ? req.tsCodes() : null;
        int n = financialSeeder.seed(source, tsCodes);
        Map<String, Object> r = new HashMap<>();
        r.put("table", "financial");
        r.put("inserted", n);
        if (tsCodes != null) {
            r.put("requestedStocks", tsCodes.size());
        }
        return r;
    }

    /**
     * 新闻/政策/题材事件种子（news_event）—— 纯 Java 直连东财 7×24 快讯端点。
     * <p>limit 可空（默认 200，上限 500）。自动映射 event_id（东财 code）/ event_time / related_board（板块代码）。</p>
     */
    @PostMapping("/seed-news-event")
    public Map<String, Object> seedNewsEvent(@RequestBody(required = false) SeederRequest req) {
        int source = req != null && req.source() != null ? req.source() : 0;
        Integer limit = req != null ? req.limit() : null;
        int n = newsEventSeeder.seedRecent(source, limit);
        Map<String, Object> r = new HashMap<>();
        r.put("table", "news_event");
        r.put("inserted", n);
        return r;
    }
}
