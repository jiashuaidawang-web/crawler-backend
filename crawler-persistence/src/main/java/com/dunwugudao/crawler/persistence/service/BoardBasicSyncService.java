package com.dunwugudao.crawler.persistence.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dunwugudao.crawler.persistence.entity.BoardBasic;
import com.dunwugudao.crawler.persistence.mapper.BoardBasicMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * board_basic 维表同步（幂等）。
 * <p>由 board_daily 落库副作用触发：每写一行 board_daily，顺手按
 * (board_type, board_code, data_source) 三字段查 board_basic，有则跳过、无则新增。</p>
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
        BoardBasic existing = selectByUnique(boardType, boardCode, dataSource);
        if (existing != null) {
            return; // 已有则跳过（名称变化不追）
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

    /** 三字段唯一查询：(board_type, board_code, data_source)。 */
    private BoardBasic selectByUnique(int boardType, String boardCode, int dataSource) {
        QueryWrapper<BoardBasic> qw = new QueryWrapper<>();
        qw.eq("board_type", boardType)
          .eq("board_code", boardCode)
          .eq("data_source", dataSource)
          .last("LIMIT 1");
        return boardBasicMapper.selectOne(qw);
    }
}
