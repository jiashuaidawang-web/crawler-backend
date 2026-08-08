package com.dunwugudao.crawler.admin.seed;

import com.dunwugudao.crawler.core.util.DateTimeUtil;
import com.dunwugudao.crawler.persistence.entity.BoardBasic;
import com.dunwugudao.crawler.persistence.entity.Concept;
import com.dunwugudao.crawler.persistence.mapper.BoardBasicMapper;
import com.dunwugudao.crawler.persistence.mapper.ConceptMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 概念主题维表种子（concept）。
 * <p>concept 非东财接口直爬，而是由 board_basic 中 {@code board_type=3}(概念板块) 派生：
 * theme_code = board_code, theme_name = board_name, theme_type = 概念。</p>
 * <p>scarcity / imagination(S7 炒作因子 0~1) 暂置 null，后续由 S7 启发式填充；
 * 仅填充静态属性即可支撑主线识别与题材分类。</p>
 */
@Slf4j
@Service
public class ConceptSeeder {

    private final BoardBasicMapper boardBasicMapper;
    private final ConceptMapper conceptMapper;

    public ConceptSeeder(BoardBasicMapper boardBasicMapper, ConceptMapper conceptMapper) {
        this.boardBasicMapper = boardBasicMapper;
        this.conceptMapper = conceptMapper;
    }

    /** 从 board_basic 派生 concept 维表，返回写入条数。 */
    public int seedFromBoardBasic(int source) {
        List<BoardBasic> boards = boardBasicMapper.selectList(null);
        LocalDate today = LocalDate.now();
        LocalDateTime now = DateTimeUtil.nowSeconds();
        List<Concept> batch = new ArrayList<>();
        int skipped = 0;
        for (BoardBasic b : boards) {
            if (b.getBoardType() == null || b.getBoardType() != 3) {
                continue; // 仅概念板块
            }
            if (b.getBoardCode() == null) {
                skipped++;
                continue;
            }
            Concept c = new Concept();
            c.setThemeCode(b.getBoardCode());
            c.setThemeName(b.getBoardName());
            c.setThemeType("概念");
            c.setScarcity(null);     // TODO S7 启发式填充
            c.setImagination(null);  // TODO S7 启发式填充
            c.setDataSource(source);
            c.setSrcDetail("board_basic@" + b.getDataSource());
            c.setCreateDate(today);
            c.setUpdateDate(now);
            batch.add(c);
        }
        if (!batch.isEmpty()) {
            conceptMapper.batchInsert(batch);
        }
        log.info("ConceptSeeder.seedFromBoardBasic: source={}, totalBoards={}, conceptRows={}, skipped={}",
                source, boards.size(), batch.size(), skipped);
        return batch.size();
    }
}
