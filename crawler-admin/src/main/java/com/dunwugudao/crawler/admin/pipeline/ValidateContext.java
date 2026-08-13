package com.dunwugudao.crawler.admin.pipeline;

import java.time.LocalDate;
import java.util.List;

/** 校验上下文:阶段校验所需的输入。 */
public record ValidateContext(int expectedTotal, List<Long> taskIds) {

    public static ValidateContext of(int expectedTotal, List<Long> taskIds) {
        return new ValidateContext(expectedTotal, taskIds);
    }

    public static ValidateContext empty() {
        return new ValidateContext(0, List.of());
    }
}
