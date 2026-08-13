package com.dunwugudao.crawler.admin.pipeline;

import java.util.List;

/** 单次种子下发的结果:下发任务数 + 上游总数(校验真值) + 任务 id 列表。 */
public record SeedResult(int inserted, int expectedTotal, List<Long> taskIds, String message) {

    public static SeedResult empty(String message) {
        return new SeedResult(0, 0, List.of(), message);
    }
}
