package com.dunwugudao.crawler.persistence.service;

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
        // 先查是否已存在，减少无意义写入（最终幂等仍由 ReplacingMergeTree 保证）
        Integer existingDs = boardBasicMapper.selectDataSource(boardType, boardCode, dataSource);
        if (existingDs != null) {
            return;
        }
        BoardBasic e = new BoardBasic();
        e.setBoardCode(boardCode);
        e.setBoardName(boardName);
        e.setBoardType(boardType);
        e.setStatus(1);
        e.setDataSource(dataSource);
        e.setCreateDate(LocalDate.now());
        try {
            boardBasicMapper.insert(e);
        } catch (Exception ex) {
            log.warn("BoardBasicSyncService.syncBoard 新增失败(boardCode={}, boardType={}, ds={}): {}",
                    boardCode, boardType, dataSource, ex.getMessage());
        }
    }
}
