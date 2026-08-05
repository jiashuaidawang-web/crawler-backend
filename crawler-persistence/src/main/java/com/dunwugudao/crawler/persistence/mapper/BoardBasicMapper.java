package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.BoardBasic;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * board_basic Mapper。
 */
@Mapper
public interface BoardBasicMapper extends BaseMapper<BoardBasic> {

    /** board_basic 有但 stock_board_rel 没有的板块（新增板块）。 */
    @Select("""
            SELECT DISTINCT bb.board_code
            FROM board_basic bb
            LEFT JOIN (SELECT DISTINCT board_code FROM stock_board_rel) sr
              ON bb.board_code = sr.board_code
            WHERE sr.board_code IS NULL
            """)
    List<String> findBoardsNotInRel();
}
