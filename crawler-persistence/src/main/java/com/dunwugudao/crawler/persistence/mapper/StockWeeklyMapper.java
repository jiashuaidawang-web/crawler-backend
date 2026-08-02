package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.StockWeekly;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * 个股周线 Mapper。
 * <p>主键 (ts_code, trade_date)，幂等 upsert。</p>
 */
@Mapper
public interface StockWeeklyMapper extends BaseMapper<StockWeekly> {

    /**
     * 幂等写入：以 (ts_code, trade_date) 为自然键，冲突时更新全部业务列并刷新 data_source / src_detail。
     */
    @Insert("""
            INSERT INTO stock_weekly
              (trade_date, ts_code, stock_name, open, high, low, close, vol, amount,
               data_source, src_detail, create_date, update_date,
               chg_amount, amplitude, volume_ratio, avg_price, main_net, pe_static,
               leader_code, industry_code, concept_code, market_code)
            VALUES
              (#{tradeDate}, #{tsCode}, #{stockName}, #{open}, #{high}, #{low}, #{close}, #{vol}, #{amount},
               #{dataSource}, #{srcDetail}, #{createDate}, #{updateDate},
               #{chgAmount}, #{amplitude}, #{volumeRatio}, #{avgPrice}, #{mainNet}, #{peStatic},
               #{leaderCode}, #{industryCode}, #{conceptCode}, #{marketCode})
            ON CONFLICT (ts_code, trade_date) DO UPDATE SET
              stock_name    = EXCLUDED.stock_name,
              open          = EXCLUDED.open,
              high          = EXCLUDED.high,
              low           = EXCLUDED.low,
              close         = EXCLUDED.close,
              vol           = EXCLUDED.vol,
              amount        = EXCLUDED.amount,
              data_source   = EXCLUDED.data_source,
              src_detail    = EXCLUDED.src_detail,
              chg_amount    = EXCLUDED.chg_amount,
              amplitude     = EXCLUDED.amplitude,
              volume_ratio  = EXCLUDED.volume_ratio,
              avg_price     = EXCLUDED.avg_price,
              main_net      = EXCLUDED.main_net,
              pe_static     = EXCLUDED.pe_static,
              leader_code   = EXCLUDED.leader_code,
              industry_code = EXCLUDED.industry_code,
              concept_code  = EXCLUDED.concept_code,
              market_code   = EXCLUDED.market_code,
              update_date   = EXCLUDED.update_date
            """)
    int insertOrUpdate(StockWeekly row);

    /**
     * 读取某自然键已存在的 data_source，供优先级覆写裁决使用。
     * @return 已存在行优先级代码；无记录返回 null
     */
    @Select("SELECT data_source FROM stock_weekly WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}")
    Integer selectDataSource(@Param("tsCode") String tsCode, @Param("tradeDate") LocalDate tradeDate);
}
