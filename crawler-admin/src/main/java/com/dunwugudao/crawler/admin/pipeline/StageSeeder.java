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
        // seedStockDailyPages 已探测上游 total 并计算页数,但当前返回 int(任务数)。
        // Phase 1 为快速验证,用"页数*页大小"作为 expectedTotal 近似值;
        // Phase 2 改为 SeedGenerator 直接返回 SeedResult(带回真实 total)。
        int inserted = seedGenerator.seedStockDailyPages(source, date.toString());
        // 近似上游总数:inserted 个任务 * 100(页大小)。实际 total 可能略小于此值(最后一页不满)。
        int approxTotal = inserted * 100;
        return new SeedResult(inserted, approxTotal, List.of(),
                "STOCK_DAILY 按页下发 " + inserted + " 个任务,近似总数 " + approxTotal);
    }
}
