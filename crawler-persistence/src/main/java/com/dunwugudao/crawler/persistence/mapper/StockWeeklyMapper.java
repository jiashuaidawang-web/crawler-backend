package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.StockWeekly;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 个股周线 Mapper。
 * <p>主键 (ts_code, trade_date)，openGauss 兼容（无 ON CONFLICT）。</p>
 */
@Mapper
public interface StockWeeklyMapper extends BaseMapper<StockWeekly> {

    @Select("SELECT data_source FROM stock_weekly WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}")
    Integer selectDataSource(@Param("tsCode") String tsCode, @Param("tradeDate") LocalDate tradeDate);

    @Update("""
            UPDATE stock_weekly SET
              stock_name = #{stockName}, open = #{open}, high = #{high}, low = #{low}, close = #{close},
              vol = #{vol}, amount = #{amount}, data_source = #{dataSource}, src_detail = #{srcDetail},
              chg_amount = #{chgAmount}, amplitude = #{amplitude}, volume_ratio = #{volumeRatio},
              avg_price = #{avgPrice}, main_net = #{mainNet}, pe_static = #{peStatic},
              leader_code = #{leaderCode}, industry_code = #{industryCode}, concept_code = #{conceptCode},
              market_code = #{marketCode}, update_date = #{updateDate}
            WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}
            """)
    int updateRow(StockWeekly row);

    @Insert("""
            INSERT INTO stock_weekly
              (trade_date, ts_code, stock_name, open, high, low, close, vol, amount,
               data_source, src_detail, create_date, update_date,
               chg_amount, amplitude, volume_ratio, avg_price, main_net, pe_static,
               leader_code, industry_code, concept_code, market_code)
            SELECT
              #{tradeDate}, #{tsCode}, #{stockName}, #{open}, #{high}, #{low}, #{close}, #{vol}, #{amount},
               #{dataSource}, #{srcDetail}, #{createDate}, #{updateDate},
               #{chgAmount}, #{amplitude}, #{volumeRatio}, #{avgPrice}, #{mainNet}, #{peStatic},
               #{leaderCode}, #{industryCode}, #{conceptCode}, #{marketCode}
            WHERE NOT EXISTS (
              SELECT 1 FROM stock_weekly WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}
            )
            """)
    int insertIfAbsent(StockWeekly row);
}
