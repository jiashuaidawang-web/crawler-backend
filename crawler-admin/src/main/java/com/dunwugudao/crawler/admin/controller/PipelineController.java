package com.dunwugudao.crawler.admin.controller;

import com.dunwugudao.crawler.admin.pipeline.DailyPipelineOrchestrator;
import com.dunwugudao.crawler.admin.pipeline.PipelineRunResult;
import org.springframework.web.bind.annotation.*;

/** 日批编排 REST(一键跑批 / 断点续跑 / 查状态)。 */
@RestController
@RequestMapping("/api/job/pipeline")
public class PipelineController {

    private final DailyPipelineOrchestrator orchestrator;

    public PipelineController(DailyPipelineOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /** 幂等跑批:跑完全日批。date 缺省=今天。 */
    @PostMapping("/daily")
    public PipelineRunResult daily(@RequestParam(required = false) String date) {
        String d = (date == null || date.isBlank()) ? java.time.LocalDate.now().toString() : date;
        return orchestrator.run(d);
    }

    /** 断点续跑:从首个未完成阶段继续。 */
    @PostMapping("/resume")
    public PipelineRunResult resume(@RequestParam(required = false) String date) {
        String d = (date == null || date.isBlank()) ? java.time.LocalDate.now().toString() : date;
        return orchestrator.resume(d);
    }

    /** 查某日跑批状态。 */
    @GetMapping("/status")
    public PipelineRunResult status(@RequestParam(required = false) String date) {
        String d = (date == null || date.isBlank()) ? java.time.LocalDate.now().toString() : date;
        return orchestrator.status(d);
    }
}
