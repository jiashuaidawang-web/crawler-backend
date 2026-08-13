package com.dunwugudao.crawler.admin.pipeline;

import com.dunwugudao.crawler.admin.seed.SeedGenerator;
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

    private final SeedGenerator seedGenerator;

    public StageSeeder(SeedGenerator seedGenerator) {
        this.seedGenerator = seedGenerator;
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
            // DRAGON_TIGER_DETAIL 依赖 DRAGON_TIGER,不单独 seed(由 chainDragonTigerDetails 串联)
            case DRAGON_TIGER_DETAIL ->SeedResult.empty("DRAGON_TIGER_DETAIL 依赖 DRAGON_TIGER,由串联触发");
        };
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
