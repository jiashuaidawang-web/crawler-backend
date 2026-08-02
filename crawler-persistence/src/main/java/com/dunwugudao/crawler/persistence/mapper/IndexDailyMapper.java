package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.IndexDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * 指数日线 Mapper。
 * <p>主键 (index_code, trade_date)，幂等 upsert。</p>
 */
@Mapper
public interface IndexDailyMapper extends BaseMapper<IndexDaily> {

    /**
     * 幂等写入：以 (index_code, trade_date) 为自然键，冲突时更新全部业务列并刷新 data_source / src_detail。
     */
    @Insert("""
            INSERT INTO index_daily
              (trade_date, index_code, index_name, open, high, low, close, pre_close, pct_chg,
               vol, amount, turnover, data_source, src_detail, create_date, update_date)
            VALUES
              (#{tradeDate}, #{indexCode}, #{indexName}, #{open}, #{high}, #{low}, #{close}, #{preClose}, #{pctChg},
               #{vol}, #{amount}, #{turnover}, #{dataSource}, #{srcDetail}, #{createDate}, #{updateDate})
            ON CONFLICT (index_code, trade_date) DO UPDATE SET
              index_name   = EXCLUDED.index_name,
              open         = EXCLUDED.open,
              high         = EXCLUDED.high,
              low          = EXCLUDED.low,
              close        = EXCLUDED.close,
              pre_close    = EXCLUDED.pre_close,
              pct_chg      = EXCLUDED.pct_chg,
              vol          = EXCLUDED.vol,
              amount       = EXCLUDED.amount,
              turnover     = EXCLUDED.turnover,
              data_source  = EXCLUDED.data_source,
              src_detail   = EXCLUDED.src_detail,
              update_date  = EXCLUDED.update_date
            """)
    int insertOrUpdate(IndexDaily row);

    /**
     * 读取某自然键已存在的 data_source，供优先级覆写裁决使用。
     * @return 已存在行优先级代码；无记录返回 null
     */
    @Select("SELECT data_source FROM index_daily WHERE index_code = #{indexCode} AND trade_date = #{tradeDate}")
    Integer selectDataSource(@Param("indexCode") String indexCode, @Param("tradeDate") LocalDate tradeDate);
}
