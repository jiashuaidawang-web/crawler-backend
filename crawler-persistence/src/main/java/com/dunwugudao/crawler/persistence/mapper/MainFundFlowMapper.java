package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.MainFundFlow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 主力资金流 Mapper。
 * <p>主键 (obj_type, ts_code, board_code, index_code, trade_date)，openGauss 兼容。</p>
 */
@Mapper
public interface MainFundFlowMapper extends BaseMapper<MainFundFlow> {

    @Select("""
            SELECT MIN(data_source) FROM main_fund_flow
            WHERE obj_type = #{objType} AND ts_code = #{tsCode} AND board_code = #{boardCode}
              AND index_code = #{indexCode} AND trade_date = #{tradeDate}
            """)
    Integer selectDataSource(@Param("objType") String objType, @Param("tsCode") String tsCode,
                             @Param("boardCode") String boardCode, @Param("indexCode") String indexCode,
                             @Param("tradeDate") LocalDate tradeDate);

    @Update("""
            UPDATE main_fund_flow SET
              main_net = #{mainNet}, super_big = #{superBig}, big_net = #{bigNet}, mid_net = #{midNet},
              small_net = #{smallNet}, data_source = #{dataSource}, src_detail = #{srcDetail},
              update_date = #{updateDate}
            WHERE obj_type = #{objType} AND ts_code = #{tsCode} AND board_code = #{boardCode}
              AND index_code = #{indexCode} AND trade_date = #{tradeDate}
            """)
    int updateRow(MainFundFlow row);

    @Insert("""
            INSERT INTO main_fund_flow
              (trade_date, obj_type, ts_code, board_code, index_code,
               main_net, super_big, big_net, mid_net, small_net,
               data_source, src_detail, create_date, update_date)
            SELECT
              #{tradeDate}, #{objType}, #{tsCode}, #{boardCode}, #{indexCode},
               #{mainNet}, #{superBig}, #{bigNet}, #{midNet}, #{smallNet},
               #{dataSource}, #{srcDetail}, #{createDate}, #{updateDate}
            WHERE NOT EXISTS (
              SELECT 1 FROM main_fund_flow
              WHERE obj_type = #{objType} AND ts_code = #{tsCode} AND board_code = #{boardCode}
                AND index_code = #{indexCode} AND trade_date = #{tradeDate}
            )
            """)
    int insertIfAbsent(MainFundFlow row);
}
