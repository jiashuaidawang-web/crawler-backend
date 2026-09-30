package com.dunwugudao.crawler.admin.backfill;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;

/**
 * 回填配置（绑定 spring backfill.* 前缀）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "backfill")
public class BackfillConfig {

    /** 起始日期（默认 1990-12-19，A股开市日） */
    private LocalDate startDate = LocalDate.of(1990, 12, 19);

    /** 结束日期（默认今天） */
    private LocalDate endDate = LocalDate.now();

    /** 数据源（1=东财） */
    private int source = 1;

    /** 是否跳过日历构建（日历已有时可跳过） */
    private boolean skipCalendarBuild = false;

    /** 仅检测差距，不发任务（dry-run 模式） */
    private boolean gapOnly = true;

    /** 是否启用回填（CommandLineRunner 触发开关） */
    private boolean enabled = true;
}
