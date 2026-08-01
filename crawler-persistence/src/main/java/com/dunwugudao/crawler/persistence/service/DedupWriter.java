package com.dunwugudao.crawler.persistence.service;

import com.dunwugudao.crawler.core.model.SourceType;
import com.dunwugudao.crawler.persistence.entity.LimitPool;
import com.dunwugudao.crawler.persistence.mapper.LimitPoolMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 去重 / 溯源写入层（能力4/8/9）。
 * <p>规则：同一自然键(如 ts_code+trade_date)可能两源都给，
 * 以 data_source 优先级裁决覆写——新来源代码 > 已存在代码才覆写，相等或更低则不覆盖（避免抖动）。</p>
 * <p>优先级集中配置在 {@link #precedence}：东财优先 daily/board，同花顺优先 board_rel。该表用于
 * 调用方选择“权威来源”，逐行覆写裁决仍按 source 代码比较。</p>
 */
@Slf4j
@Service
public class DedupWriter {

    private final LimitPoolMapper limitPoolMapper;

    /** 表 → 该表权威来源（优先级最高者）。 */
    private final Map<String, SourceType> precedence = buildPrecedence();

    public DedupWriter(LimitPoolMapper limitPoolMapper) {
        this.limitPoolMapper = limitPoolMapper;
    }

    /** 写入 limit_pool（示例原始表）。M2 再补齐字段映射与批量优化。 */
    public void writeLimitPool(List<Map<String, Object>> rows, SourceType source, String srcDetail) {
        int newCode = source.getCode();
        for (Map<String, Object> r : rows) {
            String tsCode = String.valueOf(r.get("ts_code"));
            LocalDate tradeDate = toLocalDate(r.get("trade_date"));
            if (tsCode == null || tradeDate == null) {
                log.warn("skip limit_pool row missing ts_code/trade_date: {}", r);
                continue;
            }
            // 优先级裁决：已存在且优先级 >= 新来源 → 不覆盖
            Integer existing = limitPoolMapper.selectDataSource(tsCode, tradeDate);
            if (existing != null && existing >= newCode) {
                continue;
            }
            LimitPool entity = toEntity(r, source, srcDetail);
            limitPoolMapper.insertOrUpdate(entity);
        }
    }

    /** 返回某表的权威来源（供种子/调度选择策略时使用）。 */
    public SourceType preferredSource(String tableName) {
        return precedence.getOrDefault(tableName, SourceType.EASTMONEY);
    }

    private LimitPool toEntity(Map<String, Object> r, SourceType source, String srcDetail) {
        LimitPool e = new LimitPool();
        e.setTradeDate(toLocalDate(r.get("trade_date")));
        e.setTsCode(str(r.get("ts_code")));
        e.setStockName(str(r.get("stock_name")));
        e.setType(str(r.get("type")));
        e.setBoardPos(intVal(r.get("board_pos")));
        e.setIsFirst(intVal(r.get("is_first")));
        e.setIsContinuous(intVal(r.get("is_continuous")));
        e.setLimitStyle(str(r.get("limit_style")));
        e.setOpenTimes(intVal(r.get("open_times")));
        e.setBidAmount(dec(r.get("bid_amount")));
        e.setTurnover(dec(r.get("turnover")));
        e.setPctChg(dec(r.get("pct_chg")));
        e.setReason(str(r.get("reason")));
        e.setBoardCode(str(r.get("board_code")));
        e.setBoardName(str(r.get("board_name")));
        e.setDataSource(source.getCode());
        e.setSrcDetail(srcDetail);
        return e;
    }

    private Map<String, SourceType> buildPrecedence() {
        Map<String, SourceType> m = new HashMap<>();
        // 东财优先 daily / board
        m.put("index_daily", SourceType.EASTMONEY);
        m.put("stock_daily", SourceType.EASTMONEY);
        m.put("stock_weekly", SourceType.EASTMONEY);
        m.put("board_daily", SourceType.EASTMONEY);
        m.put("limit_pool", SourceType.EASTMONEY);
        m.put("strong_pool", SourceType.EASTMONEY);
        m.put("dragon_tiger", SourceType.EASTMONEY);
        m.put("main_fund_flow", SourceType.EASTMONEY);
        m.put("northbound_flow", SourceType.EASTMONEY);
        // 同花顺优先 board_rel
        m.put("stock_board_rel", SourceType.TONGHUASHUN);
        return m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer intVal(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private static BigDecimal dec(Object o) {
        return o instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null;
    }

    private static LocalDate toLocalDate(Object o) {
        if (o instanceof LocalDate d) {
            return d;
        }
        if (o instanceof java.time.LocalDateTime dt) {
            return dt.toLocalDate();
        }
        if (o != null) {
            return LocalDate.parse(String.valueOf(o).substring(0, 10));
        }
        return null;
    }
}
