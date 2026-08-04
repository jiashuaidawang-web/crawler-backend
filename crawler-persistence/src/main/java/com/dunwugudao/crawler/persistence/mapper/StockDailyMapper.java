package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.StockDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 个股日线 Mapper。
 * <p>主键 (ts_code, trade_date)，openGauss 兼容（无 ON CONFLICT）。</p>
 */
@Mapper
public interface StockDailyMapper extends BaseMapper<StockDaily> {

    @Select("SELECT data_source FROM stock_daily WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}")
    Integer selectDataSource(@Param("tsCode") String tsCode, @Param("tradeDate") LocalDate tradeDate);

    @Update("""
            UPDATE stock_daily SET
              stock_name = #{stockName}, open = #{open}, high = #{high}, low = #{low}, close = #{close},
              pre_close = #{preClose}, pct_chg = #{pctChg}, vol = #{vol}, amount = #{amount},
              turnover = #{turnover}, total_mv = #{totalMv}, circ_mv = #{circMv}, pe = #{pe},
              is_limit_up = #{isLimitUp}, is_limit_down = #{isLimitDown}, data_source = #{dataSource},
              src_detail = #{srcDetail}, chg_amount = #{chgAmount}, amplitude = #{amplitude},
              volume_ratio = #{volumeRatio}, chg_60d = #{chg60d}, market_code = #{marketCode},
              update_date = #{updateDate}
            WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}
            """)
    int updateRow(StockDaily row);

    @Insert("""
            INSERT INTO stock_daily
              (trade_date, ts_code, stock_name, open, high, low, close, pre_close, pct_chg, vol, amount,
               turnover, total_mv, circ_mv, pe, is_limit_up, is_limit_down, data_source, src_detail,
               create_date, update_date, chg_amount, amplitude, volume_ratio, avg_price, main_net,
               pe_static, leader_code, industry_code, concept_code, market_code,
               reserved_f24, reserved_f25, reserved_f107, reserved_f136, reserved_f173)
            SELECT
              #{tradeDate}, #{tsCode}, #{stockName}, #{open}, #{high}, #{low}, #{close}, #{preClose}, #{pctChg}, #{vol}, #{amount},
               #{turnover}, #{totalMv}, #{circMv}, #{pe}, #{isLimitUp}, #{isLimitDown}, #{dataSource}, #{srcDetail},
               #{createDate}, #{updateDate}, #{chgAmount}, #{amplitude}, #{volumeRatio}, #{avgPrice}, #{mainNet},
               #{peStatic}, #{leaderCode}, #{industryCode}, #{conceptCode}, #{marketCode},
               #{reservedF24}, #{reservedF25}, #{reservedF107}, #{reservedF136}, #{reservedF173}
            WHERE NOT EXISTS (
              SELECT 1 FROM stock_daily WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}
            )
            """)
    int insertIfAbsent(StockDaily row);
}
