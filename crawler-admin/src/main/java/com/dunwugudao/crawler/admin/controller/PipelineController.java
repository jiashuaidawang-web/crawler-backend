package com.dunwugudao.crawler.admin.controller;

import com.dunwugudao.crawler.admin.pipeline.DailyPipelineOrchestrator;
import com.dunwugudao.crawler.admin.pipeline.PipelineRunResult;
import com.dunwugudao.crawler.admin.pipeline.SeedResult;
import com.dunwugudao.crawler.persistence.entity.PipelineRun;
import com.dunwugudao.crawler.persistence.entity.PipelineStageRecord;
import com.dunwugudao.crawler.persistence.service.IpConsumptionService;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 日批编排 REST(一键跑批 / 断点续跑 / 查状态 / 历史 run / 重试失败阶段)。 */
@RestController
@RequestMapping("/api/job/pipeline")
public class PipelineController {

    private final DailyPipelineOrchestrator orchestrator;
    private final IpConsumptionService ipConsumptionService;

    public PipelineController(DailyPipelineOrchestrator orchestrator, IpConsumptionService ipConsumptionService) {
        this.orchestrator = orchestrator;
        this.ipConsumptionService = ipConsumptionService;
    }

    /** 预览各阶段预期数据量(不实际跑批)。 */
    @GetMapping("/preview")
    public Map<String, Object> preview(@RequestParam(required = false) String date) {
        String d = (date == null || date.isBlank()) ? java.time.LocalDate.now().toString() : date;
        return orchestrator.preview(d);
    }

    /** 幂等跑批:跑完全日批。date 缺省=今天。force=true 强制重跑(含已 SUCCESS 的任务)。 */
    @PostMapping("/daily")
    public PipelineRunResult daily(@RequestParam(required = false) String date,
                                   @RequestParam(required = false, defaultValue = "false") boolean force) {
        String d = (date == null || date.isBlank()) ? java.time.LocalDate.now().toString() : date;
        return orchestrator.run(d, force);
    }

    /** 断点续跑:从首个未完成阶段继续。 */
    @PostMapping("/resume")
    public PipelineRunResult resume(@RequestParam(required = false) String date) {
        String d = (date == null || date.isBlank()) ? java.time.LocalDate.now().toString() : date;
        return orchestrator.resume(d);
    }

    /** 查某日最新跑批状态。 */
    @GetMapping("/status")
    public PipelineRunResult status(@RequestParam(required = false) String date) {
        String d = (date == null || date.isBlank()) ? java.time.LocalDate.now().toString() : date;
        return orchestrator.status(d);
    }

    /** 查某日某次 run 详情(用于历史 run)。date 仅用于返回展示,实际按 runId 查。 */
    @GetMapping("/status/{runId}")
    public PipelineRunResult statusByRunId(@PathVariable Long runId, @RequestParam(required = false) String date) {
        String d = (date == null || date.isBlank()) ? java.time.LocalDate.now().toString() : date;
        return orchestrator.statusByRunId(d, runId);
    }

    /** 列出某日所有 run(历史跑批,最新在前)。 */
    @GetMapping("/runs")
    public List<PipelineRun> runs(@RequestParam(required = false) String date) {
        String d = (date == null || date.isBlank()) ? java.time.LocalDate.now().toString() : date;
        return orchestrator.history(d);
    }

    /** 手动重试某次跑批中失败的阶段。返回该阶段重试后的最新状态(含 userMessage)。 */
    @PostMapping("/retry-stage")
    public PipelineStageRecord retryStage(@RequestParam String date, @RequestParam String stage) {
        return orchestrator.retryStage(date, stage);
    }

    /** 万能修复:自动诊断+自动修复某阶段,目标是数据对得上。返回修复动作和说明。 */
    @PostMapping("/fix-stage")
    public Map<String, Object> fixStage(@RequestParam String date, @RequestParam String stage) {
        return orchestrator.fixStage(date, stage);
    }

    /** 确认某阶段通过(误报/口径差异时手动确认)。 */
    @PostMapping("/confirm-stage")
    public PipelineStageRecord confirmStage(@RequestParam String date, @RequestParam String stage) {
        return orchestrator.confirmStage(date, stage);
    }

    /** 重新下发某阶段的种子任务(幂等)。 */
    @PostMapping("/reseed-stage")
    public Map<String, Object> reseedStage(@RequestParam String date, @RequestParam String stage) {
        SeedResult result = orchestrator.reseedStage(date, stage);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("stage", stage);
        r.put("date", date);
        r.put("inserted", result.inserted());
        r.put("expectedTotal", result.expectedTotal());
        r.put("message", result.message());
        return r;
    }

    /** 诊断失败阶段:判断是 seed 不完整 / task 失败 / 引擎去重。 */
    @GetMapping("/diagnose-stage")
    public java.util.Map<String, Object> diagnoseStage(@RequestParam String date, @RequestParam String stage) {
        return orchestrator.diagnoseStage(date, stage);
    }

    /** 近 N 天平均 IP 消耗（采购预测）。 */
    @GetMapping("/ip-stats/avg")
    public java.util.Map<String, Object> ipStatsAvg(@RequestParam(defaultValue = "7") int days) {
        return ipConsumptionService.avgDailyIps(days);
    }

    /** 按业务统计某日 IP 消耗。 */
    @GetMapping("/ip-stats/by-stage")
    public java.util.Map<String, Object> ipStatsByStage(@RequestParam String date) {
        return ipConsumptionService.statsByStage(java.time.LocalDate.parse(date));
    }

    /** 测试：手动插入一条 IP 消耗记录（验证 SQL）。 */
    @PostMapping("/ip-stats/test")
    public String ipStatsTest() {
        ipConsumptionService.log("ADMIN", "TEST_STAGE", "TEST_TASK", null, "1.2.3.4:8080", "SUCCESS", 1024, 100L, null, java.time.LocalDate.now());
        return "logged";
    }

    /** 测试：查询所有 IP 消耗记录。 */
    @GetMapping("/ip-stats/all")
    public java.util.Map<String, Object> ipStatsAll() {
        return ipConsumptionService.statsByStage(java.time.LocalDate.now());
    }

    /** 按 consumer_type（ADMIN/WORKER）统计某日 IP 消耗。 */
    @GetMapping("/ip-stats/by-consumer")
    public java.util.Map<String, Object> ipStatsByConsumer(@RequestParam String date) {
        return ipConsumptionService.statsByConsumerType(java.time.LocalDate.parse(date));
    }

    /** 按代理商统计某日 IP 消耗。 */
    @GetMapping("/ip-stats/by-agent")
    public java.util.Map<String, Object> ipStatsByAgent(@RequestParam String date) {
        return ipConsumptionService.statsByAgent(java.time.LocalDate.parse(date));
    }
}
