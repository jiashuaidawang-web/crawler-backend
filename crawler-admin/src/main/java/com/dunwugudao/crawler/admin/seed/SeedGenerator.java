package com.dunwugudao.crawler.admin.seed;

import com.dunwugudao.crawler.admin.pipeline.SeedResult;
import com.dunwugudao.crawler.admin.service.BoardBasicService;
import com.dunwugudao.crawler.core.model.SourceType;
import com.dunwugudao.crawler.persistence.entity.BoardBasic;
import com.dunwugudao.crawler.persistence.entity.CrawlTask;
import com.dunwugudao.crawler.persistence.entity.StockBackfillStatus;
import com.dunwugudao.crawler.persistence.entity.StockTaskConfig;
import com.dunwugudao.crawler.persistence.mapper.BoardBasicMapper;
import com.dunwugudao.crawler.persistence.mapper.CrawlTaskMapper;
import com.dunwugudao.crawler.persistence.mapper.DragonTigerMapper;
import com.dunwugudao.crawler.persistence.mapper.StockBackfillStatusMapper;
import com.dunwugudao.crawler.persistence.mapper.CixinPoolMapper;
import com.dunwugudao.crawler.persistence.mapper.LimitDownPoolMapper;
import com.dunwugudao.crawler.persistence.mapper.LimitUpPoolMapper;
import com.dunwugudao.crawler.persistence.mapper.StrongPoolMapper;
import com.dunwugudao.crawler.persistence.mapper.StockDailyMapper;
import com.dunwugudao.crawler.persistence.mapper.StockTaskConfigMapper;
import com.dunwugudao.crawler.persistence.mapper.ZhabanPoolMapper;
import com.dunwugudao.crawler.persistence.service.StockWeeklyAggregator;
import com.dunwugudao.crawler.strategy.eastmoney.EastmoneyClient;
import com.dunwugudao.crawler.strategy.eastmoney.EastmoneyEndpoints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** 批次大小：STOCK_DAILY_HISTORY 用 100（避免 UNION ALL 过大导致 PG 解析慢） */
    private static final int BATCH = 100;

    /** LIMIT_POOL 拆成的三个子任务 */
    private static final List<String> LIMIT_SUBTYPES =
            List.of(TaskTypeCatalog.LIMIT_UP, TaskTypeCatalog.LIMIT_DOWN, TaskTypeCatalog.LIMIT_ZHABAN);

    private final CrawlTaskMapper mapper;
    private final StockUniverseProvider universe;
    private final BoardBasicMapper boardBasicMapper;
    private final DragonTigerMapper dragonTigerMapper;
    private final ProxyManager proxyManager;
    private final EastmoneyClient eastmoneyClient;
    private final StockBackfillStatusMapper backfillStatusMapper;
    private final StockDailyMapper stockDailyMapper;
    private final StockWeeklyAggregator stockWeeklyAggregator;
    private final StockTaskConfigMapper taskConfigMapper;
    private final LimitUpPoolMapper limitUpPoolMapper;
    private final LimitDownPoolMapper limitDownPoolMapper;
    private final ZhabanPoolMapper zhabanPoolMapper;
    private final StrongPoolMapper strongPoolMapper;
    private final CixinPoolMapper cixinPoolMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** STOCK_DAILY 每页条数（东财 clist pz 最大值 100）。 */
    private final int stockDailyPageSize;
    /** STOCK_DAILY 探测失败兜底全市场股票数(保证 task 数)。 */
    private final int stockDailyNowStockSize;
    /** STOCK_BY_BOARD 每页条数。 */
    private static final int BOARD_BY_BOARD_PAGE_SIZE = 100;

    public SeedGenerator(CrawlTaskMapper mapper, StockUniverseProvider universe,
                         BoardBasicMapper boardBasicMapper,
                         DragonTigerMapper dragonTigerMapper,
                         ProxyManager proxyManager,
                         EastmoneyClient eastmoneyClient,
                         StockBackfillStatusMapper backfillStatusMapper,
                         StockDailyMapper stockDailyMapper,
                         StockWeeklyAggregator stockWeeklyAggregator,
                         StockTaskConfigMapper taskConfigMapper,
                         LimitUpPoolMapper limitUpPoolMapper,
                         LimitDownPoolMapper limitDownPoolMapper,
                         ZhabanPoolMapper zhabanPoolMapper,
                         StrongPoolMapper strongPoolMapper,
                         CixinPoolMapper cixinPoolMapper,
                         org.springframework.core.env.Environment env) {
        this.mapper = mapper;
        this.universe = universe;
        this.boardBasicMapper = boardBasicMapper;
        this.dragonTigerMapper = dragonTigerMapper;
        this.proxyManager = proxyManager;
        this.eastmoneyClient = eastmoneyClient;
        this.backfillStatusMapper = backfillStatusMapper;
        this.stockDailyMapper = stockDailyMapper;
        this.stockWeeklyAggregator = stockWeeklyAggregator;
        this.taskConfigMapper = taskConfigMapper;
        this.limitUpPoolMapper = limitUpPoolMapper;
        this.limitDownPoolMapper = limitDownPoolMapper;
        this.zhabanPoolMapper = zhabanPoolMapper;
        this.strongPoolMapper = strongPoolMapper;
        this.cixinPoolMapper = cixinPoolMapper;
        // 每页 100 条（56 页）；总页数改为探测 data.total 后计算，不再依赖硬编码总股数
        this.stockDailyPageSize = Integer.parseInt(env.getProperty("stock-daily.page-size", "100"));
        // 探测失败兜底全市场股票数(默认 5545,保证 task 数)
        this.stockDailyNowStockSize = Integer.parseInt(env.getProperty("stock-daily.now-stock-size", "5545"));
    }

    /**
    /** 单个交易日的市场级 + 逐券种子（dailyCloseSeed 用）。 */
    public int dailySeed(String date, int source) {
        // board_basic 改为 board_daily 同步的副作用维护，不再单独 maintain（见 BoardBasicSyncService）

        // 2. 刷新股票/板块列表
        log.info("开始刷新股票/指数列表...");
        List<String> stockCodes = universe.stockCodes();
        List<String> indexCodes = universe.indexCodes();
        log.info("获取到 {} 只股票, {} 只指数", stockCodes.size(), indexCodes.size());

        // 3. 生成任务 - 每个任务类型单独处理,边界清晰,每个方法独立获取IP
        int n = 0;
        n += seedStockDailyPages(source, date);
        n += seedRegionDaily(source, date);
        n += seedIndustryDaily(source, date);
        n += seedConceptDaily(source, date);
        n += seedMainFundStock(source, date);
        n += seedMainFundBoard(source, date);
        n += seedStrongPool(source, date);
        n += seedCixinPool(source, date);
        n += seedLimitPool(source, date);
        // 北向资金（东财 kamt 实时端点，每日一条；历史回填需 datacenter 报告，当前 API 变更暂不可回填）
        n += seedNorthbound(source, date);
        // 指数日线（push2 clist 全市场快照 43 只，市场级单任务）
        n += seedIndexDaily(source, date);
        // 逐板块：从 board_basic 表读去重 boardCode，每个板块一个任务（板块-个股关联初始化）
        n += seedByBoard(source, date);
        log.info("dailySeed date={} source={} inserted={}", date, source, n);
        return n;
    }

    /** STOCK_DAILY 单独处理:探测 total,按页拆 task */
    public int seedStockDailyPages(int source, String date) {
        return seedStockDailyPagesResult(source, date).inserted();
    }

    /**
     * STOCK_DAILY 按页拆任务,返回 SeedResult(含上游总数 total 作为校验真值)。
     * <p>幂等:任务已存在时 inserted=0,但 expectedTotal 仍为上游真实总数,
     * 编排器可依此校验已有任务的数据是否完整。</p>
     */
    public SeedResult seedStockDailyPagesResult(int source, String date) {
        // 探测真实全市场总数（最小请求：pz=1, fields=f12），计算页数
        EastmoneyEndpoints.EndpointSpec spec = EastmoneyEndpoints.get("STOCK_DAILY");
        int total = fetchClistTotalByProxy(spec, date);
        int totalPages;
        if (total <= 0) {
            total = stockDailyNowStockSize;
            totalPages = (total + stockDailyPageSize - 1) / stockDailyPageSize;
            log.warn("[seedStockDailyPages] date={} 探测无数据(total<=0),兜底 now-stock-size={}, pages={}",
                     date, total, totalPages);
        } else {
            totalPages = (total + stockDailyPageSize - 1) / stockDailyPageSize;
        }
        int maxPages = 100;
        if (totalPages > maxPages) {
            log.warn("[seedStockDailyPages] date={} total={} 计算页数={} 超过上限 {}, 截断为 {}",
                     date, total, totalPages, maxPages, maxPages);
            totalPages = maxPages;
        }
        List<CrawlTask> batch = new ArrayList<>(BATCH);
        int inserted = 0;
        for (int pn = 1; pn <= totalPages; pn++) {
            String params = TaskTypeCatalog.buildPageParams(date, pn);
            CrawlTask task = buildTask("STOCK_DAILY", source, date, null, null, params);
            task.setUniqueKey(TaskTypeCatalog.buildPageUniqueKey("STOCK_DAILY", source, date, pn));
            batch.add(task);
            if (batch.size() >= BATCH) {
                inserted += flush(batch);
                batch.clear();
            }
        }
        inserted += flush(batch);
        log.info("[seedStockDailyPages] date={} total={} pageSize={} pages={} inserted={}",
                date, total, stockDailyPageSize, totalPages, inserted);
        return new SeedResult(inserted, total, List.of(),
                "STOCK_DAILY 上游总数=" + total + ",页数=" + totalPages + ",新插入=" + inserted);
    }

    /** REGION_DAILY 单独处理（地域板块日线，board_type=1） */
    public int seedRegionDaily(int source, String date) {
        return seedClistTypeSingle("REGION_DAILY", source, date, null, 10);
    }

    public SeedResult seedRegionDailyResult(int source, String date) {
        return seedClistTypeSingleResult("REGION_DAILY", source, date, null, 10);
    }

    /** INDUSTRY_DAILY 单独处理（行业板块日线，board_type=2） */
    public int seedIndustryDaily(int source, String date) {
        return seedClistTypeSingle("INDUSTRY_DAILY", source, date, null, 10);
    }

    public SeedResult seedIndustryDailyResult(int source, String date) {
        return seedClistTypeSingleResult("INDUSTRY_DAILY", source, date, null, 10);
    }

    /** CONCEPT_DAILY 单独处理（概念板块日线，board_type=3） */
    public int seedConceptDaily(int source, String date) {
        return seedClistTypeSingle("CONCEPT_DAILY", source, date, null, 10);
    }

    public SeedResult seedConceptDailyResult(int source, String date) {
        return seedClistTypeSingleResult("CONCEPT_DAILY", source, date, null, 10);
    }

    // ========================================================================
    // 板块日线（board_daily）—— 地域/行业/概念，跟 stockDaily 对齐
    // ========================================================================

    /**
     * 一次性下发 3 种 board_daily 任务（地域/行业/概念），端到端测试用。
     */
    public int seedBoardDailyAll(int source, String date) {
        int n = 0;
        n += seedRegionDaily(source, date);
        n += seedIndustryDaily(source, date);
        n += seedConceptDaily(source, date);
        log.info("[seedBoardDailyAll] date={} source={} inserted={}", date, source, n);
        return n;
    }

    /** MAIN_FUND_STOCK 单独处理 */
    public int seedMainFundStock(int source, String date) {
        return seedClistTypeSingle("MAIN_FUND_STOCK", source, date, null);
    }

    public SeedResult seedMainFundStockResult(int source, String date) {
        return seedClistTypeSingleResult("MAIN_FUND_STOCK", source, date, null, 0);
    }

    /** MAIN_FUND_BOARD 单独处理 */
    public int seedMainFundBoard(int source, String date) {
        return seedClistTypeSingle("MAIN_FUND_BOARD", source, date, null);
    }

    public SeedResult seedMainFundBoardResult(int source, String date) {
        return seedClistTypeSingleResult("MAIN_FUND_BOARD", source, date, null, 0);
    }

    // ========================================================================
    // 板块基础维表（board_basic）—— 独立抓取，不依赖 board_daily 副作用
    // 模式与 STOCK_DAILY 一致：探测 total → 按页拆分（cap 10 页）→ params={tradeDate,pn}
    // ========================================================================

    /** REGION_BOARD 单独处理（地域板块基础维表，board_type=1） */
    public int seedRegionBoard(int source, String date) {
        return seedBoardBasic("REGION_BOARD", source, date);
    }

    /** INDUSTRY_BOARD 单独处理（行业板块基础维表，board_type=2） */
    public int seedIndustryBoard(int source, String date) {
        return seedBoardBasic("INDUSTRY_BOARD", source, date);
    }

    /** CONCEPT_BOARD 单独处理（概念板块基础维表，board_type=3） */
    public int seedConceptBoard(int source, String date) {
        return seedBoardBasic("CONCEPT_BOARD", source, date);
    }

    /**
     * 板块基础维表通用 seed：与 STOCK_DAILY 同模式。
     * <p>探测 total → ceil(total/100) → cap 10 页 → params={tradeDate,pn}。
     * params 不带 boardType（board_type 由 worker 按 taskType 映射）。</p>
     */
    private int seedBoardBasic(String taskType, int source, String date) {
        EastmoneyEndpoints.EndpointSpec spec = EastmoneyEndpoints.get(taskType);
        int total = fetchClistTotalByProxy(spec, date);
        if (total <= 0) {
            log.warn("[seedBoardBasic] taskType={} 探测无数据({})，跳过", taskType, total);
            return 0;
        }
        int pageSize = 100;
        int totalPages = (total + pageSize - 1) / pageSize;
        int maxPages = 10;                                  // 板块基础 cap 10 页
        if (totalPages > maxPages) {
            log.info("[seedBoardBasic] taskType={} total={} pages={} → cap {}", taskType, total, totalPages, maxPages);
            totalPages = maxPages;
        }
        int inserted = 0;
        for (int pn = 1; pn <= totalPages; pn++) {
            String params = TaskTypeCatalog.buildPageParams(date, pn);   // {"tradeDate":date,"pn":pn}
            CrawlTask task = buildTask(taskType, source, date, null, null, params);
            task.setUniqueKey(TaskTypeCatalog.buildPageUniqueKey(taskType, source, date, pn));
            inserted += mapper.insertIfAbsent(task);
        }
        log.info("[seedBoardBasic] taskType={} total={} pages={} inserted={}", taskType, total, totalPages, inserted);
        return inserted;
    }

    /**
     * 一次性下发 3 种 board_basic 任务（地域/行业/概念板块基础维表），端到端测试用。
     */
    public int seedBoardBasicAll(int source, String date) {
        int n = 0;
        n += seedRegionBoard(source, date);
        n += seedIndustryBoard(source, date);
        n += seedConceptBoard(source, date);
        log.info("[seedBoardBasicAll] date={} source={} inserted={}", date, source, n);
        return n;
    }

    /**
     * 仅下发 5 个池子任务（涨停/跌停/炸板/强势/次新），用于端到端测试。
     * <p>不包含 STOCK_DAILY / REGION_DAILY 等其他任务类型，避免 daily-seed 里一堆无关任务。</p>
     */
    public int dailySeedPoolsOnly(String date, int source) {
        int n = 0;
        n += seedStrongPool(source, date);
        n += seedCixinPool(source, date);
        n += seedLimitPool(source, date);   // 展开成 LIMIT_UP + LIMIT_DOWN + LIMIT_ZHABAN
        log.info("[dailySeedPoolsOnly] date={} source={} inserted={}", date, source, n);
        return n;
    }

    /** STRONG_POOL 单独处理 */
    public int seedStrongPool(int source, String date) {
        return seedPoolTasksSingle("STRONG_POOL", source, date);
    }

    public SeedResult seedStrongPoolResult(int source, String date) {
        return seedPoolTasksSingleResult("STRONG_POOL", source, date);
    }

    /** CIXIN_POOL 单独处理 */
    public int seedCixinPool(int source, String date) {
        return seedPoolTasksSingle("CIXIN_POOL", source, date);
    }

    public SeedResult seedCixinPoolResult(int source, String date) {
        return seedPoolTasksSingleResult("CIXIN_POOL", source, date);
    }

    /** LIMIT_POOL 单独处理(涨停/跌停/炸板) */
    public int seedLimitPool(int source, String date) {
        int n = 0;
        for (String limitType : LIMIT_SUBTYPES) {
            n += seedPoolTasksSingle(limitType, source, date);
        }
        return n;
    }

    /** LIMIT_POOL 池子种子,返回各子类型 SeedResult 列表。 */
    public List<SeedResult> seedLimitPoolResults(int source, String date) {
        List<SeedResult> results = new ArrayList<>();
        for (String limitType : LIMIT_SUBTYPES) {
            results.add(seedPoolTasksSingleResult(limitType, source, date));
        }
        return results;
    }

    /** 北向资金（NORTHBOUND_FLOW）单独处理：每日一条（kamt 实时端点，返回当日北向/沪股通/深股通净买入）。 */
    public int seedNorthbound(int source, String date) {
        CrawlTask task = buildTask("NORTHBOUND_FLOW", source, date, null, 1);
        int inserted = mapper.insertIfAbsent(task);
        log.info("[seedNorthbound] date={} source={} inserted={}", date, source, inserted);
        return inserted;
    }

    /** NORTHBOUND 种子,返回 SeedResult(含 kamt s2n 数组 size)。 */
    public SeedResult seedNorthboundResult(int source, String date) {
        int count = fetchNorthboundCount();
        CrawlTask task = buildTask("NORTHBOUND_FLOW", source, date, null, 1);
        int inserted = mapper.insertIfAbsent(task);
        log.info("[seedNorthbound] date={} source={} inserted={} count={}", date, source, inserted, count);
        return new SeedResult(inserted, Math.max(count, 0), List.of(), "NORTHBOUND s2n.size=" + count);
    }

    /** 探测北向分钟级数(kamt data.s2n 数组 size)。 */
    public int fetchNorthboundCount() {
        try {
            String url = "http://push2.eastmoney.com/api/qt/kamt.rtmin/get"
                    + "?fields1=f1,f2,f3,f4"
                    + "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61,f62,f63,f64,f65,f66,f67,f68,f69,f70,f71,f72,f73,f74,f75,f76,f77,f78,f79,f80"
                    + "&ut=b2884a393a59ad64002292a3e90d46a5";
            String resp = proxyManager.executeWithRetry(url);
            if (resp == null) {
                return -1;
            }
            JsonNode root = objectMapper.readTree(resp);
            JsonNode s2n = root.path("data").path("s2n");
            int size = s2n.isArray() ? s2n.size() : -1;
            log.info("[fetchNorthboundCount] s2n.size={}", size);
            return size;
        } catch (Exception e) {
            log.warn("[fetchNorthboundCount] 失败: {}", e.getMessage());
            return -1;
        }
    }

    /** 指数日线（INDEX_DAILY）单独处理：市场级单任务（push2 clist fs=b:MK0010, 43 只一次拿完）。 */
    public int seedIndexDaily(int source, String date) {
        CrawlTask task = buildTask("INDEX_DAILY", source, date, null, null);
        int inserted = mapper.insertIfAbsent(task);
        log.info("[seedIndexDaily] date={} source={} inserted={}", date, source, inserted);
        return inserted;
    }

    /** INDEX_DAILY 种子,返回 SeedResult(含 clist total)。 */
    public SeedResult seedIndexDailyResult(int source, String date) {
        EastmoneyEndpoints.EndpointSpec spec = EastmoneyEndpoints.get("INDEX_DAILY");
        int total = fetchClistTotalByProxy(spec, date);
        CrawlTask task = buildTask("INDEX_DAILY", source, date, null, null);
        int inserted = mapper.insertIfAbsent(task);
        log.info("[seedIndexDaily] date={} source={} inserted={} total={}", date, source, inserted, total);
        return new SeedResult(inserted, Math.max(total, 0), List.of(), "INDEX_DAILY clist total=" + total);
    }

    /** 分时分钟线:从配置表取 type='minute' 的启用股票,逐个生成 STOCK_KLINE_MINUTE 任务。 */
    public int seedStockKlineMinute(int source, String date) {
        List<StockTaskConfig> configs = taskConfigMapper.selectByTypeAndStatus("minute", 1);
        if (configs == null || configs.isEmpty()) {
            log.info("[seedStockKlineMinute] 配置表无 minute 类型股票,跳过");
            return 0;
        }
        int inserted = 0;
        List<CrawlTask> batch = new ArrayList<>(100);
        for (StockTaskConfig cfg : configs) {
            String params = "{\"tsCode\":\"" + cfg.getCode() + "\",\"tradeDate\":\"" + date + "\"}";
            CrawlTask task = buildTask("STOCK_KLINE_MINUTE", source, date, cfg.getCode(), 1, params);
            task.setUniqueKey("STOCK_KLINE_MINUTE|" + source + "|" + cfg.getCode() + "|" + date);
            batch.add(task);
            if (batch.size() >= 100) {
                inserted += flush(batch);
                batch.clear();
            }
        }
        inserted += flush(batch);
        log.info("[seedStockKlineMinute] date={} source={} stocks={} inserted={}", date, source, configs.size(), inserted);
        return inserted;
    }

    /** 从5个池子初始化配置表(type=minute):取所有池子的 distinct 股票,幂等插入配置表。 */
    public int initTaskConfigFromPools() {
        List<List<Map<String, Object>>> allPoolRows = new ArrayList<>();
        allPoolRows.add(limitUpPoolMapper.selectDistinctTsCodeAndName());
        allPoolRows.add(limitDownPoolMapper.selectDistinctTsCodeAndName());
        allPoolRows.add(zhabanPoolMapper.selectDistinctTsCodeAndName());
        allPoolRows.add(strongPoolMapper.selectDistinctTsCodeAndName());
        allPoolRows.add(cixinPoolMapper.selectDistinctTsCodeAndName());
        Map<String, String> codeToName = new LinkedHashMap<>();
        for (List<Map<String, Object>> rows : allPoolRows) {
            if (rows == null) continue;
            for (Map<String, Object> r : rows) {
                Object codeObj = r.get("ts_code");
                Object nameObj = r.get("stock_name");
                if (codeObj == null || nameObj == null) continue;
                String code = codeObj.toString();
                if (!codeToName.containsKey(code)) {
                    codeToName.put(code, nameObj.toString());
                }
            }
        }
        if (codeToName.isEmpty()) {
            log.info("[initTaskConfigFromPools] 5个池子均无数据,跳过");
            return 0;
        }
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        List<StockTaskConfig> batch = new ArrayList<>(100);
        int inserted = 0;
        for (Map.Entry<String, String> e : codeToName.entrySet()) {
            StockTaskConfig cfg = new StockTaskConfig();
            cfg.setType("minute");
            cfg.setCode(e.getKey());
            cfg.setStockName(e.getValue());
            cfg.setStatus(1);
            cfg.setCreateDate(today);
            cfg.setUpdateDate(now);
            batch.add(cfg);
            if (batch.size() >= 100) {
                inserted += taskConfigMapper.batchInsertIfAbsent(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            inserted += taskConfigMapper.batchInsertIfAbsent(batch);
        }
        log.info("[initTaskConfigFromPools] pools=5, distinctStocks={}, inserted={}", codeToName.size(), inserted);
        return inserted;
    }

    /** 龙虎榜主表（DRAGON_TIGER，市场级，每日一条）—— 经东财 datacenter 新版接口抓取，worker 认领后落 dragon_tiger 表。 */
    public int seedDragonTiger(int source, String date) {
        CrawlTask task = buildTask("DRAGON_TIGER", source, date, null, null);
        int inserted = mapper.insertIfAbsent(task);
        log.info("[seedDragonTiger] date={} source={} inserted={}", date, source, inserted);
        return inserted;
    }

    /** DRAGON_TIGER 种子,返回 SeedResult(含上游 result.count)。 */
    public SeedResult seedDragonTigerResult(int source, String date) {
        int count = fetchDragonTigerCount(date);
        CrawlTask task = buildTask("DRAGON_TIGER", source, date, null, null);
        int inserted = mapper.insertIfAbsent(task);
        log.info("[seedDragonTiger] date={} source={} inserted={} count={}", date, source, inserted, count);
        return new SeedResult(inserted, Math.max(count, 0), List.of(), "DRAGON_TIGER result.count=" + count);
    }

    /** 探测龙虎榜家数(datacenter result.count,pageSize=1 即得真值)。 */
    public int fetchDragonTigerCount(String date) {
        try {
            String dash = date; // date 已是 yyyy-MM-dd
            String filter = "(TRADE_DATE<='" + dash + "')(TRADE_DATE>='" + dash + "')";
            String url = "https://datacenter-web.eastmoney.com/api/data/v1/get"
                    + "?reportName=RPT_DAILYBILLBOARD_DETAILSNEW"
                    + "&columns=SECURITY_CODE"
                    + "&sortColumns=SECURITY_CODE,TRADE_DATE&sortTypes=1,-1"
                    + "&pageSize=1&pageNumber=1&source=WEB&client=WEB"
                    + "&filter=" + URLEncoder.encode(filter, "UTF-8");
            String resp = proxyManager.executeWithRetry(url);
            if (resp == null) {
                return -1;
            }
            JsonNode root = objectMapper.readTree(resp);
            int count = root.path("result").path("count").asInt(-1);
            log.info("[fetchDragonTigerCount] date={} count={}", date, count);
            return count;
        } catch (Exception e) {
            log.warn("[fetchDragonTigerCount] date={} 失败: {}", date, e.getMessage());
            return -1;
        }
    }

    /**
     * 个股周线（STOCK_WEEKLY）独立种子：每个股票一个任务,一次拿满历史(lmt=50000,约 80 年)。
     * <p>幂等:uniqueKey=STOCK_WEEKLY|source|tsCode,insertIfAbsent 保证重复调用不产生重复任务。</p>
     *
     * @param source 数据源(1=东财)
     * @param tsCode 指定股票(如 "300059.SZ"),为 null 则全量
     */
    public int seedStockWeekly(int source, String tsCode) {
        List<String> codes;
        if (tsCode != null && !tsCode.isBlank()) {
            codes = List.of(tsCode.trim());
            log.info("[seedStockWeekly] 指定单只股票 {}, source={}", tsCode, source);
        } else {
            codes = stockDailyMapper.selectDistinctTsCode();
            if (codes == null || codes.isEmpty()) {
                log.warn("[seedStockWeekly] stock_daily 无股票数据,跳过");
                return 0;
            }
        }
        int inserted = 0;
        for (String code : codes) {
            if (code == null || code.isBlank()) continue;
            String params = "{\"tsCode\":\"" + code + "\"}";  // 不带 tradeDate,一次拿满
            CrawlTask task = buildTask("STOCK_WEEKLY", source, null, code, null, params);
            task.setUniqueKey("STOCK_WEEKLY|" + source + "|" + code);
            task.setPriority(5);
            task.setMaxRetry(3);
            inserted += mapper.insertIfAbsent(task);
        }
        log.info("[seedStockWeekly] 下发 {} 个周K任务(股票池 {} 只)", inserted, codes.size());
        return inserted;
    }

    /**
     * 聚合指定股票或全量的周K(从日K表聚合,补全 stock_weekly 的扩展字段)。
     *
     * @param tsCode 股票代码(如 "300059.SZ"),为 null 则全量
     */
    public int aggregateWeeklyForStock(String tsCode) {
        if (tsCode != null && !tsCode.isBlank()) {
            return stockWeeklyAggregator.aggregateWeeklyForStock(tsCode.trim());
        }
        return aggregateAllWeekly();
    }

    /**
     * 聚合所有股票的周K(从日K表聚合,补全 stock_weekly 的扩展字段)。
     * <p>触发时机:周K端点任务跑完后调用(手动或定时)。</p>
     */
    public int aggregateAllWeekly() {
        List<String> codes = stockDailyMapper.selectDistinctTsCode();
        if (codes == null || codes.isEmpty()) {
            log.warn("[aggregateAllWeekly] stock_daily 无股票数据,跳过");
            return 0;
        }
        int totalWeeks = 0;
        for (String code : codes) {
            try {
                totalWeeks += stockWeeklyAggregator.aggregateWeeklyForStock(code);
            } catch (Exception e) {
                log.error("[aggregateAllWeekly] 聚合失败(tsCode={}): {}", code, e.getMessage());
            }
        }
        log.info("[aggregateAllWeekly] 聚合完成,共 {} 只股票, {} 周", codes.size(), totalWeeks);
        return totalWeeks;
    }

    /** 逐板块种子：读 board_basic 表去重 boardCode，每个板块先请求 total 再按页拆任务。 */
    public int seedByBoard(int source, String date) {
        List<BoardBasic> boards = boardBasicMapper.selectList(null);
        // 按 boardCode 去重（保留首位）
        Set<String> seen = new LinkedHashSet<>();
        List<BoardBasic> deduped = new ArrayList<>();
        for (BoardBasic b : boards) {
            if (b.getBoardCode() != null && seen.add(b.getBoardCode())) {
                deduped.add(b);
            }
        }
        int total = 0;
        for (BoardBasic b : deduped) {
            // 先请求一次（pz=1）拿 total，计算页数
            int totalCount = fetchBoardStockTotal(b.getBoardCode(), date);
            int totalPages = (totalCount <= 0) ? 1 : ((totalCount + BOARD_BY_BOARD_PAGE_SIZE - 1) / BOARD_BY_BOARD_PAGE_SIZE);
            // 按页拆任务：每页一个 task（pn 从 1 开始）
            for (int pn = 1; pn <= totalPages; pn++) {
                String params = TaskTypeCatalog.buildParams(
                        "STOCK_BY_BOARD", date, null, null,
                        b.getBoardCode(), b.getBoardName(), b.getBoardType());
                // 追加 pn 到 params（buildParams 没带 pn，这里拼进去）
                String paramsWithPn = appendPnToParams(params, pn);
                CrawlTask task = buildTask("STOCK_BY_BOARD", source, date, null, null, paramsWithPn);
                task.setUniqueKey(TaskTypeCatalog.buildPageUniqueKey("STOCK_BY_BOARD", source, date, pn) + "|" + b.getBoardCode());
                total += mapper.insertIfAbsent(task);
            }
        }
        log.info("seedByBoard date={} boards={} inserted={}", date, deduped.size(), total);
        return total;
    }

    /**
     * 只跑单个板块的 STOCK_BY_BOARD（端到端测试用）。
     * <p>从 board_basic 表读指定 boardCode 的信息，探测 total → 按页拆任务。</p>
     *
     * @param boardCode 板块代号（如 BK0450）
     * @param source    数据源
     * @param date      交易日
     * @return 插入的 task 数
     */
    public int syncBoardRelations(int source, String date) {
        // 增量同步板块-个股关联（stock_board_rel）：从 board_basic 读板块，探测 total → 按页拆 STOCK_BY_BOARD 任务。
        // worker 幂等，重复跑不重复数据。
        return seedByBoard(source, date);
    }

    /**
     * 只跑单个板块的 STOCK_BY_BOARD（端到端测试用）。
     * <p>从 board_basic 表读指定 boardCode 的信息，探测 total → 按页拆任务。</p>
     *
     * @param boardCode 板块代号（如 BK0450）
     * @param source    数据源
     * @param date      交易日
     * @return 插入的 task 数
     */
    public int seedSingleBoard(String boardCode, int source, String date) {
        // 从 board_basic 表查该板块信息（原生 SQL，去 MP QueryWrapper）
        BoardBasic bb = boardBasicMapper.selectOneByBoardCode(boardCode);
        if (bb == null) {
            log.warn("seedSingleBoard: board_basic 无 {}, 跳过", boardCode);
            return 0;
        }
        String boardName = bb.getBoardName();
        int boardType = bb.getBoardType();
        // 探测 total
        int totalCount = fetchBoardStockTotal(boardCode, date);
        int totalPages = (totalCount <= 0) ? 1 : ((totalCount + BOARD_BY_BOARD_PAGE_SIZE - 1) / BOARD_BY_BOARD_PAGE_SIZE);
        int inserted = 0;
        for (int pn = 1; pn <= totalPages; pn++) {
            String params = TaskTypeCatalog.buildParams(
                    "STOCK_BY_BOARD", date, null, null,
                    bb.getBoardCode(), bb.getBoardName(), bb.getBoardType());
            String paramsWithPn = appendPnToParams(params, pn);
            CrawlTask task = buildTask("STOCK_BY_BOARD", source, date, null, null, paramsWithPn);
            task.setUniqueKey(TaskTypeCatalog.buildPageUniqueKey("STOCK_BY_BOARD", source, date, pn) + "|" + bb.getBoardCode());
            inserted += mapper.insertIfAbsent(task);
        }
        log.info("[seedSingleBoard] boardCode={} boardName={} total={} pages={} inserted={}",
                bb.getBoardCode(), bb.getBoardName(), totalCount, totalPages, inserted);
        return inserted;
    }

    /** 请求某板块下股票总数（pz=1，只拿 total）。 */
    private int fetchBoardStockTotal(String boardCode, String date) {
        String fs = "b:" + boardCode.toLowerCase() + "+f:!50";
        // HTTP（非 HTTPS）：避免 OkHttp 代理 CONNECT 隧道阶段 Proxy-Authorization 加不上导致 407
        String url = "http://push2.eastmoney.com/weblogin/api/qt/clist/get"
                + "?pn=1&pz=1&po=1&np=1&fltt=1&invt=2&fid=f3"
                + "&fs=" + fs + "&fields=f12";
        try {
            String proxy = proxyManager.acquireProxy();
            String resp = eastmoneyClient.get(url, randomUa(), proxy);
            String cleaned = cleanJsonp(resp);
            JsonNode root = objectMapper.readTree(cleaned);
            int total = root.path("data").path("total").asInt(0);
            log.info("[fetchBoardStockTotal] boardCode={}, total={}, proxy={}", boardCode, total, proxy);
            return total;
        } catch (Exception e) {
            log.error("[fetchBoardStockTotal] 失败(boardCode={}, date={}): {}", boardCode, date, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 探测池子（涨停/跌停/炸板/强势/次新）当日总数 tc。
     * <p>先请求一次（pagesize=1，最小化数据传输），解析 data.tc。返回 -1 表示探测失败。</p>
     */
    private int fetchPoolTotal(String limitType, String date) {
        try {
            Map<String, Object> probeParams = new java.util.HashMap<>();
            probeParams.put("tradeDate", date);
            if (limitType != null) {
                probeParams.put("limitType", limitType);
            }
            probeParams.put("Pageindex", 0);
            probeParams.put("pagesize", 1);
            String taskType = taskTypeForLimitType(limitType);
            EastmoneyEndpoints.EndpointSpec spec = EastmoneyEndpoints.get(taskType);
            String url = spec.buildUrl(probeParams, 0);
            String proxy = proxyManager.acquireProxy();
            String resp = eastmoneyClient.get(url, randomUa(), proxy);
            String cleaned = cleanJsonp(resp);
            JsonNode root = objectMapper.readTree(cleaned);
            int tc = root.path("data").path("tc").asInt(-1);
            log.info("[fetchPoolTotal] limitType={}, tc={}, proxy={}", limitType, tc, proxy);
            return tc;
        } catch (Exception e) {
            log.error("[fetchPoolTotal] 失败(limitType={}, date={}): {}", limitType, date, e.getMessage(), e);
            return -1;
        }
    }

    /** limitType → 池子 taskType（用于查 EndpointSpec）。 */
    private String taskTypeForLimitType(String limitType) {
        if (limitType == null) return "LIMIT_POOL";
        switch (limitType) {
            case "LIMIT_UP": return "LIMIT_UP";
            case "LIMIT_DOWN": return "LIMIT_DOWN";
            case "LIMIT_ZHABAN": return "LIMIT_ZHABAN";
            case "STRONG_POOL": return "STRONG_POOL";
            case "CIXIN_POOL": return "CIXIN_POOL";
            default: return limitType;
        }
    }

    /**
     * 池子 params JSON：含 tradeDate / limitType / Pageindex / pagesize。
     */
    private String buildPoolParams(String date, String limitType, int pageindex, int pagesize) {
        if (limitType != null) {
            return "{\"tradeDate\":\"" + date + "\",\"limitType\":\"" + limitType
                    + "\",\"Pageindex\":" + pageindex + ",\"pagesize\":" + pagesize + "}";
        }
        return "{\"tradeDate\":\"" + date + "\",\"Pageindex\":" + pageindex + ",\"pagesize\":" + pagesize + "}";
    }

    /**
     * 池子任务下发：先探测 total，再按 ceil(tc/100) 拆任务。
     * @return 插入的任务数
     */
    private int seedPoolTasks(String limitType, int source, String date) {
        int tc = fetchPoolTotal(limitType, date);
        int numTasks;
        if (tc <= 0) {
            // 探测失败或无数据：仍下发 1 个 task（兜底，避免漏跑）
            numTasks = 1;
            log.info("[seedPoolTasks] limitType={} 探测无数据(tc={})，下发 1 个兜底 task", limitType, tc);
        } else {
            numTasks = (tc + 99) / 100; // ceil(tc/100)
        }
        String taskType = taskTypeForLimitType(limitType);
        int inserted = 0;
        for (int i = 0; i < numTasks; i++) {
            String params = buildPoolParams(date, limitType, i, 100);
            CrawlTask task = buildTask(taskType, source, date, null, null, params);
            task.setUniqueKey(TaskTypeCatalog.buildPageUniqueKey(taskType, source, date, i));
            inserted += mapper.insertIfAbsent(task);
        }
        log.info("[seedPoolTasks] limitType={} tc={} numTasks={} inserted={}", limitType, tc, numTasks, inserted);
        return inserted;
    }

    /**
     * 探测 CLIST 类型（push2 clist）当日总数。
     * <p>用完整 push2 模板(只改 pz=1),避免被识别为爬虫。解析 data.total。</p>
     */
    private int fetchClistTotal(EastmoneyEndpoints.EndpointSpec spec, String date) {
        // 完整参数模板(用户实测 2026-08-04),只改 pz=1 做最小探测
        String cb = EastmoneyEndpoints.generateCb();
        String fsVal = spec.getFs() != null ? spec.getFs() : "";
        String url = "http://83.push2.eastmoney.com/api/qt/clist/get"
                + "?cb=" + cb
                + "&pn=1&pz=1&po=1&np=1"
                + "&ut=bd1d9ddb04089700cf9c27f6f7426281"
                + "&fltt=2&invt=2"
                + "&fid=f3"
                + "&fs=" + fsVal
                + "&fields=f12";
        try {
            String proxy = proxyManager.acquireProxy();
            String resp = eastmoneyClient.get(url, randomUa(), proxy);
            String cleaned = cleanJsonp(resp);
            JsonNode root = objectMapper.readTree(cleaned);
            int total = root.path("data").path("total").asInt(-1);
            log.info("[fetchClistTotal] taskType={} total={}, proxy={}", spec.getTaskType(), total, proxy);
            return total;
        } catch (Exception e) {
            log.warn("[fetchClistTotal] taskType={} 失败: {}", spec.getTaskType(), e.getMessage());
            return -1;
        }
    }

    /**
     * CLIST 类型按页拆任务：探测 data.total 后按 ceil(total/100) 拆，每任务 pn=1..N。
     * @param boardType REGION/INDUSTRY/CONCEPT_DAILY 需要，其他传 null
     */
    private int seedClistType(String taskType, int source, String date, Integer boardType) {
        EastmoneyEndpoints.EndpointSpec spec = EastmoneyEndpoints.get(taskType);
        int total = fetchClistTotal(spec, date);
        if (total <= 0) {
            log.warn("[seedClistType] taskType={} 探测无数据({})，跳过", taskType, total);
            return 0;
        }
        int pageSize = 100;
        int totalPages = (total + pageSize - 1) / pageSize;
        int inserted = 0;
        for (int pn = 1; pn <= totalPages; pn++) {
            String params;
            if (boardType != null) {
                params = TaskTypeCatalog.buildParams(taskType, date, boardType);
            } else {
                params = TaskTypeCatalog.buildParams(taskType, date, (String) null);
            }
            params = appendPnToParams(params, pn);
            CrawlTask task = buildTask(taskType, source, date, null, null, params);
            task.setUniqueKey(TaskTypeCatalog.buildPageUniqueKey(taskType, source, date, pn));
            inserted += mapper.insertIfAbsent(task);
        }
        log.info("[seedClistType] taskType={} total={} pages={} inserted={}", taskType, total, totalPages, inserted);
        return inserted;
    }

    /** 用ProxyManager获取IP并探测total(每个任务类型独立获取IP,带重试) */
    private int fetchClistTotalByProxy(EastmoneyEndpoints.EndpointSpec spec, String date) {
        String url = buildClistTotalUrl(spec, date);
        try {
            String resp = proxyManager.executeWithRetry(url);
            if (resp == null) {
                return -1;
            }
            String cleaned = cleanJsonp(resp);
            JsonNode root = objectMapper.readTree(cleaned);
            int total = root.path("data").path("total").asInt(-1);
            log.info("[fetchClistTotalByProxy] taskType={} total={}", spec.getTaskType(), total);
            return total;
        } catch (Exception e) {
            log.warn("[fetchClistTotalByProxy] taskType={} 失败: {}", spec.getTaskType(), e.getMessage());
            return -1;
        }
    }

    /** 构建探测total的URL */
    private String buildClistTotalUrl(EastmoneyEndpoints.EndpointSpec spec, String date) {
        String cb = EastmoneyEndpoints.generateCb();
        String fsVal = spec.getFs() != null ? spec.getFs() : "";
        return "http://83.push2.eastmoney.com/api/qt/clist/get"
                + "?cb=" + cb
                + "&pn=1&pz=1&po=1&np=1"
                + "&ut=bd1d9ddb04089700cf9c27f6f7426281"
                + "&fltt=2&invt=2"
                + "&fid=f3"
                + "&fs=" + fsVal
                + "&fields=f12";
    }

    /** 单个CLIST任务类型处理(独立获取IP) */
    /**
     * CLIST 类型按页拆任务（通用）：探测 data.total → 按页拆分。
     * <p>与 STOCK_DAILY 同模式：params={tradeDate,pn}（不带 boardType，board_type 由 worker 从 taskType 映射）。
     * 页数上限 maxPages（STOCK_DAILY 传 100，board_daily 传 10）。</p>
     *
     * @param taskType  任务类型
     * @param source    数据源
     * @param date      交易日
     * @param boardType 兼容旧调用传 null（不再写入 params）
     * @param maxPages  页数上限（0 或负数表示不限）
     */
    private int seedClistTypeSingle(String taskType, int source, String date, Integer boardType, int maxPages) {
        return seedClistTypeSingleResult(taskType, source, date, boardType, maxPages).inserted();
    }

    /** clist 类种子,返回 SeedResult(含上游总数 total)。 */
    private SeedResult seedClistTypeSingleResult(String taskType, int source, String date, Integer boardType, int maxPages) {
        EastmoneyEndpoints.EndpointSpec spec = EastmoneyEndpoints.get(taskType);
        int total = fetchClistTotalByProxy(spec, date);
        if (total <= 0) {
            log.warn("[seedClistTypeSingle] taskType={} 探测无数据({})，跳过", taskType, total);
            return new SeedResult(0, 0, List.of(), "无数据");
        }
        int pageSize = 100;
        int totalPages = (total + pageSize - 1) / pageSize;
        if (maxPages > 0 && totalPages > maxPages) {
            log.info("[seedClistTypeSingle] taskType={} total={} pages={} → cap {}", taskType, total, totalPages, maxPages);
            totalPages = maxPages;
        }
        int inserted = 0;
        for (int pn = 1; pn <= totalPages; pn++) {
            String params = TaskTypeCatalog.buildPageParams(date, pn);
            CrawlTask task = buildTask(taskType, source, date, null, null, params);
            task.setUniqueKey(TaskTypeCatalog.buildPageUniqueKey(taskType, source, date, pn));
            inserted += mapper.insertIfAbsent(task);
        }
        log.info("[seedClistTypeSingle] taskType={} total={} pages={} inserted={}", taskType, total, totalPages, inserted);
        return new SeedResult(inserted, total, List.of(), taskType + " 上游总数=" + total);
    }

    /** 兼容旧调用（无 cap）。 */
    private int seedClistTypeSingle(String taskType, int source, String date, Integer boardType) {
        return seedClistTypeSingle(taskType, source, date, boardType, 0);
    }

    /** 单个池子任务处理(独立获取IP) */
    private int seedPoolTasksSingle(String limitType, int source, String date) {
        return seedPoolTasksSingleResult(limitType, source, date).inserted();
    }

    /** 池子种子,返回 SeedResult(含上游总数 tc)。 */
    private SeedResult seedPoolTasksSingleResult(String limitType, int source, String date) {
        int tc = fetchPoolTotalByProxy(limitType, date);
        int numTasks;
        if (tc <= 0) {
            numTasks = 1;
            log.info("[seedPoolTasksSingle] limitType={} 探测无数据(tc={})，下发 1 个兜底 task", limitType, tc);
        } else {
            numTasks = (tc + 99) / 100;
        }
        String taskType = taskTypeForLimitType(limitType);
        int inserted = 0;
        for (int i = 0; i < numTasks; i++) {
            String params = buildPoolParams(date, limitType, i, 100);
            CrawlTask task = buildTask(taskType, source, date, null, null, params);
            task.setUniqueKey(TaskTypeCatalog.buildPageUniqueKey(taskType, source, date, i));
            inserted += mapper.insertIfAbsent(task);
        }
        log.info("[seedPoolTasksSingle] limitType={} tc={} numTasks={} inserted={}", limitType, tc, numTasks, inserted);
        return new SeedResult(inserted, Math.max(tc, 0), List.of(), limitType + " 上游tc=" + tc);
    }

    /** 用ProxyManager获取IP并探测池子total(每个任务类型独立获取IP,带重试) */
    private int fetchPoolTotalByProxy(String limitType, String date) {
        try {
            Map<String, Object> probeParams = new HashMap<>();
            probeParams.put("tradeDate", date);
            if (limitType != null) {
                probeParams.put("limitType", limitType);
            }
            probeParams.put("Pageindex", 0);
            probeParams.put("pagesize", 1);
            String taskType = taskTypeForLimitType(limitType);
            EastmoneyEndpoints.EndpointSpec spec = EastmoneyEndpoints.get(taskType);
            String url = spec.buildUrl(probeParams, 0);
            // 池子响应用 data.tc（非 data.total），故用 POOL_VALIDATOR 识别"502 但数据有效"
            String resp = proxyManager.executeWithRetry(url, ProxyManager.POOL_VALIDATOR);
            if (resp == null) {
                return -1;
            }
            String cleaned = cleanJsonp(resp);
            JsonNode root = objectMapper.readTree(cleaned);
            int tc = root.path("data").path("tc").asInt(-1);
            log.info("[fetchPoolTotalByProxy] limitType={}, tc={}", limitType, tc);
            return tc;
        } catch (Exception e) {
            log.error("[fetchPoolTotalByProxy] 失败(limitType={}, date={}): {}", limitType, date, e.getMessage(), e);
            return -1;
        }
    }

    /** 把 pn 拼到 params JSON 末尾（简单字符串拼接，避免引入 JSON 库）。 */
    private String appendPnToParams(String params, int pn) {
        if (params == null || params.isEmpty()) {
            return "{\"pn\":" + pn + "}";
        }
        // params 形如 {"boardCode":"BK0450",...}，在末尾 } 前插入 ,"pn":N
        return params.substring(0, params.length() - 1) + ",\"pn\":" + pn + "}";
    }

    /** 清洗 JSONP 包裹：剥掉 callback(...); 前缀后缀。 */
    private String cleanJsonp(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        int lparen = s.indexOf('(');
        int rparen = s.lastIndexOf(')');
        if (lparen > 0 && rparen > lparen && s.endsWith(");")) {
            return s.substring(lparen + 1, rparen);
        }
        return s;
    }

    private String randomUa() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
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

    /**
     * 个股日K历史回填（断点续传）。
     * <p>从 stock_daily 取全量股票（distinct ts_code），每只股票一个任务：
     * push2his kline, lmt=50000（约 80 年，覆盖 A 股全历史），一次拿满。</p>
     * <p>断点续传：{@code crawl_stock_backfill_status} 记录每只股票进度，
     * 已是 SUCCESS 的股票自动跳过；重启后从断点继续。</p>
     *
     * @param source 数据源（1=东财）
     * @param lmt    拉取天数上限（默认 50000，约 80 年）
     * @param tsCode 指定股票代码（如 "300976"），为空则跑全量
     * @return 新插入的任务数
     */
    public int backfillDailyHistory(int source, int lmt, String tsCode) {
        // 1. 全量股票：从 stock_daily 取 distinct ts_code
        List<String> allCodes = stockDailyMapper.selectDistinctTsCode();
        if (allCodes == null || allCodes.isEmpty()) {
            log.warn("[backfillDailyHistory] stock_daily 无股票数据，跳过");
            return 0;
        }

        // 2. 如果指定了 tsCode，只跑这一只
        List<String> pendingCodes;
        if (tsCode != null && !tsCode.isBlank()) {
            String code = tsCode.trim();
            if (!allCodes.contains(code)) {
                log.warn("[backfillDailyHistory] stock_daily 中无该股票: {}", code);
                return 0;
            }
            pendingCodes = List.of(code);
            log.info("[backfillDailyHistory] 指定单只股票 {}, lmt={}", code, lmt);
        } else {
            // 全量：排除已是 SUCCESS 的股票（断点续传）
            List<String> successCodes = backfillStatusMapper.selectSuccessCodes();
            java.util.Set<String> successSet = successCodes == null ? java.util.Set.of() : new java.util.HashSet<>(successCodes);
            log.info("[backfillDailyHistory] 全量股票 {} 只，已 SUCCESS {} 只", allCodes.size(), successSet.size());

            pendingCodes = new ArrayList<>();
            for (String c : allCodes) {
                if (successSet.contains(c)) {
                    continue;
                }
                pendingCodes.add(c);
            }
            log.info("[backfillDailyHistory] 需回填 {} 只（跳过 {} 只已 SUCCESS）",
                    pendingCodes.size(), allCodes.size() - pendingCodes.size());
        }
        if (pendingCodes.isEmpty()) {
            return 0;
        }

        // 3. 初始化进度表（幂等，已存在则忽略）+ 下发任务
        int inserted = 0;
        List<CrawlTask> batch = new ArrayList<>(BATCH);
        List<String> statusInitBatch = new ArrayList<>(BATCH);
        for (String code : pendingCodes) {
            statusInitBatch.add(code);
            int isHs = isHsByCode(code); // 按代码前缀判断 market
            String params = buildDailyHistoryParams(code, isHs, lmt);
            CrawlTask task = buildTask("STOCK_DAILY_HISTORY", source, "", code, 1, params);
            task.setUniqueKey("STOCK_DAILY_HISTORY|" + source + "|" + code);
            batch.add(task);
            if (batch.size() >= BATCH) {
                backfillStatusMapper.batchInsertIfAbsent(statusInitBatch); // 进度表幂等初始化
                statusInitBatch.clear();
                inserted += flush(batch);
                batch.clear();
            }
        }
        if (!statusInitBatch.isEmpty()) {
            backfillStatusMapper.batchInsertIfAbsent(statusInitBatch);
        }
        inserted += flush(batch);
        log.info("[backfillDailyHistory] 下发 {} 个日K历史回填任务", inserted);
        return inserted;
    }

    /** 按股票代码前缀判断是否沪市：1=沪市 0=深市/北交所（market 项目逻辑）。 */
    private int isHsByCode(String tsCode) {
        String code = tsCode.contains(".") ? tsCode.substring(0, tsCode.indexOf('.')) : tsCode;
        if (code.startsWith("6") || code.startsWith("11")) {
            return 1; // 沪市（60xxxx 主板 / 11xxxx 科创板）
        }
        return 0; // 深市/北交所（00xxxx/30xxxx/12xxxx/13xxxx/8xxxx/4xxxx）
    }

    /** 个股日K历史回填 params：{"tsCode":"300976","isHs":0,"lmt":50000} */
    private String buildDailyHistoryParams(String tsCode, int isHs, int lmt) {
        return "{\"tsCode\":\"" + tsCode + "\",\"isHs\":" + isHs + ",\"lmt\":" + lmt + "}";
    }

    private int backfillMarketWide(String date, int source, List<String> typeFilter) {
        List<CrawlTask> batch = new ArrayList<>();
        int inserted = 0; // 池子任务即时入库的计数
        for (TaskTypeCatalog.TaskSpec spec : TaskTypeCatalog.marketWideTypes()) {
            if (typeFilter != null && !typeFilter.contains(spec.taskType())) {
                continue;
            }
            if ("LIMIT_POOL".equals(spec.taskType())) {
                // 涨停/跌停/炸板：先探测 total，按 ceil(tc/100) 拆任务（即时入库）
                for (String limitType : LIMIT_SUBTYPES) {
                    inserted += seedPoolTasks(limitType, source, date);
                }
            } else if ("STOCK_DAILY".equals(spec.taskType())) {
                // 历史回填也按页拆（探测 total 后拆分，即时入库）
                inserted += seedStockDailyPages(source, date);
            } else if ("STRONG_POOL".equals(spec.taskType()) || "CIXIN_POOL".equals(spec.taskType())) {
                // 强势/次新：先探测 total，按 ceil(tc/100) 拆任务（即时入库）
                inserted += seedPoolTasks(spec.taskType(), source, date);
            } else if ("REGION_DAILY".equals(spec.taskType())) {
                inserted += seedClistType(spec.taskType(), source, date, 1);
            } else if ("INDUSTRY_DAILY".equals(spec.taskType())) {
                inserted += seedClistType(spec.taskType(), source, date, 2);
            } else if ("CONCEPT_DAILY".equals(spec.taskType())) {
                inserted += seedClistType(spec.taskType(), source, date, 3);
            } else {
                // board_daily / MAIN_FUND_STOCK / MAIN_FUND_BOARD 等 CLIST 类型
                inserted += seedClistType(spec.taskType(), source, date, null);
            }
        }
        return inserted + flush(batch);
    }

    private int backfillPerInstrument(String date, int source, List<String> typeFilter) {
        int total = 0;
        for (TaskTypeCatalog.TaskSpec spec : TaskTypeCatalog.perInstrumentTypes()) {
            if (typeFilter != null && !typeFilter.contains(spec.taskType())) {
                continue;
            }
            List<String> codes = universe.stockCodes();
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
    public int seedThsPlate(int source, String date) {
        // 同花顺板块基础维表（THS_PLATE）：每日 3 个任务（地域/行业/概念，plate_type=4/5/6）。
        // 浏览器策略，单任务串行跑完所有板块（375 个概念单线程约 15-25 分钟）。
        int inserted = 0;
        int[] plateTypes = {4, 5, 6};
        for (int plateType : plateTypes) {
            String params = "{\"plate_type\":" + plateType + ",\"tradeDate\":\"" + date + "\"}";
            CrawlTask task = buildTask("THS_PLATE", source, date, null, null, params);
            task.setUniqueKey("THS_PLATE|" + source + "|" + date + "|" + plateType);
            task.setPriority(3); // 浏览器任务优先级调低（耗时长）
            task.setMaxRetry(2);
            inserted += mapper.insertIfAbsent(task);
        }
        log.info("[seedThsPlate] date={} source={} inserted={}", date, source, inserted);
        return inserted;
    }

    /** 同花顺板块基础维表(THS_PLATE_DIRECT,Playwright 直连代理,不用 CloakBrowser)。 */
    public int seedThsPlateDirect(int source, String date) {
        int inserted = 0;
        int[] plateTypes = {4, 5, 6};
        for (int plateType : plateTypes) {
            String params = "{\"plate_type\":" + plateType + ",\"tradeDate\":\"" + date + "\"}";
            CrawlTask task = buildTask("THS_PLATE_DIRECT", source, date, null, null, params);
            task.setUniqueKey("THS_PLATE_DIRECT|" + source + "|" + date + "|" + plateType);
            task.setPriority(3);
            task.setMaxRetry(2);
            inserted += mapper.insertIfAbsent(task);
        }
        log.info("[seedThsPlateDirect] date={} source={} inserted={}", date, source, inserted);
        return inserted;
    }

    /** 龙虎榜明细子任务（需先爬完 DRAGON_TIGER 拿到代码列表再调用）。M3 未自动串联，留 TODO。 */
    public int seedDragonTigerDetails(String date, List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return 0;
        }
        List<CrawlTask> batch = new ArrayList<>(BATCH);
        int total = 0;
        for (String code : codes) {
            // 逐券唯一键：DRAGON_TIGER_DETAIL|source|source|code|date
            // 明细行数随席位数波动，不做量校验（defaultExpected=null）
            batch.add(buildTask("DRAGON_TIGER_DETAIL", 1, date, code, null,
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

    /**
     * 自动串联：从 dragon_tiger 表读某交易日上榜记录的 TRADE_ID → 批量下发 DRAGON_TIGER_DETAIL 子任务。
     * <p>设计为显式调用（XXL-JOB / REST），而非 dailySeed 自动触发，原因：</p>
     * <ul>
     *   <li>依赖「DRAGON_TIGER 已爬完并落库 dragon_tiger 表」，dailySeed 触发时数据未就绪；</li>
     *   <li>显式触发可在 DRAGON_TIGER 跑完后按需调用，支持单日回填与重试。</li>
     * </ul>
     * @param date 交易日（yyyy-MM-dd）
     * @return 新插入的 DRAGON_TIGER_DETAIL 任务数；dragon_tiger 无记录返回 0
     */
    public int chainDragonTigerDetails(String date) {
        LocalDate d = LocalDate.parse(date, FMT);
        List<Long> tradeIds = dragonTigerMapper.selectDistinctTradeIds(d);
        if (tradeIds.isEmpty()) {
            log.info("chainDragonTigerDetails date={} 无上榜记录（dragon_tiger 表无记录），跳过", date);
            return 0;
        }
        List<CrawlTask> batch = new ArrayList<>(100);
        int total = 0;
        for (Long tradeId : tradeIds) {
            // DRAGON_TIGER_DETAIL 的 params 带 tradeId（RPT_BILLBOARD_SEAT 按 TRADE_ID 过滤）
            // 明细行数随席位数波动，不做量校验（defaultExpected=null）
            String params = "{\"tradeId\":" + tradeId + ",\"tradeDate\":\"" + date + "\"}";
            CrawlTask task = buildTask("DRAGON_TIGER_DETAIL", 1, date, null, null, params);
            task.setUniqueKey("DRAGON_TIGER_DETAIL|1|" + date + "|" + tradeId);
            batch.add(task);
            if (batch.size() >= 100) {
                total += flush(batch);
                batch.clear();
            }
        }
        total += flush(batch);
        log.info("chainDragonTigerDetails date={} tradeIds={} inserted={}", date, tradeIds.size(), total);
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

    /** 带自定义 params 的 insertOne（用于 REGION/INDUSTRY/CONCEPT_DAILY 等需要传 boardType 的场景）。 */
    private int insertOne(String taskType, int source, String date, String code, Integer expected, String params) {
        return mapper.insertIfAbsent(buildTask(taskType, source, date, code, expected, params));
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
