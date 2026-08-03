package com.dunwugudao.crawler.admin.controller;

import com.dunwugudao.crawler.admin.dto.SeedRequest;
import com.dunwugudao.crawler.admin.seed.SeedGenerator;
import com.dunwugudao.crawler.core.model.SourceType;
import com.dunwugudao.crawler.persistence.entity.CrawlTask;
import com.dunwugudao.crawler.persistence.mapper.CrawlTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 种子任务下发：插入 crawl_task（PENDING）供 worker 认领。
 * <p>STOCK_DAILY 自动走 {@link SeedGenerator#seedStockDailyPages} 按页拆任务（每页 100 条）；
 * 其他 taskType 直接插入单个 task。</p>
 */
@RestController
@RequestMapping("/api/crawl")
@RequiredArgsConstructor
public class SeedController {

    private final CrawlTaskMapper crawlTaskMapper;
    private final SeedGenerator seedGenerator;

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestBody SeedRequest req) {
        // STOCK_DAILY：按页拆任务（每页 100 条，避免单 task 过大）
        if ("STOCK_DAILY".equals(req.taskType())) {
            String date = req.tradeDate() != null ? req.tradeDate() : "2026-08-01";
            int inserted = seedGenerator.seedStockDailyPages(req.source() == null ? 1 : req.source(), date);
            Map<String, Object> r = new HashMap<>();
            r.put("taskType", "STOCK_DAILY");
            r.put("date", date);
            r.put("pagesInserted", inserted);
            return r;
        }

        // 其他 taskType：直接插入单个 task（幂等）
        CrawlTask task = new CrawlTask();
        task.setTaskType(req.taskType());
        task.setSource(SourceType.fromCode(req.source() == null ? 1 : req.source()));
        task.setUrl(req.url());
        task.setParamsJson(req.paramsJson());
        task.setUniqueKey(req.uniqueKey());
        task.setExpectedCount(req.expectedCount());
        task.setStatus("PENDING");
        task.setPriority(req.priority() == null ? 5 : req.priority());
        task.setRetryCount(0);
        task.setMaxRetry(req.maxRetry() == null ? 3 : req.maxRetry());
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        // 幂等：重复 seed 不报错，返回已存在/新插入的 task
        int rows = crawlTaskMapper.insertIfAbsent(task);

        Map<String, Object> r = new HashMap<>();
        r.put("taskId", task.getTaskId());
        r.put("status", "PENDING");
        r.put("inserted", rows);  // 1=新插入, 0=已存在
        return r;
    }
}
