package com.dunwugudao.crawler.admin.seed;

import com.dunwugudao.crawler.admin.service.BoardBasicService;
import com.dunwugudao.crawler.core.model.SourceType;
import com.dunwugudao.crawler.persistence.entity.BoardBasic;
import com.dunwugudao.crawler.persistence.entity.CrawlTask;
import com.dunwugudao.crawler.persistence.mapper.BoardBasicMapper;
import com.dunwugudao.crawler.persistence.mapper.CrawlTaskMapper;
import com.dunwugudao.crawler.persistence.mapper.DragonTigerMapper;
import com.dunwugudao.crawler.strategy.eastmoney.EastmoneyClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final int BATCH = 500;

    /** LIMIT_POOL 拆成的三个子任务 */
    private static final List<String> LIMIT_SUBTYPES =
            List.of(TaskTypeCatalog.LIMIT_UP, TaskTypeCatalog.LIMIT_DOWN, TaskTypeCatalog.LIMIT_ZHABAN);

    private final CrawlTaskMapper mapper;
    private final StockUniverseProvider universe;
    private final BoardBasicMapper boardBasicMapper;
    private final DragonTigerMapper dragonTigerMapper;
    private final ProxyManager proxyManager;
    private final EastmoneyClient eastmoneyClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 全市场股票总数（计算 STOCK_DAILY 总页数）。 */
    private final int stockDailySize;
    /** STOCK_DAILY 每页条数（东财 clist pz 最大值 100）。 */
    private final int stockDailyPageSize;
    /** STOCK_BY_BOARD 每页条数。 */
    private static final int BOARD_BY_BOARD_PAGE_SIZE = 100;

    public SeedGenerator(CrawlTaskMapper mapper, StockUniverseProvider universe,
                         BoardBasicMapper boardBasicMapper,
                         DragonTigerMapper dragonTigerMapper,
                         ProxyManager proxyManager,
                         EastmoneyClient eastmoneyClient,
                         org.springframework.core.env.Environment env) {
        this.mapper = mapper;
        this.universe = universe;
        this.boardBasicMapper = boardBasicMapper;
        this.dragonTigerMapper = dragonTigerMapper;
        this.proxyManager = proxyManager;
        this.eastmoneyClient = eastmoneyClient;
        // 默认全市场 5545 只、每页 100 条（56 页）；可通过 application.yml 覆盖
        this.stockDailySize = Integer.parseInt(env.getProperty("stock-daily.now-stock-size", "5545"));
        this.stockDailyPageSize = Integer.parseInt(env.getProperty("stock-daily.page-size", "100"));
    }

    /** 单个交易日的市场级 + 逐券种子（dailyCloseSeed 用）。 */
    public int dailySeed(String date, int source) {
        // board_basic 改为 board_daily 同步的副作用维护，不再单独 maintain（见 BoardBasicSyncService）

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
            } else if ("STOCK_DAILY".equals(spec.taskType())) {
                // 全市场快照按页拆任务：总页数 = ceil(nowStockSize / pageSize)，每页一个 task（pn 从 1 开始）
                n += seedStockDailyPages(source, date);
            } else if ("REGION_DAILY".equals(spec.taskType())) {
                n += insertOne(spec.taskType(), source, date, null, null,
                        TaskTypeCatalog.buildParams(spec.taskType(), date, 1));
            } else if ("INDUSTRY_DAILY".equals(spec.taskType())) {
                n += insertOne(spec.taskType(), source, date, null, null,
                        TaskTypeCatalog.buildParams(spec.taskType(), date, 2));
            } else if ("CONCEPT_DAILY".equals(spec.taskType())) {
                n += insertOne(spec.taskType(), source, date, null, null,
                        TaskTypeCatalog.buildParams(spec.taskType(), date, 3));
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
        // 逐板块：从 board_basic 表读去重 boardCode，每个板块一个任务（板块-个股关联初始化）
        n += seedByBoard(source, date);
        log.info("dailySeed date={} source={} inserted={}", date, source, n);
        return n;
    }

    /**
     * STOCK_DAILY 按页拆任务：总页数 = ceil(nowStockSize / pageSize)，每页一个 task（pn 从 1 开始）。
     * <p>唯一键：STOCK_DAILY|source|date|pn（幂等，重复 seed 不重复入库）。
     * worker 执行时只取 params.pn 拼 URL，不做分页判断。</p>
     */
    public int seedStockDailyPages(int source, String date) {
        int totalPages = (stockDailySize + stockDailyPageSize - 1) / stockDailyPageSize;
        List<CrawlTask> batch = new ArrayList<>(BATCH);
        int total = 0;
        for (int pn = 1; pn <= totalPages; pn++) {
            String params = TaskTypeCatalog.buildPageParams(date, pn);
            CrawlTask task = buildTask("STOCK_DAILY", source, date, null, null, params);
            task.setUniqueKey(TaskTypeCatalog.buildPageUniqueKey("STOCK_DAILY", source, date, pn));
            batch.add(task);
            if (batch.size() >= BATCH) {
                total += flush(batch);
                batch.clear();
            }
        }
        total += flush(batch);
        log.info("seedStockDailyPages date={} size={} pageSize={} pages={} inserted={}",
                date, stockDailySize, stockDailyPageSize, totalPages, total);
        return total;
    }

    /** 逐板块种子：读 board_basic 表去重 boardCode，每个板块先请求 total 再按页拆任务。 */
    private int seedByBoard(int source, String date) {
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

    /** 请求某板块下股票总数（pz=1，只拿 total）。 */
    private int fetchBoardStockTotal(String boardCode, String date) {
        String fs = "b:" + boardCode.toLowerCase() + "+f:!50";
        String url = "https://push2.eastmoney.com/weblogin/api/qt/clist/get"
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
            log.warn("[fetchBoardStockTotal] 失败(boardCode={}): {}", boardCode, e.getMessage());
            return 0;
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
            } else if ("STOCK_DAILY".equals(spec.taskType())) {
                // 历史回填也按页拆
                for (int pn = 1; pn <= totalPages(date); pn++) {
                    String params = TaskTypeCatalog.buildPageParams(date, pn);
                    CrawlTask task = buildTask("STOCK_DAILY", source, date, null, null, params);
                    task.setUniqueKey(TaskTypeCatalog.buildPageUniqueKey("STOCK_DAILY", source, date, pn));
                    batch.add(task);
                }
            } else {
                batch.add(buildTask(spec.taskType(), source, date, null, null));
            }
        }
        return flush(batch);
    }

    /** STOCK_DAILY 总页数（size / pageSize 向上取整）。 */
    private int totalPages(String date) {
        return (stockDailySize + stockDailyPageSize - 1) / stockDailyPageSize;
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
            // 逐券唯一键：DRAGON_TIGER_DETAIL|source|source|code|date
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

    /**
     * 自动串联：从 dragon_tiger 表读某交易日上榜代码 → 批量下发 DRAGON_TIGER_DETAIL 子任务。
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
        List<String> codes = dragonTigerMapper.selectDistinctCodes(d);
        if (codes.isEmpty()) {
            log.info("chainDragonTigerDetails date={} 无上榜代码（dragon_tiger 表无记录），跳过", date);
            return 0;
        }
        log.info("chainDragonTigerDetails date={} 读到 {} 个上榜代码，开始下发 DRAGON_TIGER_DETAIL", date, codes.size());
        return seedDragonTigerDetails(date, codes);
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
