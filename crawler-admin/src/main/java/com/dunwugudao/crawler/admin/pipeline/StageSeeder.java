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
            case LIMIT_POOL -> seedLimitPool(source, date);
            case STRONG_POOL -> seedGenerator.seedStrongPoolResult(source, date.toString());
            case CIXIN_POOL -> seedGenerator.seedCixinPoolResult(source, date.toString());
            case NORTHBOUND -> seedGenerator.seedNorthboundResult(source, date.toString());
            case INDEX_DAILY -> seedGenerator.seedIndexDailyResult(source, date.toString());
            case DRAGON_TIGER -> seedGenerator.seedDragonTigerResult(source, date.toString());
            case STOCK_BY_BOARD -> seedBoardRel(source, date);
            // DRAGON_TIGER_DETAIL 依赖 DRAGON_TIGER,由链式阶段触发
            case DRAGON_TIGER_DETAIL -> SeedResult.empty("DRAGON_TIGER_DETAIL 依赖 DRAGON_TIGER,由串联触发");
        };
    }

    /** 板块-个股关联:对比昨日股票数+板块数,相同则跳过省 IP。 */
    private SeedResult seedBoardRel(int source, LocalDate date) {
        int[] yesterday = getYesterdayCounts(date);
        int yestStock = yesterday[0], yestBoard = yesterday[1];

        // 今日数量(用于比较 + 跑完后记录供明天用)
        int todayStock = seedGenerator.queryStockCodeCount(source, date);
        int todayBoard = seedGenerator.queryBoardCodeCount();

        log.info("[seedBoardRel] date={} today(stock={},board={}) yesterday(stock={},board={})",
                date, todayStock, todayBoard, yestStock, yestBoard);
        return seedGenerator.seedByBoardResult(source, date.toString(),
                todayStock, todayBoard, yestStock, yestBoard);
    }

    /**
     * 获取昨日 pipeline 记录的股票数+板块数。
     * 从昨日 STOCK_BY_BOARD 阶段的 check_result 解析 {stockCount, boardCount}。
     * 若无昨日记录,返回 [0, 0] → 需要跑。
     */
    private int[] getYesterdayCounts(LocalDate date) {
        try {
            LocalDate yest = date.minusDays(1);
            PipelineRun run = pipelineMapper.selectRunByDate(yest);
            if (run == null) {
                return new int[]{0, 0};
            }
            List<PipelineStageRecord> stages = pipelineMapper.selectStages(run.getRunId());
            for (PipelineStageRecord s : stages) {
                if ("STOCK_BY_BOARD".equals(s.getStageName()) && s.getCheckResult() != null) {
                    String json = s.getCheckResult();
                    int stockCount = parseJsonInt(json, "stockCount");
                    int boardCount = parseJsonInt(json, "boardCount");
                    return new int[]{stockCount, boardCount};
                }
            }
        } catch (Exception e) {
            log.warn("[seedBoardRel] 获取昨日数量失败: {}", e.getMessage());
        }
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

    /** LIMIT_POOL 拆成 3 个子类型,返回聚合 SeedResult(总数求和)。 */
    public SeedResult seedLimitPool(int source, LocalDate date) {
        List<SeedResult> results = seedGenerator.seedLimitPoolResults(source, date.toString());
        int inserted = 0, total = 0;
        for (SeedResult r : results) {
            inserted += r.inserted();
            total += r.expectedTotal();
        }
        return new SeedResult(inserted, total, List.of(), "LIMIT_POOL 3子类型总数=" + total);
    }
}
