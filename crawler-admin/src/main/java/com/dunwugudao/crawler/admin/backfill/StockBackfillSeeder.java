package com.dunwugudao.crawler.admin.backfill;

import com.dunwugudao.crawler.admin.seed.TaskTypeCatalog;
import com.dunwugudao.crawler.core.model.SourceType;
import com.dunwugudao.crawler.persistence.entity.CrawlTask;
import com.dunwugudao.crawler.persistence.mapper.CrawlTaskMapper;
import com.dunwugudao.crawler.persistence.mapper.StockBackfillStatusMapper;
import com.dunwugudao.crawler.persistence.mapper.StockDailyMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * stock_daily 历史回填任务种子生成器（STOCK_DAILY_HISTORY 模式）。
 *
 * <p><b>设计决策</b>：东财 push2 clist API 不支持历史日期（始终返回当前快照），
 * 因此历史回填必须使用 STOCK_DAILY_HISTORY（push2his kline 逐券拿全历史）。
 * 每只股票一个任务，lmt=50000（约80年），一次拿满。</p>
 *
 * <p>幂等：uniqueKey=STOCK_DAILY_HISTORY|source|tsCode，insertIfAbsent 保证重复运行不产生重复任务。
 * 断点续传：crawl_stock_backfill_status 记录每只股票进度，SUCCESS 的股票自动跳过。</p>
 */
@Slf4j
@Component
public class StockBackfillSeeder {

    private static final int BATCH = 100;
    /** 拉取天数上限（约80年） */
    private static final int DEFAULT_LMT = 50000;

    private final CrawlTaskMapper taskMapper;
    private final StockDailyMapper stockDailyMapper;
    private final StockBackfillStatusMapper backfillStatusMapper;

    public StockBackfillSeeder(CrawlTaskMapper taskMapper,
                                StockDailyMapper stockDailyMapper,
                                StockBackfillStatusMapper backfillStatusMapper) {
        this.taskMapper = taskMapper;
        this.stockDailyMapper = stockDailyMapper;
        this.backfillStatusMapper = backfillStatusMapper;
    }

    /**
     * 对全市场股票生成 STOCK_DAILY_HISTORY 回填任务。
     * <p>从 stock_daily 取 distinct ts_code，排除已是 SUCCESS 的股票（断点续传），
     * 每只股票一个任务，push2his kline lmt=50000 一次拿满历史。</p>
     *
     * @param source 数据源（1=东财）
     * @return 新插入的任务数
     */
    public int seedAllStocksHistory(int source) {
        // 1. 全量股票：从 stock_daily 取 distinct ts_code
        List<String> allCodes = stockDailyMapper.selectDistinctTsCode();
        if (allCodes == null || allCodes.isEmpty()) {
            log.warn("[StockBackfillSeeder] stock_daily 无股票数据，跳过");
            return 0;
        }
        log.info("[StockBackfillSeeder] stock_daily 共有 {} 只股票", allCodes.size());

        // 2. 排除已是 SUCCESS 的股票（断点续传）
        List<String> successCodes = backfillStatusMapper.selectSuccessCodes();
        Set<String> successSet = successCodes == null ? Set.of() : new HashSet<>(successCodes);
        log.info("[StockBackfillSeeder] 已 SUCCESS {} 只，跳过", successSet.size());

        List<String> pendingCodes = new ArrayList<>();
        for (String c : allCodes) {
            if (!successSet.contains(c)) {
                pendingCodes.add(c);
            }
        }
        log.info("[StockBackfillSeeder] 需回填 {} 只股票", pendingCodes.size());

        if (pendingCodes.isEmpty()) {
            return 0;
        }

        // 3. 初始化进度表 + 下发任务
        int inserted = 0;
        List<CrawlTask> batch = new ArrayList<>(BATCH);
        List<String> statusInitBatch = new ArrayList<>(BATCH);

        for (String code : pendingCodes) {
            statusInitBatch.add(code);
            int isHs = isHsByCode(code);
            String params = "{\"tsCode\":\"" + code + "\",\"isHs\":" + isHs + ",\"lmt\":" + DEFAULT_LMT + "}";
            CrawlTask task = buildTask(source, code, params);
            batch.add(task);

            if (batch.size() >= BATCH) {
                backfillStatusMapper.batchInsertIfAbsent(statusInitBatch);
                statusInitBatch.clear();
                inserted += flush(batch);
                batch.clear();
            }
        }
        if (!statusInitBatch.isEmpty()) {
            backfillStatusMapper.batchInsertIfAbsent(statusInitBatch);
        }
        if (!batch.isEmpty()) {
            inserted += flush(batch);
        }

        log.info("[StockBackfillSeeder] 完成: {} 只股票, {} 个任务", pendingCodes.size(), inserted);
        return inserted;
    }

    /**
     * 按股票代码前缀判断是否沪市：1=沪市 0=深市/北交所。
     */
    private int isHsByCode(String tsCode) {
        String code = tsCode.contains(".") ? tsCode.substring(0, tsCode.indexOf('.')) : tsCode;
        if (code.startsWith("6") || code.startsWith("11")) {
            return 1; // 沪市（60xxxx 主板 / 11xxxx 科创板）
        }
        return 0; // 深市/北交所
    }

    private CrawlTask buildTask(int source, String tsCode, String params) {
        CrawlTask t = new CrawlTask();
        t.setTaskType("STOCK_DAILY_HISTORY");
        t.setSource(SourceType.fromCode(source));
        t.setUrl(null);
        t.setParamsJson(params);
        t.setStatus("PENDING");
        t.setPriority(5);
        t.setRetryCount(0);
        t.setMaxRetry(3);
        t.setUniqueKey("STOCK_DAILY_HISTORY|" + source + "|" + tsCode);
        t.setExpectedCount(1);
        t.setExecutorType("JAVA");
        t.setJobType("BATCH");
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        return t;
    }

    private int flush(List<CrawlTask> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            return taskMapper.batchInsertIfAbsent(batch);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.warn("[StockBackfillSeeder] 批量插入冲突, 降级逐条, batchSize={}", batch.size());
            int inserted = 0;
            for (CrawlTask t : batch) {
                inserted += taskMapper.insertIfAbsent(t);
            }
            return inserted;
        }
    }
}
