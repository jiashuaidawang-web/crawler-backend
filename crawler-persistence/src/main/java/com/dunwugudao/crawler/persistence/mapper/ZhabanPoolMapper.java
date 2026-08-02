package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.ZhabanPool;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ZhabanPoolMapper extends BaseMapper<ZhabanPool> {

    @Select("SELECT data_source FROM zhaban_pool WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}")
    Integer selectDataSource(@Param("tsCode") String tsCode, @Param("tradeDate") java.time.LocalDate tradeDate);

    @Update("""
            UPDATE zhaban_pool SET
              stock_name = #{stockName}, latest_price = #{latestPrice}, pct_chg = #{pctChg},
              ztp = #{ztp}, zf = #{zf}, zs = #{zs}, open_time = #{openTime}, open_times = #{openTimes},
              amount = #{amount}, ltsz = #{ltsz}, tshare = #{tshare}, turnover_rate = #{turnoverRate},
              board_code = #{boardCode}, zttj_ct = #{zttjCt}, zttj_days = #{zttjDays},
              data_source = #{dataSource}, src_detail = #{srcDetail}, update_date = #{updateDate}
            WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}
            """)
    int updateRow(ZhabanPool row);

    @Insert("""
            INSERT INTO zhaban_pool
              (trade_date, ts_code, stock_name, latest_price, pct_chg, ztp, zf, zs, open_time, open_times,
               amount, ltsz, tshare, turnover_rate, board_code, zttj_ct, zttj_days, data_source, src_detail,
               create_date, update_date)
            SELECT
              #{tradeDate}, #{tsCode}, #{stockName}, #{latestPrice}, #{pctChg}, #{ztp}, #{zf}, #{zs}, #{openTime}, #{openTimes},
               #{amount}, #{ltsz}, #{tshare}, #{turnoverRate}, #{boardCode}, #{zttjCt}, #{zttjDays}, #{dataSource}, #{srcDetail},
               #{createDate}, #{updateDate}
            WHERE NOT EXISTS (
              SELECT 1 FROM zhaban_pool WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate} AND data_source = #{dataSource}
            )
            """)
    int insertIfAbsent(ZhabanPool row);
}
