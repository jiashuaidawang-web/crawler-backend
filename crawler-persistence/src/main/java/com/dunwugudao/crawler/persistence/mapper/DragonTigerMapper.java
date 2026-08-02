package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.DragonTiger;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

/**
 * 龙虎榜 Mapper。
 * <p>主键 (ts_code, trade_date)，openGauss 兼容（无 ON CONFLICT）。</p>
 */
@Mapper
public interface DragonTigerMapper extends BaseMapper<DragonTiger> {

    @Select("SELECT data_source FROM dragon_tiger WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}")
    Integer selectDataSource(@Param("tsCode") String tsCode, @Param("tradeDate") LocalDate tradeDate);

    @Select("SELECT DISTINCT ts_code FROM dragon_tiger WHERE trade_date = #{tradeDate} AND ts_code IS NOT NULL")
    List<String> selectDistinctCodes(@Param("tradeDate") LocalDate tradeDate);

    @Update("""
            UPDATE dragon_tiger SET
              stock_name = #{stockName}, reason = #{reason}, explanation = #{explanation},
              abnormal_type = #{abnormalType}, net_buy = #{netBuy}, total_buy = #{totalBuy},
              total_sell = #{totalSell}, data_source = #{dataSource}, src_detail = #{srcDetail},
              billboard_deal_amt = #{billboardDealAmt}, accum_amount = #{accumAmount},
              buy_ratio = #{buyRatio}, sell_ratio = #{sellRatio}, buy_seat = #{buySeat},
              sell_seat = #{sellSeat}, buy_seat_new = #{buySeatNew}, sell_seat_new = #{sellSeatNew},
              change_rate = #{changeRate}, close_price = #{closePrice}, turnoverrate = #{turnoverrate},
              free_market_cap = #{freeMarketCap}, market = #{market},
              deal_amount_ratio = #{dealAmountRatio}, deal_net_ratio = #{dealNetRatio},
              security_inner_code = #{securityInnerCode}, security_type_code = #{securityTypeCode},
              trade_id = #{tradeId}, trade_market = #{tradeMarket},
              trade_market_code = #{tradeMarketCode}, update_date = #{updateDate}
            WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}
            """)
    int updateRow(DragonTiger row);

    @Insert("""
            INSERT INTO dragon_tiger
              (trade_date, ts_code, stock_name, reason, explanation, abnormal_type,
               net_buy, total_buy, total_sell, data_source, src_detail, create_date, update_date,
               billboard_deal_amt, accum_amount, buy_ratio, sell_ratio,
               buy_seat, sell_seat, buy_seat_new, sell_seat_new,
               change_rate, close_price, turnoverrate, free_market_cap, market,
               deal_amount_ratio, deal_net_ratio, security_inner_code, security_type_code,
               trade_id, trade_market, trade_market_code)
            SELECT
              #{tradeDate}, #{tsCode}, #{stockName}, #{reason}, #{explanation}, #{abnormalType},
               #{netBuy}, #{totalBuy}, #{totalSell}, #{dataSource}, #{srcDetail}, #{createDate}, #{updateDate},
               #{billboardDealAmt}, #{accumAmount}, #{buyRatio}, #{sellRatio},
               #{buySeat}, #{sellSeat}, #{buySeatNew}, #{sellSeatNew},
               #{changeRate}, #{closePrice}, #{turnoverrate}, #{freeMarketCap}, #{market},
               #{dealAmountRatio}, #{dealNetRatio}, #{securityInnerCode}, #{securityTypeCode},
               #{tradeId}, #{tradeMarket}, #{tradeMarketCode}
            WHERE NOT EXISTS (
              SELECT 1 FROM dragon_tiger WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}
            )
            """)
    int insertIfAbsent(DragonTiger row);
}
