package com.dunwugudao.crawler.persistence.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * ClickHouse 批量写入工具。
 *
 * <p>CK 不适合高频单行 INSERT，必须批量。本工具封装 JDBC {@link PreparedStatement#addBatch()} /
 * {@link PreparedStatement#executeBatch()}，提供：</p>
 * <ul>
 *   <li>{@link #batchInsert(String, List, BiConsumer)} —— 通用批量 INSERT（VALUES 多行）</li>
 *   <li>{@link #flush(String, List, BiConsumer)} —— 仅 flush 外部攒的 PreparedStatement</li>
 * </ul>
 *
 * <p>注意：CK JDBC 0.6.x 的 {@code executeBatch()} 实际是把多条合成一个多值 INSERT，
 * 单次建议 ≥ 1000 行，最大不超过 {@link #MAX_BATCH}（避免单语句过大）。</p>
 */
@Slf4j
@Component
public class ClickHouseBatchInserter {

    /** 单次 executeBatch 最大行数（CK 推荐 1000~10000，按行宽调整） */
    public static final int MAX_BATCH = 1000;

    private final DataSource chDataSource;

    public ClickHouseBatchInserter(@Qualifier("chDataSource") DataSource chDataSource) {
        this.chDataSource = chDataSource;
    }

    /**
     * 批量 INSERT。
     *
     * @param sql    形如 "INSERT INTO stock_daily (c1,c2,...) VALUES (?,?,...)"
     * @param rows   行数据
     * @param binder (ps, rowIndex) -> 设占位符值
     * @param <T>    行类型
     */
    public <T> int batchInsert(String sql, List<T> rows, BiConsumer<PreparedStatement, T> binder) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int total = 0;
        // 按 MAX_BATCH 切片，避免单语句过大
        for (int from = 0; from < rows.size(); from += MAX_BATCH) {
            int to = Math.min(from + MAX_BATCH, rows.size());
            List<T> slice = rows.subList(from, to);
            total += executeOneBatch(sql, slice, binder);
        }
        return total;
    }

    private <T> int executeOneBatch(String sql, List<T> slice, BiConsumer<PreparedStatement, T> binder) {
        try (Connection conn = chDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (T row : slice) {
                binder.accept(ps, row);
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            int sum = 0;
            for (int c : counts) {
                sum += (c >= 0 ? c : 0);
            }
            if (log.isDebugEnabled()) {
                log.debug("ClickHouse batchInsert: rows={}, affected={}", slice.size(), sum);
            }
            return sum;
        } catch (SQLException e) {
            log.error("ClickHouse batchInsert 失败(sql={}, rows={}): {}", sql, slice.size(), e.getMessage());
            throw new RuntimeException("ClickHouse batchInsert 失败", e);
        }
    }
}
