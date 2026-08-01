package com.dunwugudao.crawler.admin.seed;

import com.dunwugudao.crawler.admin.service.BoardBasicService;
import com.dunwugudao.crawler.core.model.SourceType;
import com.dunwugudao.crawler.persistence.entity.CrawlTask;
import com.dunwugudao.crawler.persistence.mapper.CrawlTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 种子任务生成器（M3-2 核心）。
 * <p>
 * 幂等保证：所有写入走 {@code insertIfAbsent / batchInsertIfAbsent}（ON CONFLICT (unique_key) DO NOTHING），
 * 重复 seed 不会重复入库；MyBatis 在冲突忽略时返回 0，据此统计本次"实际新插入条数"。
 * <p>
 * 量校验口径：
 * <ul>
 *   <li>市场级任务 {@code expectedCount=null} → 不做 VOLUME_DEVIATION。</li>
 *   <li>逐券任务 {@code expectedCount=1} → 行级量校验（见 TaskTypeCatalog）。</li>
 * </ul>
 */
@Slf4j
@Service
public class SeedGenerator {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int BATCH = 500;

    /** LIMIT_POOL 拆成的三个子任务 */
    private static final List<String> LIMIT_SUBTYPES =
            List.of(TaskTypeCatalog.LIMIT_UP, TaskTypeCatalog.LIMIT_DOWN, TaskTypeCatalog.LIMIT_ZHABAN);

    private final CrawlTaskMapper mapper;
    private final StockUniverseProvider universe;
    private final BoardBasicService boardBasicService;

    public SeedGenerator(CrawlTaskMapper mapper, StockUniverseProvider universe, BoardBasicService boardBasicService) {
        this.mapper = mapper;
        this.universe = universe;
        this.boardBasicService = boardBasicService;
    }

    /** 单个交易日的市场级 + 逐券种子（dailyCloseSeed 用）。 */
    public int dailySeed(String date, int source) {
        // 1. 先维护板块基础数据
        log.info("开始维护板块基础数据...");
        int[] boardResult = boardBasicService.maintain();
        log.info("板块维护完成：新增={}, 删除={}, 更新={}", boardResult[0], boardResult[1], boardResult[2]);

        // 2. 刷新股票/板块列表
        log.info("开始刷新股票/指数列表...");
        List<String> stockCodes = universe.stockCodes();
        List<String> indexCodes = universe.indexCodes();
        log.info("获取到 {} 只股票, {} 只指数", stockCodes.size(), indexCodes.size());

        // 3. 生成任务
        int n = 0;
        for (TaskTypeCatalog.TaskSpec spec : TaskTypeCatalog.marketWideTypes()) {
            if ("LIMIT_POOL".equals(spec.taskType())) {
                for (String limitType : LIMIT_SUBTYPES) {
                    n += insertOne(limitType, source, date, null, null);
                }
            } else {
                n += insertOne(spec.taskType(), source, date, null, null);
            }
        }
        for (TaskTypeCatalog.TaskSpec spec : TaskTypeCatalog.perInstrumentTypes()) {
            if ("INDEX_DAILY".equals(spec.taskType())) {
                for (String code : indexCodes) {
                    n += insertOne(spec.taskType(), source, date, code, spec.defaultExpected());
                }
            } else {
                for (String code : stockCodes) {
                    n += insertOne(spec.taskType(), source, date, code, spec.defaultExpected());
                }
            }
        }
        log.info("dailySeed date={} source={} inserted={}", date, source, n);
        return n;
    }

    /**
     * 历史区间回填（historyBackfill 用）。
     * <p>逐自然日遍历 [start, end]，<b>不过滤交易日</b>——交易日过滤留 TODO（后续接 trade_calendar 表）。
     * 运营建议：只回填交易日，避免无数据日产生空任务。逐券量大时务必先跑市场级或分批。</p>
     */
    public int backfill(String start, String end, int source, List<String> types) {
        LocalDate s = LocalDate.parse(start, FMT);
        LocalDate e = LocalDate.parse(end, FMT);
        List<String> typeFilter = (types == null || types.isEmpty()) ? null : types;

        int total = 0;
        // 市场级逐日批量插入（每批单一日期，量小直接逐条也可以，但统一走批量路径以备扩展）
        for (LocalDate d = s; !d.isAfter(e); d = d.plusDays(1)) {
            String date = d.format(FMT);
            total += backfillMarketWide(date, source, typeFilter);
            total += backfillPerInstrument(date, source, typeFilter);
        }
        log.info("backfill {}-{} source={} inserted={}", start, end, source, total);
        return total;
    }

    private int backfillMarketWide(String date, int source, List<String> typeFilter) {
        List<CrawlTask> batch = new ArrayList<>();
        for (TaskTypeCatalog.TaskSpec spec : TaskTypeCatalog.marketWideTypes()) {
            if (typeFilter != null && !typeFilter.contains(spec.taskType())) {
                continue;
            }
            if ("LIMIT_POOL".equals(spec.taskType())) {
                for (String limitType : LIMIT_SUBTYPES) {
                    batch.add(buildTask(limitType, source, date, null, null));
                }
            } else {
                batch.add(buildTask(spec.taskType(), source, date, null, null));
            }
        }
        return flush(batch);
    }

    private int backfillPerInstrument(String date, int source, List<String> typeFilter) {
        int total = 0;
        for (TaskTypeCatalog.TaskSpec spec : TaskTypeCatalog.perInstrumentTypes()) {
            if (typeFilter != null && !typeFilter.contains(spec.taskType())) {
                continue;
            }
            List<String> codes = "INDEX_DAILY".equals(spec.taskType())
                    ? universe.indexCodes() : universe.stockCodes();
            if (codes.isEmpty()) {
                continue;
            }
            List<CrawlTask> batch = new ArrayList<>(BATCH);
            for (String code : codes) {
                batch.add(buildTask(spec.taskType(), source, date, code, spec.defaultExpected()));
                if (batch.size() >= BATCH) {
                    total += flush(batch);
                    batch.clear();
                }
            }
            total += flush(batch);
        }
        return total;
    }

    /** 龙虎榜明细子任务（需先爬完 DRAGON_TIGER 拿到代码列表再调用）。M3 未自动串联，留 TODO。 */
    public int seedDragonTigerDetails(String date, List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return 0;
        }
        List<CrawlTask> batch = new ArrayList<>(BATCH);
        int total = 0;
        for (String code : codes) {
            // 逐券唯一键：DRAGON_TIGER_DETAIL|source|code|date
            batch.add(buildTask("DRAGON_TIGER_DETAIL", 1, date, code, 1,
                    TaskTypeCatalog.buildParams("DRAGON_TIGER_DETAIL", date, code)));
            if (batch.size() >= BATCH) {
                total += flush(batch);
                batch.clear();
            }
        }
        total += flush(batch);
        log.info("seedDragonTigerDetails date={} codes={} inserted={}", date, codes.size(), total);
        return total;
    }

    private int flush(List<CrawlTask> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        return mapper.batchInsertIfAbsent(batch);
    }

    private int insertOne(String taskType, int source, String date, String code, Integer expected) {
        return mapper.insertIfAbsent(buildTask(taskType, source, date, code, expected));
    }

    private CrawlTask buildTask(String taskType, int source, String date, String code, Integer expected) {
        String params;
        if (LIMIT_SUBTYPES.contains(taskType)) {
            String limitType = taskType; // LIMIT_UP/LIMIT_DOWN/LIMIT_ZHABAN 即 limitType
            params = TaskTypeCatalog.buildParams(taskType, date, null, limitType);
        } else {
            params = TaskTypeCatalog.buildParams(taskType, date, code);
        }
        return buildTask(taskType, source, date, code, expected, params);
    }

    private CrawlTask buildTask(String taskType, int source, String date, String code, Integer expected, String params) {
        CrawlTask t = new CrawlTask();
        t.setTaskType(taskType);
        t.setSource(SourceType.fromCode(source));
        t.setUrl(null);
        t.setParamsJson(params);
        t.setStatus("PENDING");
        t.setPriority(5);
        t.setRetryCount(0);
        t.setMaxRetry(3);
        if (code != null) {
            t.setUniqueKey(TaskTypeCatalog.buildUniqueKey(taskType, source, code, date));
        } else {
            t.setUniqueKey(TaskTypeCatalog.buildUniqueKey(taskType, source, date));
        }
        t.setExpectedCount(expected);
        t.setCreatedAt(java.time.LocalDateTime.now());
        t.setUpdatedAt(java.time.LocalDateTime.now());
        return t;
    }
}
