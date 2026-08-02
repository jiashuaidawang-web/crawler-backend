package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.StockDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 个股日线 Mapper。
 * <p>主键 (ts_code, trade_date)，幂等 upsert。</p>
 */
@Mapper
public interface StockDailyMapper extends BaseMapper<StockDaily> {

    /**
     * 幂等写入：以 (ts_code, trade_date) 为自然键，冲突时更新全部业务列并刷新 data_source / src_detail。
     */
    @Insert("""
            INSERT INTO stock_daily
              (trade_date, ts_code, stock_name, open, high, low, close, pre_close, pct_chg, vol, amount,
               turnover, total_mv, circ_mv, pe, is_limit_up, is_limit_down, data_source, src_detail,
               create_date, update_date, chg_amount, amplitude, volume_ratio, avg_price, main_net,
               pe_static, leader_code, industry_code, concept_code, market_code,
               reserved_f24, reserved_f25, reserved_f107, reserved_f136, reserved_f173)
            VALUES
              (#{tradeDate}, #{tsCode}, #{stockName}, #{open}, #{high}, #{low}, #{close}, #{preClose}, #{pctChg}, #{vol}, #{amount},
               #{turnover}, #{totalMv}, #{circMv}, #{pe}, #{isLimitUp}, #{isLimitDown}, #{dataSource}, #{srcDetail},
               #{createDate}, #{updateDate}, #{chgAmount}, #{amplitude}, #{volumeRatio}, #{avgPrice}, #{mainNet},
               #{peStatic}, #{leaderCode}, #{industryCode}, #{conceptCode}, #{marketCode},
               #{reservedF24}, #{reservedF25}, #{reservedF107}, #{reservedF136}, #{reservedF173})
            ON CONFLICT (ts_code, trade_date) DO UPDATE SET
              stock_name     = EXCLUDED.stock_name,
              open           = EXCLUDED.open,
              high           = EXCLUDED.high,
              low            = EXCLUDED.low,
              close          = EXCLUDED.close,
              pre_close      = EXCLUDED.pre_close,
              pct_chg        = EXCLUDED.pct_chg,
              vol            = EXCLUDED.vol,
              amount         = EXCLUDED.amount,
              turnover       = EXCLUDED.turnover,
              total_mv       = EXCLUDED.total_mv,
              circ_mv        = EXCLUDED.circ_mv,
              pe             = EXCLUDED.pe,
              is_limit_up    = EXCLUDED.is_limit_up,
              is_limit_down  = EXCLUDED.is_limit_down,
              data_source    = EXCLUDED.data_source,
              src_detail     = EXCLUDED.src_detail,
              chg_amount     = EXCLUDED.chg_amount,
              amplitude      = EXCLUDED.amplitude,
              volume_ratio   = EXCLUDED.volume_ratio,
              avg_price      = EXCLUDED.avg_price,
              main_net       = EXCLUDED.main_net,
              pe_static      = EXCLUDED.pe_static,
              leader_code    = EXCLUDED.leader_code,
              industry_code  = EXCLUDED.industry_code,
              concept_code   = EXCLUDED.concept_code,
              market_code    = EXCLUDED.market_code,
              reserved_f24   = EXCLUDED.reserved_f24,
              reserved_f25   = EXCLUDED.reserved_f25,
              reserved_f107  = EXCLUDED.reserved_f107,
              reserved_f136  = EXCLUDED.reserved_f136,
              reserved_f173  = EXCLUDED.reserved_f173,
              update_date    = EXCLUDED.update_date
            """)
    int insertOrUpdate(StockDaily row);

    /**
     * 读取某自然键已存在的 data_source，供优先级覆写裁决使用。
     * @return 已存在行优先级代码；无记录返回 null
     */
    @Select("SELECT data_source FROM stock_daily WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}")
    Integer selectDataSource(@Param("tsCode") String tsCode, @Param("tradeDate") java.time.LocalDate tradeDate);
}
