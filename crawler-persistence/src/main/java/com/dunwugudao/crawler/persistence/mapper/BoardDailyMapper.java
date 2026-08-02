package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.BoardDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 板块日线 Mapper。
 * <p>主键 (board_code, trade_date)，openGauss 兼容（无 ON CONFLICT）。</p>
 */
@Mapper
public interface BoardDailyMapper extends BaseMapper<BoardDaily> {

    @Select("SELECT data_source FROM board_daily WHERE board_code = #{boardCode} AND trade_date = #{tradeDate}")
    Integer selectDataSource(@Param("boardCode") String boardCode, @Param("tradeDate") LocalDate tradeDate);

    @Update("""
            UPDATE board_daily SET
              board_name = #{boardName}, board_type = #{boardType}, pct_chg = #{pctChg}, amount = #{amount},
              up_count = #{upCount}, down_count = #{downCount}, limit_up_count = #{limitUpCount},
              leading_code = #{leadingCode}, leading_name = #{leadingName}, main_net = #{mainNet},
              board_code2 = #{boardCode2}, data_source = #{dataSource}, src_detail = #{srcDetail},
              price = #{price}, rise_fall = #{riseFall}, volume = #{volume}, amplitude = #{amplitude},
              high_price = #{highPrice}, low_price = #{lowPrice}, today_open_price = #{todayOpenPrice},
              yesterday_received_price = #{yesterdayReceivedPrice}, volume_ratio = #{volumeRatio},
              turnover_ratio = #{turnoverRatio}, total_market_value = #{totalMarketValue},
              circulation_market_value = #{circulationMarketValue}, update_date = #{updateDate}
            WHERE board_code = #{boardCode} AND trade_date = #{tradeDate}
            """)
    int updateRow(BoardDaily row);

    @Insert("""
            INSERT INTO board_daily
              (trade_date, board_code, board_name, board_type, pct_chg, amount, up_count, down_count,
               limit_up_count, leading_code, leading_name, main_net, board_code2, data_source, src_detail,
               price, rise_fall, volume, amplitude, high_price, low_price, today_open_price,
               yesterday_received_price, volume_ratio, turnover_ratio, total_market_value,
               circulation_market_value, create_date, update_date)
            SELECT
              #{tradeDate}, #{boardCode}, #{boardName}, #{boardType}, #{pctChg}, #{amount}, #{upCount}, #{downCount},
               #{limitUpCount}, #{leadingCode}, #{leadingName}, #{mainNet}, #{boardCode2}, #{dataSource}, #{srcDetail},
               #{price}, #{riseFall}, #{volume}, #{amplitude}, #{highPrice}, #{lowPrice}, #{todayOpenPrice},
               #{yesterdayReceivedPrice}, #{volumeRatio}, #{turnoverRatio}, #{totalMarketValue},
               #{circulationMarketValue}, #{createDate}, #{updateDate}
            WHERE NOT EXISTS (
              SELECT 1 FROM board_daily WHERE board_code = #{boardCode} AND trade_date = #{tradeDate}
            )
            """)
    int insertIfAbsent(BoardDaily row);
}
