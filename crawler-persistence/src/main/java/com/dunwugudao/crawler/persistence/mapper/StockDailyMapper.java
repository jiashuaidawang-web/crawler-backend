package com.dunwugudao.crawler.persistence.mapper;

import com.dunwugudao.crawler.persistence.entity.StockDaily;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 个股日线 Mapper（ClickHouse）。
 *
 * <p><b>写入策略变更</b>：CK 不支持行级 UPDATE / 事务 / RETURNING。
 * 去重由表引擎 ReplacingMergeTree 改为 MergeTree 纯追加，查询时用 FINAL 取权威行。
 * 因此：</p>
 * <ul>
 *   <li>去掉 {@code updateRow}、{@code insertIfAbsent}（不再逐行 read-then-write）</li>
 *   <li>新增 {@link #batchInsert}：多行 VALUES 批量追加</li>
 *   <li>查询用 {@code ... FINAL WHERE ts_code=? AND trade_date=?} 取合并后权威行</li>
 * </ul>
 */
@Mapper
public interface StockDailyMapper {

    /** 批量追加（多行 VALUES），由 ClickHouse MergeTree 承接。 */
    void batchInsert(@Param("list") List<StockDaily> rows);

    /**
     * 查询单行合并后权威值（FINAL = 触发 ReplacingMergeTree 去重语义）。
     * 注意：FINAL 在大表上较慢，仅用于必须取单行的场景；聚合查询直接查裸表 + argMax。
     */
    @Select("SELECT * FROM stock_daily FINAL WHERE ts_code = #{tsCode} AND trade_date = #{tradeDate} LIMIT 1")
    StockDaily selectFinal(@Param("tsCode") String tsCode, @Param("tradeDate") LocalDate tradeDate);

    /**
     * 取最新交易日去重股票池（供 financial 等种子使用）。
     * <p>use server_time_zone=false 下 CK 存 Date，直接返回字符串即可；去掉可能的 .SZ/.SH 后缀由调用方处理。</p>
     */
    @Select("SELECT DISTINCT ts_code FROM stock_daily WHERE trade_date = (SELECT max(trade_date) FROM stock_daily)")
    List<String> selectDistinctTsCode();
}
