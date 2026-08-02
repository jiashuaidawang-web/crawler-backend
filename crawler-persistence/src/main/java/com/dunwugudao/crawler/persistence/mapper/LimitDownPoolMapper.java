package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.LimitDownPool;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LimitDownPoolMapper extends BaseMapper<LimitDownPool> {

    @Select("SELECT data_source FROM limit_down_pool WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}")
    Integer selectDataSource(@Param("tsCode") String tsCode, @Param("tradeDate") java.time.LocalDate tradeDate);

    @Update("""
            UPDATE limit_down_pool SET
              stock_name = #{stockName}, latest_price = #{latestPrice}, pct_chg = #{pctChg},
              pe = #{pe}, fund = #{fund}, last_time = #{lastTime}, fba = #{fba},
              days = #{days}, oc = #{oc}, amount = #{amount}, ltsz = #{ltsz}, tshare = #{tshare},
              turnover_rate = #{turnoverRate}, board_code = #{boardCode}, data_source = #{dataSource},
              src_detail = #{srcDetail}, update_date = #{updateDate}
            WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}
            """)
    int updateRow(LimitDownPool row);

    @Insert("""
            INSERT INTO limit_down_pool
              (trade_date, ts_code, stock_name, latest_price, pct_chg, pe, fund, last_time, fba,
               days, oc, amount, ltsz, tshare, turnover_rate, board_code, data_source, src_detail,
               create_date, update_date)
            SELECT
              #{tradeDate}, #{tsCode}, #{stockName}, #{latestPrice}, #{pctChg}, #{pe}, #{fund}, #{lastTime}, #{fba},
               #{days}, #{oc}, #{amount}, #{ltsz}, #{tshare}, #{turnoverRate}, #{boardCode}, #{dataSource}, #{srcDetail},
               #{createDate}, #{updateDate}
            WHERE NOT EXISTS (
              SELECT 1 FROM limit_down_pool WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate} AND data_source = #{dataSource}
            )
            """)
    int insertIfAbsent(LimitDownPool row);
}
