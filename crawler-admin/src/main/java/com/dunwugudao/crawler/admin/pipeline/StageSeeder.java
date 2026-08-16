package com.dunwugudao.crawler.admin.pipeline;

import com.dunwugudao.crawler.admin.seed.SeedGenerator;
import com.dunwugudao.crawler.persistence.mapper.PipelineMapper;
import com.dunwugudao.crawler.persistence.entity.PipelineRun;
import com.dunwugudao.crawler.persistence.entity.PipelineStageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 阶段种子下发 + 上游总数捕获。
 * 每种 stage 委托 SeedGenerator 的 seed*Result 方法,把"下发任务数"和"上游总数"打包为 SeedResult。
 */
@Component
public class StageSeeder {

    private static final Logger log = LoggerFactory.getLogger(StageSeeder.class);

    private final SeedGenerator seedGenerator;
    private final PipelineMapper pipelineMapper;

    /** BOARD_BASIC 阶段发现的新板块(传给 STOCK_BY_BOARD 用,避免全量探测)。 */
    private List<String> newBoardsFromBoardBasic;

    public StageSeeder(SeedGenerator seedGenerator, PipelineMapper pipelineMapper) {
        this.seedGenerator = seedGenerator;
        this.pipelineMapper = pipelineMapper;
    }

    public SeedResult seed(PipelineStage stage, LocalDate date, int source) {
        return switch (stage) {
            case STOCK_DAILY -> seedGenerator.seedStockDailyPagesResult(source, date.toString());
            case REGION_DAILY -> seedGenerator.seedRegionDailyResult(source, date.toString());
            case INDUSTRY_DAILY -> seedGenerator.seedIndustryDailyResult(source, date.toString());
            case CONCEPT_DAILY -> seedGenerator.seedConceptDailyResult(source, date.toString());
            case MAIN_FUND_STOCK -> seedGenerator.seedMainFundStockResult(source, date.toString());
            case MAIN_FUND_BOARD -> seedGenerator.seedMainFundBoardResult(source, date.toString());
            case LIMIT_UP -> seedLimitPoolSub("LIMIT_UP", source, date);
            case LIMIT_DOWN -> seedLimitPoolSub("LIMIT_DOWN", source, date);
            case LIMIT_ZHABAN -> seedLimitPoolSub("LIMIT_ZHABAN", source, date);
            case STRONG_POOL -> seedGenerator.seedStrongPoolResult(source, date.toString());
            case CIXIN_POOL -> seedGenerator.seedCixinPoolResult(source, date.toString());
            case NORTHBOUND -> seedGenerator.seedNorthboundResult(source, date.toString());
            case INDEX_DAILY -> seedGenerator.seedIndexDailyResult(source, date.toString());
            case DRAGON_TIGER -> seedGenerator.seedDragonTigerResult(source, date.toString());
            case BOARD_BASIC -> {
                // BOARD_BASIC 发现的新板块暂存,供 STOCK_BY_BOARD 使用
                SeedResult result = seedGenerator.seedBoardBasicAllResult(source, date.toString());
                newBoardsFromBoardBasic = seedGenerator.getNewBoardCodesFromLastRun();
                yield result;
            }
            case STOCK_BY_BOARD -> {
                SeedResult result = seedBoardRel(source, date, newBoardsFromBoardBasic);
                newBoardsFromBoardBasic = null;  // 用完清空
                yield result;
            }
            case STOCK_WEEKLY -> seedGenerator.seedWeeklyResult(source, date.toString());
            // DRAGON_TIGER_DETAIL 依赖 DRAGON_TIGER,由链式阶段触发
            case DRAGON_TIGER_DETAIL -> SeedResult.empty("DRAGON_TIGER_DETAIL 依赖 DRAGON_TIGER,由串联触发");
        };
    }

    /** 板块-个股关联:对比最近一次股票数+板块数,相同则跳过省 IP。 */
    private SeedResult seedBoardRel(int source, LocalDate date, List<String> newBoardCodes) {
        int[] latest = getLatestCounts(date);
        int latestStock = latest[0], latestBoard = latest[1];

        // 今日数量(用于比较 + 跑完后记录供明天用)
        int todayStock = seedGenerator.queryStockCodeCount(source, date);
        int todayBoard = seedGenerator.queryBoardCodeCount();

        log.info("[seedBoardRel] date={} today(stock={},board={}) latest(stock={},board={}) newBoards={}",
                date, todayStock, todayBoard, latestStock, latestBoard,
                newBoardCodes != null ? newBoardCodes.size() : "null");
        return seedGenerator.seedByBoardResult(source, date.toString(),
                todayStock, todayBoard, latestStock, latestBoard, newBoardCodes, null);
    }

    /**
     * 获取昨日 pipeline 记录的股票数+板块数。
     * 从昨日 STOCK_BY_BOARD 阶段的 check_result 解析 {stockCount, boardCount}。
     * 若无昨日记录,返回 [0, 0] → 需要跑。
     */
    /**
     * 获取最近一次有数据的股票数+板块数(用于智能验证对比)。
     * <p>从昨天开始往前找,最多回溯 7 天,找到第一个有 STOCK_BY_BOARD 数据的 run。
     * 这样即使周一跑(周日无数据)或服务宕机几天,也能用最近的有效数据做对比。</p>
     */
    private int[] getLatestCounts(LocalDate date) {
        // 回溯最近 7 天,找第一个有数据的
        for (int i = 1; i <= 7; i++) {
            LocalDate checkDate = date.minusDays(i);
            try {
                PipelineRun run = pipelineMapper.selectRunByDate(checkDate);
                if (run == null) {
                    continue;
                }
                List<PipelineStageRecord> stages = pipelineMapper.selectStages(run.getRunId());
                for (PipelineStageRecord s : stages) {
                    if ("STOCK_BY_BOARD".equals(s.getStageName()) && s.getCheckResult() != null) {
                        String json = s.getCheckResult();
                        int stockCount = parseJsonInt(json, "stockCount");
                        int boardCount = parseJsonInt(json, "boardCount");
                        if (stockCount > 0 || boardCount > 0) {
                            log.info("[getLatestCounts] 找到 {} 的数据(stock={},board={}),用于对比",
                                    checkDate, stockCount, boardCount);
                            return new int[]{stockCount, boardCount};
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[getLatestCounts] 查询 {} 失败: {}", checkDate, e.getMessage());
            }
        }
        log.info("[getLatestCounts] 最近 7 天无 STOCK_BY_BOARD 数据,需全量跑");
        return new int[]{0, 0};
    }

    private int parseJsonInt(String json, String key) {
        try {
            String marker = "\"" + key + "\":";
            int idx = json.indexOf(marker);
            if (idx < 0) return 0;
            int start = idx + marker.length();
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
                end++;
            }
            return Integer.parseInt(json.substring(start, end));
        } catch (Exception e) {
            return 0;
        }
    }

    /** LIMIT_UP 拆成 3 个子类型,返回聚合 SeedResult(总数求和)。 */
    public SeedResult seedLimitPool(int source, LocalDate date) {
        List<SeedResult> results = seedGenerator.seedLimitPoolResults(source, date.toString());
        int inserted = 0, total = 0;
        for (SeedResult r : results) {
            inserted += r.inserted();
            total += r.expectedTotal();
        }
        return new SeedResult(inserted, total, List.of(), "LIMIT_UP 3子类型总数=" + total);
    }

    /** LIMIT_POOL 单种子类型种子(涨停/跌停/炸板各自独立阶段用)。 */
    public SeedResult seedLimitPoolSub(String limitType, int source, LocalDate date) {
        SeedResult r = seedGenerator.seedPoolTasksSingleResult(limitType, source, date.toString());
        return new SeedResult(r.inserted(), r.expectedTotal(), r.taskIds(), limitType + "=" + r.inserted());
    }
}
