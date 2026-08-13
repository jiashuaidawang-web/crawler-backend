package com.dunwugudao.crawler.admin.pipeline;

import java.time.LocalDate;

/** 阶段校验器接口。 */
public interface PipelineValidator {

    String name();

    ValidateResult validate(LocalDate date, PipelineStage stage, ValidateContext ctx);
}
