package com.dunwugudao.crawler.persistence.service;

import com.dunwugudao.crawler.core.util.DateTimeUtil;
import com.dunwugudao.crawler.persistence.entity.BoardBasic;
import com.dunwugudao.crawler.persistence.mapper.BoardBasicMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * board_basic 维表同步（幂等）—— ClickHouse 版。
 *
 * <p>由 board_daily 落库副作用触发。CK 版：</p>
 * <ul>
 *   <li>去掉 MyBatis-Plus {@code QueryWrapper}（CK 不支持），改原生 SQL</li>
 *   <li>幂等由表引擎 {@code ReplacingMergeTree(_ver=data_source)} 兜底：同键重插保留高 data_source 行；
 *       查一次判定"是否已存在"仍做，减少无意义写入</li>
 * </ul>
 */
@Slf4j
@Service
public class BoardBasicSyncService {

    private final BoardBasicMapper boardBasicMapper;

    public BoardBasicSyncService(BoardBasicMapper boardBasicMapper) {
        this.boardBasicMapper = boardBasicMapper;
    }

    /**
     * 单条幂等同步 board_basic。
     *
     * @param boardCode  板块代号（f12）
     * @param boardName  板块名称（f14）
     * @param boardType  板块类型（1地域 2行业 3概念，来自 taskType 映射）
     * @param dataSource 来源（0=东财）
     */
    public void syncBoard(String boardCode, String boardName, int boardType, int dataSource) {
        if (boardCode == null || boardCode.isBlank() || boardType <= 0) {
            return;
        }
        // 先查是否已存在，减少无意义写入（最终幂等仍由 ReplacingMergeTree 保证）。
        // 查询偶发失败时(CH 间歇性 "target server failed to respond")，异常会冒泡到
        // writeBoardBasic 被 catch 吞掉、该行白白丢失，故对查询做短时重试，仅抗瞬时抖动。
        // 慢一点无所谓，不能少数据：最多 5 次尝试，秒级退避(1s/2s/4s/8s)。
        Integer existingDs = selectDataSourceWithRetry(boardType, boardCode, dataSource);
        if (existingDs != null) {
            return;
        }
        BoardBasic e = new BoardBasic();
        e.setBoardCode(boardCode);
        e.setBoardName(boardName);
        e.setBoardType(boardType);
        e.setStatus(1);
        e.setDataSource(dataSource);
        e.setTradeDate(LocalDate.now());  // 新增:记录哪天入库的
        e.setCreateDate(LocalDate.now());
        // 以下三列在原始 MySQL 中均为 nullable（迁 CK 后 String/DateTime 默认非空），东财来源天然没有这些值：
        // - code：同花顺板块指数代码（东财只有 BK 号）→ 空字符串
        // - features：备用字段，始终无内容 → 空字符串
        // - update_date：MySQL 注释"初始 NULL"，CK 非空 → 填创建时间（创建即首次更新）
        e.setCode("");
        e.setFeatures("");
        e.setUpdateDate(DateTimeUtil.nowSeconds());
        try {
            boardBasicMapper.insert(e);
        } catch (Exception ex) {
            log.warn("BoardBasicSyncService.syncBoard 新增失败(boardCode={}, boardType={}, ds={}): {}",
                    boardCode, boardType, dataSource, ex.getMessage());
        }
    }

    /**
     * 带秒级重试的"是否存在"查询——只抗 CH 瞬时抖动。
     * 慢一点无所谓，不能少数据：最多 5 次尝试，退避 1s/2s/4s/8s；仍失败则抛出让调用方决定。
     */
    private Integer selectDataSourceWithRetry(int boardType, String boardCode, int dataSource) {
        int maxAttempts = 5;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return boardBasicMapper.selectDataSource(boardType, boardCode, dataSource);
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    throw e;
                }
                long delaySeconds = 1L << (attempt - 1); // 1, 2, 4, 8
                log.warn("BoardBasicSyncService.selectDataSource 查询失败，第 {}/{} 次重试(boardType={}, boardCode={}, ds={}), {}s 后重试: {}",
                        attempt, maxAttempts, boardType, boardCode, dataSource, delaySeconds, e.getMessage());
                try {
                    Thread.sleep(delaySeconds * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        return null; // 不可达
    }
}
