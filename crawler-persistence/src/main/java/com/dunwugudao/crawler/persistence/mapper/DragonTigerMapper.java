package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.DragonTiger;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 龙虎榜 Mapper。
 * <p>主键 (ts_code, trade_date)，幂等 upsert。</p>
 */
@Mapper
public interface DragonTigerMapper extends BaseMapper<DragonTiger> {

    /**
     * 幂等写入：以 (ts_code, trade_date) 为自然键，冲突时更新全部业务列并刷新 data_source / src_detail。
     */
    @Insert("""
            INSERT INTO dragon_tiger
              (trade_date, ts_code, stock_name, reason, explanation, abnormal_type,
               net_buy, total_buy, total_sell, data_source, src_detail, create_date, update_date,
               billboard_deal_amt, accum_amount, buy_ratio, sell_ratio,
               buy_seat, sell_seat, buy_seat_new, sell_seat_new,
               change_rate, close_price, turnoverrate, free_market_cap, market,
               deal_amount_ratio, deal_net_ratio, security_inner_code, security_type_code,
               trade_id, trade_market, trade_market_code)
            VALUES
              (#{tradeDate}, #{tsCode}, #{stockName}, #{reason}, #{explanation}, #{abnormalType},
               #{netBuy}, #{totalBuy}, #{totalSell}, #{dataSource}, #{srcDetail}, #{createDate}, #{updateDate},
               #{billboardDealAmt}, #{accumAmount}, #{buyRatio}, #{sellRatio},
               #{buySeat}, #{sellSeat}, #{buySeatNew}, #{sellSeatNew},
               #{changeRate}, #{closePrice}, #{turnoverrate}, #{freeMarketCap}, #{market},
               #{dealAmountRatio}, #{dealNetRatio}, #{securityInnerCode}, #{securityTypeCode},
               #{tradeId}, #{tradeMarket}, #{tradeMarketCode})
            ON CONFLICT (ts_code, trade_date) DO UPDATE SET
              stock_name         = EXCLUDED.stock_name,
              reason             = EXCLUDED.reason,
              explanation        = EXCLUDED.explanation,
              abnormal_type      = EXCLUDED.abnormal_type,
              net_buy            = EXCLUDED.net_buy,
              total_buy          = EXCLUDED.total_buy,
              total_sell         = EXCLUDED.total_sell,
              data_source        = EXCLUDED.data_source,
              src_detail         = EXCLUDED.src_detail,
              billboard_deal_amt = EXCLUDED.billboard_deal_amt,
              accum_amount       = EXCLUDED.accum_amount,
              buy_ratio          = EXCLUDED.buy_ratio,
              sell_ratio         = EXCLUDED.sell_ratio,
              buy_seat           = EXCLUDED.buy_seat,
              sell_seat          = EXCLUDED.sell_seat,
              buy_seat_new       = EXCLUDED.buy_seat_new,
              sell_seat_new      = EXCLUDED.sell_seat_new,
              change_rate        = EXCLUDED.change_rate,
              close_price        = EXCLUDED.close_price,
              turnoverrate       = EXCLUDED.turnoverrate,
              free_market_cap    = EXCLUDED.free_market_cap,
              market             = EXCLUDED.market,
              deal_amount_ratio  = EXCLUDED.deal_amount_ratio,
              deal_net_ratio     = EXCLUDED.deal_net_ratio,
              security_inner_code = EXCLUDED.security_inner_code,
              security_type_code = EXCLUDED.security_type_code,
              trade_id           = EXCLUDED.trade_id,
              trade_market       = EXCLUDED.trade_market,
              trade_market_code  = EXCLUDED.trade_market_code,
              update_date        = EXCLUDED.update_date
            """)
    int insertOrUpdate(DragonTiger row);

    /**
     * 读取某自然键已存在的 data_source，供优先级覆写裁决使用。
     * @return 已存在行优先级代码；无记录返回 null
     */
    @Select("SELECT data_source FROM dragon_tiger WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}")
    Integer selectDataSource(@Param("tsCode") String tsCode, @Param("tradeDate") LocalDate tradeDate);

    /**
     * 读取某交易日已存在的全部上榜股票代码（去重），供 DRAGON_TIGER_DETAIL 自动串联。
     * @return 代码列表；无记录返回空列表
     */
    @Select("SELECT DISTINCT ts_code FROM dragon_tiger WHERE trade_date = #{tradeDate} AND ts_code IS NOT NULL")
    List<String> selectDistinctCodes(@Param("tradeDate") LocalDate tradeDate);
}
