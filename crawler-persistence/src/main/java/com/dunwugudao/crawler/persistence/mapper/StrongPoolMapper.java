package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.StrongPool;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface StrongPoolMapper extends BaseMapper<StrongPool> {

    @Select("SELECT data_source FROM strong_pool WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}")
    Integer selectDataSource(@Param("tsCode") String tsCode, @Param("tradeDate") java.time.LocalDate tradeDate);

    @Update("""
            UPDATE strong_pool SET
              stock_name = #{stockName}, latest_price = #{latestPrice}, pct_chg = #{pctChg},
              ztp = #{ztp}, zs = #{zs}, nh = #{nh}, board_pos = #{boardPos}, lb = #{lb},
              amount = #{amount}, ltsz = #{ltsz}, tshare = #{tshare}, turnover_rate = #{turnoverRate},
              board_code = #{boardCode}, zttj_ct = #{zttjCt}, zttj_days = #{zttjDays},
              data_source = #{dataSource}, src_detail = #{srcDetail}, update_date = #{updateDate}
            WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}
            """)
    int updateRow(StrongPool row);

    @Insert("""
            INSERT INTO strong_pool
              (trade_date, ts_code, stock_name, latest_price, pct_chg, ztp, zs, nh, board_pos, lb,
               amount, ltsz, tshare, turnover_rate, board_code, zttj_ct, zttj_days, data_source, src_detail,
               create_date, update_date)
            SELECT
              #{tradeDate}, #{tsCode}, #{stockName}, #{latestPrice}, #{pctChg}, #{ztp}, #{zs}, #{nh}, #{boardPos}, #{lb},
               #{amount}, #{ltsz}, #{tshare}, #{turnoverRate}, #{boardCode}, #{zttjCt}, #{zttjDays}, #{dataSource}, #{srcDetail},
               #{createDate}, #{updateDate}
            WHERE NOT EXISTS (
              SELECT 1 FROM strong_pool WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate} AND data_source = #{dataSource}
            )
            """)
    int insertIfAbsent(StrongPool row);
}
