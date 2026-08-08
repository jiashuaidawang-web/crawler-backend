package com.dunwugudao.crawler.admin.service;

import com.dunwugudao.crawler.core.util.DateTimeUtil;

import com.dunwugudao.crawler.admin.seed.BoardUniverseProvider;
import com.dunwugudao.crawler.persistence.entity.BoardBasic;
import com.dunwugudao.crawler.persistence.mapper.BoardBasicMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 板块基础数据维护服务。
 * <p>每日从接口获取板块列表，对比 board_basic 表，发现新增/删除/更新。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BoardBasicService {

    private final BoardUniverseProvider boardUniverseProvider;
    private final BoardBasicMapper boardBasicMapper;

    /**
     * 每日维护板块基础数据。
     * @return [新增数量, 删除数量, 更新数量]
     */
    public int[] maintain() {
        // 1. 从接口获取最新板块列表
        List<BoardUniverseProvider.BoardInfo> latestBoards = boardUniverseProvider.boardInfos();
        Set<String> latestCodes = latestBoards.stream()
                .map(BoardUniverseProvider.BoardInfo::boardCode)
                .collect(Collectors.toSet());

        // 2. 查询现有板块
        List<BoardBasic> existingBoards = boardBasicMapper.selectList(null);
        Set<String> existingCodes = existingBoards.stream()
                .map(BoardBasic::getBoardCode)
                .collect(Collectors.toSet());

        // 3. 新增板块（接口有，DB 没有）
        int added = 0;
        for (BoardUniverseProvider.BoardInfo info : latestBoards) {
            if (!existingCodes.contains(info.boardCode())) {
                BoardBasic entity = new BoardBasic();
                entity.setBoardType(info.boardType());
                entity.setBoardCode(info.boardCode());
                entity.setBoardName(info.boardName());
                entity.setStatus(1);
                entity.setDataSource(0); // 东财
                entity.setCreateDate(LocalDate.now());
                try {
                    boardBasicMapper.insert(entity);
                    added++;
                } catch (Exception e) {
                    log.warn("新增板块失败({})：{}", info.boardCode(), e.getMessage());
                }
            }
        }

        // 4. 删除板块（DB 有，接口没有 → status=0）
        int removed = 0;
        for (BoardBasic existing : existingBoards) {
            if (!latestCodes.contains(existing.getBoardCode()) && existing.getStatus() != 0) {
                existing.setStatus(0);
                existing.setUpdateDate(DateTimeUtil.nowSeconds());
                boardBasicMapper.updateById(existing);
                removed++;
            }
        }

        // 5. 更新板块（名称可能变化）
        int updated = 0;
        for (BoardUniverseProvider.BoardInfo info : latestBoards) {
            if (existingCodes.contains(info.boardCode())) {
                // 检查名称是否变化
                BoardBasic existing = existingBoards.stream()
                        .filter(b -> b.getBoardCode().equals(info.boardCode()))
                        .findFirst()
                        .orElse(null);
                if (existing != null && !info.boardName().equals(existing.getBoardName())) {
                    existing.setBoardName(info.boardName());
                    existing.setUpdateDate(DateTimeUtil.nowSeconds());
                    boardBasicMapper.updateById(existing);
                    updated++;
                }
            }
        }

        log.info("板块基础数据维护完成：新增={}, 删除={}, 更新={}", added, removed, updated);
        return new int[]{added, removed, updated};
    }
}
