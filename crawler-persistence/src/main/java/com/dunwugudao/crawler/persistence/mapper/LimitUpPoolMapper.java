package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.LimitUpPool;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LimitUpPoolMapper extends BaseMapper<LimitUpPool> {

    @Select("SELECT data_source FROM limit_up_pool WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate} AND data_source = #{dataSource}")
    Integer selectDataSource(@Param("tsCode") String tsCode, @Param("tradeDate") java.time.LocalDate tradeDate, @Param("dataSource") int dataSource);

    @Update("""
            UPDATE limit_up_pool SET
              stock_name = #{stockName}, latest_price = #{latestPrice}, pct_chg = #{pctChg},
              board_pos = #{boardPos}, is_first = #{isFirst}, is_continuous = #{isContinuous},
              limit_style = #{limitStyle}, open_time = #{openTime}, last_time = #{lastTime},
              open_times = #{openTimes}, fund = #{fund}, amount = #{amount}, ltsz = #{ltsz},
              tshare = #{tshare}, turnover_rate = #{turnoverRate}, board_code = #{boardCode},
              zttj_ct = #{zttjCt}, zttj_days = #{zttjDays}, data_source = #{dataSource},
              src_detail = #{srcDetail}, update_date = #{updateDate}
            WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate} AND data_source = #{dataSource}
            """)
    int updateRow(LimitUpPool row);

    @Insert("""
            INSERT INTO limit_up_pool
              (trade_date, ts_code, stock_name, latest_price, pct_chg, board_pos, is_first, is_continuous,
               limit_style, open_time, last_time, open_times, fund, amount, ltsz, tshare,
               turnover_rate, board_code, zttj_ct, zttj_days, data_source, src_detail, create_date, update_date)
            SELECT
              #{tradeDate}, #{tsCode}, #{stockName}, #{latestPrice}, #{pctChg}, #{boardPos}, #{isFirst}, #{isContinuous},
               #{limitStyle}, #{openTime}, #{lastTime}, #{openTimes}, #{fund}, #{amount}, #{ltsz}, #{tshare},
               #{turnoverRate}, #{boardCode}, #{zttjCt}, #{zttjDays}, #{dataSource}, #{srcDetail}, #{createDate}, #{updateDate}
            WHERE NOT EXISTS (
              SELECT 1 FROM limit_up_pool WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate} AND data_source = #{dataSource}
            )
            """)
    int insertIfAbsent(LimitUpPool row);
}
