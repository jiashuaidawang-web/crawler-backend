package com.dunwugudao.crawler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dunwugudao.crawler.persistence.entity.IndexDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 指数日线 Mapper。
 * <p>主键 (index_code, trade_date)，openGauss 兼容（无 ON CONFLICT）。</p>
 */
@Mapper
public interface IndexDailyMapper extends BaseMapper<IndexDaily> {

    @Select("SELECT data_source FROM index_daily WHERE index_code = #{indexCode} AND trade_date = #{tradeDate}")
    Integer selectDataSource(@Param("indexCode") String indexCode, @Param("tradeDate") LocalDate tradeDate);

    @Update("""
            UPDATE index_daily SET
              index_name = #{indexName}, open = #{open}, high = #{high}, low = #{low}, close = #{close},
              pre_close = #{preClose}, pct_chg = #{pctChg}, vol = #{vol}, amount = #{amount},
              turnover = #{turnover}, data_source = #{dataSource}, src_detail = #{srcDetail},
              update_date = #{updateDate}
            WHERE index_code = #{indexCode} AND trade_date = #{tradeDate}
            """)
    int updateRow(IndexDaily row);

    @Insert("""
            INSERT INTO index_daily
              (trade_date, index_code, index_name, open, high, low, close, pre_close, pct_chg,
               vol, amount, turnover, data_source, src_detail, create_date, update_date)
            SELECT
              #{tradeDate}, #{indexCode}, #{indexName}, #{open}, #{high}, #{low}, #{close}, #{preClose}, #{pctChg},
               #{vol}, #{amount}, #{turnover}, #{dataSource}, #{srcDetail}, #{createDate}, #{updateDate}
            WHERE NOT EXISTS (
              SELECT 1 FROM index_daily WHERE index_code = #{indexCode} AND trade_date = #{tradeDate}
            )
            """)
    int insertIfAbsent(IndexDaily row);
}
