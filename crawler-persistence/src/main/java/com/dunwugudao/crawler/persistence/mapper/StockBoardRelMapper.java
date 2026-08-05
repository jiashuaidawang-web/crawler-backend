package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.StockBoardRel;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 板块-个股关联关系 Mapper。
 * <p>主键 (board_code, ts_code, board_type, data_source)，openGauss 兼容（无 ON CONFLICT）。</p>
 */
@Mapper
public interface StockBoardRelMapper extends BaseMapper<StockBoardRel> {

    @Select("""
            SELECT MIN(data_source) FROM stock_board_rel
            WHERE board_code = #{boardCode} AND ts_code = #{tsCode} AND board_type = #{boardType}
            """)
    Integer selectDataSource(@Param("boardCode") String boardCode,
                             @Param("tsCode") String tsCode,
                             @Param("boardType") Integer boardType);

    @Update("""
            UPDATE stock_board_rel SET
              board_name     = #{boardName},
              stock_name     = #{stockName},
              is_leader      = #{isLeader},
              is_midarm      = #{isMidarm},
              weight         = #{weight},
              effective_date = #{effectiveDate},
              data_source    = #{dataSource},
              src_detail     = #{srcDetail},
              update_date    = #{updateDate}
            WHERE board_code = #{boardCode} AND ts_code = #{tsCode}
              AND board_type = #{boardType} AND data_source = #{dataSource}
            """)
    int updateRow(StockBoardRel row);

    @Insert("""
            INSERT INTO stock_board_rel
              (ts_code, board_code, board_name, stock_name, board_type, is_leader, is_midarm, weight,
               effective_date, data_source, src_detail, create_date, update_date)
            VALUES
              (#{tsCode}, #{boardCode}, #{boardName}, #{stockName}, #{boardType}, #{isLeader}, #{isMidarm}, #{weight},
               #{effectiveDate}, #{dataSource}, #{srcDetail}, #{createDate}, #{updateDate})
            """)
    int insertIfAbsent(StockBoardRel row);

    /** 已入库的板块数（去重）。 */
    @Select("SELECT COUNT(DISTINCT board_code) FROM stock_board_rel")
    long countDistinctBoards();

    /** 某板块在库里的股票数。 */
    @Select("SELECT COUNT(*) FROM stock_board_rel WHERE board_code = #{boardCode}")
    long countByBoardCode(@Param("boardCode") String boardCode);
}
