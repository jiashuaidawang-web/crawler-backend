package com.dunwugudao.crawler.admin.controller;

import com.dunwugudao.crawler.admin.dto.SeedRequest;
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
 */
@RestController
@RequestMapping("/api/crawl")
@RequiredArgsConstructor
public class SeedController {

    private final CrawlTaskMapper crawlTaskMapper;

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestBody SeedRequest req) {
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
        crawlTaskMapper.insert(task);

        Map<String, Object> r = new HashMap<>();
        r.put("taskId", task.getTaskId());
        r.put("status", "PENDING");
        return r;
    }
}
