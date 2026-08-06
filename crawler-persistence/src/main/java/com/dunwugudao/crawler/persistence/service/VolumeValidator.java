package com.dunwugudao.crawler.persistence.service;

import com.dunwugudao.crawler.persistence.entity.CrawlAlert;
import com.dunwugudao.crawler.persistence.entity.CrawlTask;
import com.dunwugudao.crawler.persistence.mapper.CrawlAlertMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 数据量校验（能力7）：对比 expected_count 与实际 rowCount，偏差 > 阈值（默认 20%）插入告警。
 * <p>去 MyBatis-Plus QueryWrapper，改原生 SQL（JdbcTemplate）。</p>
 */
@Slf4j
@Service
public class VolumeValidator {

    private final CrawlAlertMapper crawlAlertMapper;
    private final JdbcTemplate pgJdbcTemplate;
    private double threshold = 0.2;

    public VolumeValidator(CrawlAlertMapper crawlAlertMapper,
                           JdbcTemplate pgJdbcTemplate) {
        this.crawlAlertMapper = crawlAlertMapper;
        this.pgJdbcTemplate = pgJdbcTemplate;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public void validate(CrawlTask task, int actualCount) {
        if (task.getExpectedCount() == null) {
            return;
        }
        int expected = task.getExpectedCount();
        if (expected <= 0) {
            return;
        }
        double deviation = Math.abs(actualCount - expected) / (double) expected;
        if (deviation > threshold) {
            CrawlAlert alert = new CrawlAlert();
            alert.setAlertType("VOLUME_DEVIATION");
            alert.setTaskId(task.getTaskId());
            alert.setTaskType(task.getTaskType());
            alert.setSource(task.getSource() == null ? null : task.getSource().getCode());
            alert.setSeverity("WARN");
            alert.setMessage(String.format(
                    "expected=%d actual=%d deviation=%.2f%% (threshold=%.0f%%)",
                    expected, actualCount, deviation * 100, threshold * 100));
            alert.setValueActual(BigDecimal.valueOf(actualCount));
            alert.setValueExpected(BigDecimal.valueOf(expected));
            alert.setResolved(0);
            crawlAlertMapper.insert(alert);
            log.warn("VOLUME_DEVIATION task {}: {}", task.getTaskId(), alert.getMessage());
        }
    }

    /** 供 admin 查询最近告警用（按时间倒序，openGauss 版）。 */
    public List<CrawlAlert> recent(int resolved, int limit) {
        return pgJdbcTemplate.query(
                "SELECT * FROM crawl_alert WHERE resolved=? ORDER BY created_at DESC LIMIT ?",
                new BeanPropertyRowMapper<>(CrawlAlert.class),
                resolved, limit);
    }
}
