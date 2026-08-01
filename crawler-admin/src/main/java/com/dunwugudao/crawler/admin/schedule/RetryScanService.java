package com.dunwugudao.crawler.admin.schedule;

import com.dunwugudao.crawler.persistence.mapper.CrawlTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 重试扫描 / 僵尸回收（M3-3）。
 * <p>
 * 两个兜底职责：
 * <ul>
 *   <li>{@link #reclaimZombies(int)}：节点崩溃会把任务卡在 CLAIMED，超时的 CLAIMED 重置回 PENDING 重新认领。</li>
 *   <li>{@link #promoteExhausted()}：RETRY 且 retry_count >= max_retry 的置 DEAD，避免死循环重排。</li>
 * </ul>
 * 由 retryScan job 周期性调用（建议 5–10 分钟一次）。
 */
@Slf4j
@Service
public class RetryScanService {

    private final CrawlTaskMapper mapper;

    public RetryScanService(CrawlTaskMapper mapper) {
        this.mapper = mapper;
    }

    /** 回收超时 CLAIMED 僵尸任务，返回重置回 PENDING 的条数。 */
    public int reclaimZombies(int timeoutMin) {
        int n = mapper.reclaimZombies(timeoutMin);
        log.info("reclaimZombies timeoutMin={} reclaimed={}", timeoutMin, n);
        return n;
    }

    /** 把耗尽重试的任务置 DEAD，返回置 DEAD 的条数。 */
    public int promoteExhausted() {
        int n = mapper.promoteExhausted();
        log.info("promoteExhausted promoted={}", n);
        return n;
    }
}
