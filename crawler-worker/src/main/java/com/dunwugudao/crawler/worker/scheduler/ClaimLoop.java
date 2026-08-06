package com.dunwugudao.crawler.worker.scheduler;

import com.dunwugudao.crawler.core.model.CrawlContext;
import com.dunwugudao.crawler.core.model.CrawlResult;
import com.dunwugudao.crawler.core.model.CrawlTask;
import com.dunwugudao.crawler.core.model.TaskStatus;
import com.dunwugudao.crawler.core.policy.ExponentialBackoffRetry;
import com.dunwugudao.crawler.core.policy.RetryPolicy;
import com.dunwugudao.crawler.core.strategy.SourceStrategy;
import com.dunwugudao.crawler.core.strategy.StrategyFactory;
import com.dunwugudao.crawler.core.util.JsonCheckpoint;
import com.dunwugudao.crawler.persistence.entity.CrawlLog;
import com.dunwugudao.crawler.persistence.mapper.CrawlLogMapper;
import com.dunwugudao.crawler.persistence.mapper.CrawlTaskMapper;
import com.dunwugudao.crawler.persistence.service.ClaimService;
import com.dunwugudao.crawler.persistence.service.DedupWriter;
import com.dunwugudao.crawler.persistence.service.VolumeValidator;
import com.dunwugudao.crawler.worker.config.AntiCrawlConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 认领循环（分布式采集主循环）。
 * <p>claim → StrategyFactory 执行 fetch → DedupWriter 落库 → VolumeValidator 校验 →
 * complete/fail；异常按 RetryPolicy 置 RETRY(next_retry_at) 或 FAILED/DEAD；每步写 crawl_log。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimLoop {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ClaimLoop.class);

    private final ClaimService claimService;
    private final StrategyFactory strategyFactory;
    private final DedupWriter dedupWriter;
    private final VolumeValidator volumeValidator;
    private final CrawlLogMapper crawlLogMapper;
    private final CrawlTaskMapper crawlTaskMapper;
    private final AntiCrawlConfig antiCrawlConfig;

    @Value("${crawler.node-id:${HOSTNAME:worker-node}}")
    private String nodeId;

    @Value("${crawler.batch:10}")
    private int batch;

    @Scheduled(fixedDelayString = "${crawler.claim-fixed-delay-ms:5000}")
    public void run() {
        List<com.dunwugudao.crawler.persistence.entity.CrawlTask> claimed =
                claimService.claim(batch, nodeId);
        if (claimed == null || claimed.isEmpty()) {
            return;
        }
        for (com.dunwugudao.crawler.persistence.entity.CrawlTask entity : claimed) {
            process(entity);
        }
    }

    private void process(com.dunwugudao.crawler.persistence.entity.CrawlTask entity) {
        long start = System.currentTimeMillis();
        LOG.info("[ process task={}, type={}, source={}", entity.getTaskId(), entity.getTaskType(), entity.getSource());   // TODO M6
        CrawlTask core = toCore(entity);

        CrawlContext ctx = new CrawlContext();
        ctx.setTask(core);
        ctx.setStrategyConfig(JsonCheckpoint.deserialize(entity.getParamsJson()));
        // TODO M2: 从 AntiCrawlConfig 注入限速/代理；重试策略也应由配置驱动
        int maxRetry = entity.getMaxRetry() == null ? 3 : entity.getMaxRetry();
        ctx.setRetryPolicy(new ExponentialBackoffRetry(maxRetry, Duration.ofSeconds(2), Duration.ofMinutes(5)));

        SourceStrategy strategy = strategyFactory.get(core.getSource());
        LOG.info("[ strategy={}", strategy.getClass().getSimpleName());   // TODO M6

        CrawlLog log = new CrawlLog();
        log.setTaskId(entity.getTaskId());
        log.setNode(nodeId);
        log.setUrl(entity.getUrl());
        log.setStartedAt(LocalDateTime.now());
        log.setResultStatus("RETRY");

        try {
            CrawlResult result = strategy.fetch(ctx);
            LOG.info("[ fetch ok, rows={}", result.getRowCount());   // TODO M6
            // 实际请求的 URL（策略生成）优先于种子里的 url；CK 的 src_detail 是非空列，null 兜底为空串
            String url = result.getUrl() != null ? result.getUrl() : entity.getUrl();
            log.setUrl(url);

            if (result.getData() != null && !result.getData().isEmpty()) {
                LOG.info("[ writing {} rows", result.getData().size());   // TODO M6
                dedupWriter.write(core.getTaskType(), result.getData(), core.getSource(),
                        url != null ? url : "");
                LOG.info("[ write ok");   // TODO M6
            }
            volumeValidator.validate(entity, result.getRowCount());
            claimService.complete(entity.getTaskId(), result.getRowCount(), System.currentTimeMillis() - start);
            LOG.info("[ complete ok");   // TODO M6

            log.setResultStatus("SUCCESS");
            log.setHttpStatus(result.getHttpStatus());
            log.setParseRows(result.getRowCount());
            log.setRaw(truncate(result.getRaw()));   // 落库原始响应，便于排查 parser
            log.setBytes(result.getRaw() == null ? 0L : (long) result.getRaw().length());
        } catch (Exception e) {
            LOG.error("[ process failed: {}", e.getMessage(), e);   // TODO M6
            int attempt = entity.getRetryCount() == null ? 0 : entity.getRetryCount();
            RetryPolicy policy = ctx.getRetryPolicy();
            boolean willRetry = policy.shouldRetry(attempt + 1, e);
            // 指数退避在 ClaimService.fail 内部计算（2s/4s/8s），超限置 DEAD
            claimService.fail(entity.getTaskId(), e.getMessage(), willRetry);
            log.setResultStatus("FAIL");
            log.setErrorMsg(truncate(e.getMessage()));
        } finally {
            log.setFinishedAt(LocalDateTime.now());
            log.setDurationMs(System.currentTimeMillis() - start);
            crawlLogMapper.insert(log);
        }
    }

    private CrawlTask toCore(com.dunwugudao.crawler.persistence.entity.CrawlTask e) {
        CrawlTask t = new CrawlTask();
        t.setTaskId(e.getTaskId());
        t.setTaskType(e.getTaskType());
        t.setSource(e.getSource());
        t.setUrl(e.getUrl());
        t.setParamsJson(e.getParamsJson());
        t.setStatus(e.getStatus() == null ? null : TaskStatus.valueOf(e.getStatus()));
        t.setPriority(e.getPriority() == null ? 5 : e.getPriority());
        t.setRetryCount(e.getRetryCount() == null ? 0 : e.getRetryCount());
        t.setMaxRetry(e.getMaxRetry() == null ? 3 : e.getMaxRetry());
        t.setNextRetryAt(e.getNextRetryAt());
        t.setLastNode(e.getLastNode());
        t.setUniqueKey(e.getUniqueKey());
        t.setCheckpoint(e.getCheckpoint());
        t.setExpectedCount(e.getExpectedCount());
        t.setActualCount(e.getActualCount());
        t.setErrorMsg(e.getErrorMsg());
        t.setCreatedAt(e.getCreatedAt());
        t.setUpdatedAt(e.getUpdatedAt());
        return t;
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
