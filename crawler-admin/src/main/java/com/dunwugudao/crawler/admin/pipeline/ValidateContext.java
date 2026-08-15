package com.dunwugudao.crawler.admin.pipeline;

import java.util.List;

/** 校验上下文:阶段校验所需的输入。 */
public record ValidateContext(int expectedTotal, int source, List<Long> taskIds, int baselineRows) {

    public static ValidateContext of(int expectedTotal, int source, List<Long> taskIds, int baselineRows) {
        return new ValidateContext(expectedTotal, source, taskIds, baselineRows);
    }

    public static ValidateContext of(int expectedTotal, int source, List<Long> taskIds) {
        return new ValidateContext(expectedTotal, source, taskIds, 0);
    }

    public static ValidateContext empty() {
        return new ValidateContext(0, 0, List.of(), 0);
    }
}
