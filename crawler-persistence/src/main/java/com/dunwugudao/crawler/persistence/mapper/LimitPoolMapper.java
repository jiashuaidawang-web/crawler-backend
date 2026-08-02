package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.LimitPool;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * limit_pool（示例原始表）Mapper。
 * <p>openGauss 不支持 ON CONFLICT，改用事务内 select + update/insert 实现幂等。</p>
 */
@Mapper
public interface LimitPoolMapper extends BaseMapper<LimitPool> {

    /**
     * 幂等写入（openGauss 兼容）：已存在且优先级 >= 新来源 → 不覆盖；否则更新或插入。
     * <p>外部调用方需加 @Transactional。</p>
     */
    @Select("SELECT data_source FROM limit_pool WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}")
    Integer selectDataSource(@Param("tsCode") String tsCode, @Param("tradeDate") LocalDate tradeDate);

    @Update("""
            UPDATE limit_pool SET
              stock_name = #{stockName}, type = #{type}, board_pos = #{boardPos}, is_first = #{isFirst},
              is_continuous = #{isContinuous}, limit_style = #{limitStyle}, open_time = #{openTime},
              last_time = #{lastTime}, open_times = #{openTimes}, bid_amount = #{bidAmount},
              turnover = #{turnover}, pct_chg = #{pctChg}, reason = #{reason}, board_code = #{boardCode},
              board_name = #{boardName}, data_source = #{dataSource}, src_detail = #{srcDetail}
            WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}
            """)
    int updateRow(LimitPool row);

    @Insert("""
            INSERT INTO limit_pool
              (trade_date, ts_code, stock_name, type, board_pos, is_first, is_continuous,
               limit_style, open_time, last_time, open_times, bid_amount, turnover, pct_chg,
               reason, board_code, board_name, data_source, src_detail)
            SELECT
              #{tradeDate}, #{tsCode}, #{stockName}, #{type}, #{boardPos}, #{isFirst}, #{isContinuous},
               #{limitStyle}, #{openTime}, #{lastTime}, #{openTimes}, #{bidAmount}, #{turnover}, #{pctChg},
               #{reason}, #{boardCode}, #{boardName}, #{dataSource}, #{srcDetail}
            WHERE NOT EXISTS (
              SELECT 1 FROM limit_pool WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate}
            )
            """)
    int insertIfAbsent(LimitPool row);
}
