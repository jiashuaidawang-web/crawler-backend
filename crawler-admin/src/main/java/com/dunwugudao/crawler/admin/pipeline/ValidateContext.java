package com.dunwugudao.crawler.admin.pipeline;

import java.time.LocalDate;
import java.util.List;

/** 校验上下文:阶段校验所需的输入。 */
public record ValidateContext(int expectedTotal, int source, List<Long> taskIds) {

    public static ValidateContext of(int expectedTotal, int source, List<Long> taskIds) {
        return new ValidateContext(expectedTotal, source, taskIds);
    }

    public static ValidateContext empty() {
        return new ValidateContext(0, 0, List.of());
    }
}
