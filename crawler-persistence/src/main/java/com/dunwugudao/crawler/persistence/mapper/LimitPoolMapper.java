package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.LimitPool;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * limit_pool（示例原始表）Mapper，演示幂等 upsert 与溯源列优先级读取。
 */
@Mapper
public interface LimitPoolMapper extends BaseMapper<LimitPool> {

    /**
     * 幂等写入：以 (ts_code, trade_date) 为自然键，冲突时更新全部业务列并刷新 data_source / src_detail。
     */
    @Insert("""
            INSERT INTO limit_pool
              (trade_date, ts_code, stock_name, limit_type, board_pos, is_first, is_continuous,
               limit_style, open_time, last_time, open_times, bid_amount, turnover, pct_chg,
               reason, board_code, board_name, data_source, src_detail)
            VALUES
              (#{tradeDate}, #{tsCode}, #{stockName}, #{limitType}, #{boardPos}, #{isFirst}, #{isContinuous},
               #{limitStyle}, #{openTime}, #{lastTime}, #{openTimes}, #{bidAmount}, #{turnover}, #{pctChg},
               #{reason}, #{boardCode}, #{boardName}, #{dataSource}, #{srcDetail})
            ON CONFLICT (ts_code, trade_date) DO UPDATE SET
              stock_name    = EXCLUDED.stock_name,
              limit_type    = EXCLUDED.limit_type,
              board_pos     = EXCLUDED.board_pos,
              is_first      = EXCLUDED.is_first,
              is_continuous = EXCLUDED.is_continuous,
              limit_style   = EXCLUDED.limit_style,
              open_time     = EXCLUDED.open_time,
              last_time     = EXCLUDED.last_time,
              open_times    = EXCLUDED.open_times,
              bid_amount    = EXCLUDED.bid_amount,
              turnover      = EXCLUDED.turnover,
              pct_chg       = EXCLUDED.pct_chg,
              reason        = EXCLUDED.reason,
              board_code    = EXCLUDED.board_code,
              board_name    = EXCLUDED.board_name,
              data_source   = EXCLUDED.data_source,
              src_detail    = EXCLUDED.src_detail
            """)
    int insertOrUpdate(LimitPool row);

    /**
     * 读取某自然键已存在的 data_source，供优先级覆写裁决使用。
     * @return 已存在行优先级代码；无记录返回 null
     */
    @Select("SELECT data_source FROM limit_pool WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}")
    Integer selectDataSource(@Param("tsCode") String tsCode, @Param("tradeDate") LocalDate tradeDate);
}
