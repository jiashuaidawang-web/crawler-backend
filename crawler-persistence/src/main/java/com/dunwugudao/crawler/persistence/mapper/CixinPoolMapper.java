package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.CixinPool;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CixinPoolMapper extends BaseMapper<CixinPool> {

    @Select("SELECT data_source FROM cixin_pool WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate} AND data_source = #{dataSource}")
    Integer selectDataSource(@Param("tsCode") String tsCode, @Param("tradeDate") java.time.LocalDate tradeDate, @Param("dataSource") int dataSource);

    @Update("""
            UPDATE cixin_pool SET
              stock_name = #{stockName}, latest_price = #{latestPrice}, pct_chg = #{pctChg},
              ztp = #{ztp}, ods = #{ods}, od = #{od}, ipod = #{ipod}, o = #{o}, nh = #{nh},
              amount = #{amount}, ltsz = #{ltsz}, tshare = #{tshare}, turnover_rate = #{turnoverRate},
              board_code = #{boardCode}, zttj_ct = #{zttjCt}, zttj_days = #{zttjDays},
              data_source = #{dataSource}, src_detail = #{srcDetail}, update_date = #{updateDate}
            WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate} AND data_source = #{dataSource}
            """)
    int updateRow(CixinPool row);

    @Insert("""
            INSERT INTO cixin_pool
              (trade_date, ts_code, stock_name, latest_price, pct_chg, ztp, ods, od, ipod, o, nh,
               amount, ltsz, tshare, turnover_rate, board_code, zttj_ct, zttj_days, data_source, src_detail,
               create_date, update_date)
            SELECT
              #{tradeDate}, #{tsCode}, #{stockName}, #{latestPrice}, #{pctChg}, #{ztp}, #{ods}, #{od}, #{ipod}, #{o}, #{nh},
               #{amount}, #{ltsz}, #{tshare}, #{turnoverRate}, #{boardCode}, #{zttjCt}, #{zttjDays}, #{dataSource}, #{srcDetail},
               #{createDate}, #{updateDate}
            WHERE NOT EXISTS (
              SELECT 1 FROM cixin_pool WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate} AND data_source = #{dataSource}
            )
            """)
    int insertIfAbsent(CixinPool row);
}
