package com.dunwugudao.crawler.admin.pipeline;

import com.dunwugudao.crawler.persistence.mapper.CrawlTaskMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 阶段完成探测器。
 * 因 crawl_task 无 trade_date 列,日期编码在 unique_key 里(taskType|source|date[*pn]),
 * 故按 task_type IN (...) AND unique_key LIKE 'taskType|source|date%' 聚合
 * status IN ('PENDING','CLAIMED') 的计数,=0 即阶段完成。
 */
@Component
public class StageCompletionDetector {

    private final CrawlTaskMapper crawlTaskMapper;

    public StageCompletionDetector(CrawlTaskMapper crawlTaskMapper) {
        this.crawlTaskMapper = crawlTaskMapper;
    }

    /** 该阶段在指定日期是否全部完成(无 PENDING/CLAIMED)。 */
    public boolean isComplete(LocalDate date, PipelineStage stage, int source) {
        return pendingCount(date, stage, source) == 0;
    }

    public long pendingCount(LocalDate date, PipelineStage stage, int source) {
        String dateStr = date.toString(); // yyyy-MM-dd
        long total = 0;
        for (String taskType : stage.getTaskTypes()) {
            // unique_key 前缀 = taskType|source|date
            String prefix = taskType + "|" + source + "|" + dateStr;
            String like = prefix + "%";
            for (Map<String, Object> row : crawlTaskMapper.countByStatusLike(like)) {
                Object cnt = row.get("cnt");
                if (cnt instanceof Number) {
                    total += ((Number) cnt).longValue();
                }
            }
        }
        return total;
    }
}
