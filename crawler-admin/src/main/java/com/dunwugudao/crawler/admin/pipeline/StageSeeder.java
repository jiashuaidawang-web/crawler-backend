package com.dunwugudao.crawler.admin.pipeline;

import com.dunwugudao.crawler.admin.seed.SeedGenerator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 阶段种子下发 + 上游总数捕获。
 *
 * <p>每种 stage 委托 SeedGenerator 的现有 seed* 方法,把"下发任务数"和"上游总数"打包为
 * SeedResult 返回给编排器。Phase 1 仅实现 STOCK_DAILY;其余阶段在 Phase 2 接入时补充
 * seed* 方法返回 SeedResult 的适配。</p>
 */
@Component
public class StageSeeder {

    private final SeedGenerator seedGenerator;

    public StageSeeder(SeedGenerator seedGenerator) {
        this.seedGenerator = seedGenerator;
    }

    public SeedResult seed(PipelineStage stage, LocalDate date, int source) {
        return switch (stage) {
            case STOCK_DAILY -> seedStockDaily(source, date);
            // Phase 2 接入时补全其余阶段
            default -> SeedResult.empty("阶段 " + stage + " 尚未接入编排");
        };
    }

    private SeedResult seedStockDaily(int source, LocalDate date) {
        // seedStockDailyPagesResult 带回上游真实总数(total),即使任务已存在(inserted=0)也能校验
        return seedGenerator.seedStockDailyPagesResult(source, date.toString());
    }
}
