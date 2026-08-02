package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.BoardDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * 板块日线 Mapper。
 * <p>主键 (board_code, trade_date)，幂等 upsert。</p>
 */
@Mapper
public interface BoardDailyMapper extends BaseMapper<BoardDaily> {

    /**
     * 幂等写入：以 (board_code, trade_date) 为自然键，冲突时更新全部业务列并刷新 data_source / src_detail。
     */
    @Insert("""
            INSERT INTO board_daily
              (trade_date, board_code, board_name, board_type, pct_chg, amount, up_count, down_count,
               limit_up_count, leading_code, leading_name, main_net, board_code2, data_source, src_detail,
               price, rise_fall, volume, amplitude, high_price, low_price, today_open_price,
               yesterday_received_price, volume_ratio, turnover_ratio, total_market_value,
               circulation_market_value, create_date, update_date)
            VALUES
              (#{tradeDate}, #{boardCode}, #{boardName}, #{boardType}, #{pctChg}, #{amount}, #{upCount}, #{downCount},
               #{limitUpCount}, #{leadingCode}, #{leadingName}, #{mainNet}, #{boardCode2}, #{dataSource}, #{srcDetail},
               #{price}, #{riseFall}, #{volume}, #{amplitude}, #{highPrice}, #{lowPrice}, #{todayOpenPrice},
               #{yesterdayReceivedPrice}, #{volumeRatio}, #{turnoverRatio}, #{totalMarketValue},
               #{circulationMarketValue}, #{createDate}, #{updateDate})
            ON CONFLICT (board_code, trade_date) DO UPDATE SET
              board_name     = EXCLUDED.board_name,
              board_type     = EXCLUDED.board_type,
              pct_chg        = EXCLUDED.pct_chg,
              amount         = EXCLUDED.amount,
              up_count       = EXCLUDED.up_count,
              down_count     = EXCLUDED.down_count,
              limit_up_count = EXCLUDED.limit_up_count,
              leading_code   = EXCLUDED.leading_code,
              leading_name   = EXCLUDED.leading_name,
              main_net       = EXCLUDED.main_net,
              board_code2    = EXCLUDED.board_code2,
              data_source    = EXCLUDED.data_source,
              src_detail     = EXCLUDED.src_detail,
              price          = EXCLUDED.price,
              rise_fall      = EXCLUDED.rise_fall,
              volume         = EXCLUDED.volume,
              amplitude      = EXCLUDED.amplitude,
              high_price     = EXCLUDED.high_price,
              low_price      = EXCLUDED.low_price,
              today_open_price = EXCLUDED.today_open_price,
              yesterday_received_price = EXCLUDED.yesterday_received_price,
              volume_ratio   = EXCLUDED.volume_ratio,
              turnover_ratio = EXCLUDED.turnover_ratio,
              total_market_value = EXCLUDED.total_market_value,
              circulation_market_value = EXCLUDED.circulation_market_value,
              update_date    = EXCLUDED.update_date
            """)
    int insertOrUpdate(BoardDaily row);

    /**
     * 读取某自然键已存在的 data_source，供优先级覆写裁决使用。
     * @return 已存在行优先级代码；无记录返回 null
     */
    @Select("SELECT data_source FROM board_daily WHERE board_code = #{boardCode} AND trade_date = #{tradeDate}")
    Integer selectDataSource(@Param("boardCode") String boardCode, @Param("tradeDate") LocalDate tradeDate);
}
