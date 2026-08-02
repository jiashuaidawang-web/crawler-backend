package com.dunwugudao.crawler.persistence.service;

import com.dunwugudao.crawler.core.model.SourceType;
import com.dunwugudao.crawler.persistence.entity.BoardDaily;
import com.dunwugudao.crawler.persistence.entity.DragonTiger;
import com.dunwugudao.crawler.persistence.entity.DtDetail;
import com.dunwugudao.crawler.persistence.entity.IndexDaily;
import com.dunwugudao.crawler.persistence.entity.LimitPool;
import com.dunwugudao.crawler.persistence.entity.MainFundFlow;
import com.dunwugudao.crawler.persistence.entity.StockBoardRel;
import com.dunwugudao.crawler.persistence.entity.StockDaily;
import com.dunwugudao.crawler.persistence.entity.StockWeekly;
import com.dunwugudao.crawler.persistence.mapper.BoardDailyMapper;
import com.dunwugudao.crawler.persistence.mapper.DragonTigerMapper;
import com.dunwugudao.crawler.persistence.mapper.DtDetailMapper;
import com.dunwugudao.crawler.persistence.mapper.IndexDailyMapper;
import com.dunwugudao.crawler.persistence.mapper.LimitPoolMapper;
import com.dunwugudao.crawler.persistence.mapper.MainFundFlowMapper;
import com.dunwugudao.crawler.persistence.mapper.StockBoardRelMapper;
import com.dunwugudao.crawler.persistence.mapper.StockDailyMapper;
import com.dunwugudao.crawler.persistence.mapper.StockWeeklyMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final StockBoardRelMapper stockBoardRelMapper;
    private final BoardDailyMapper boardDailyMapper;
    private final StockDailyMapper stockDailyMapper;
    private final StockWeeklyMapper stockWeeklyMapper;
    private final IndexDailyMapper indexDailyMapper;
    private final MainFundFlowMapper mainFundFlowMapper;
    private final DragonTigerMapper dragonTigerMapper;
    private final DtDetailMapper dtDetailMapper;

    /** 表 → 该表权威来源（优先级最高者）。 */
    private final Map<String, SourceType> precedence = buildPrecedence();

    public DedupWriter(LimitPoolMapper limitPoolMapper,
                       StockBoardRelMapper stockBoardRelMapper,
                       BoardDailyMapper boardDailyMapper,
                       StockDailyMapper stockDailyMapper,
                       StockWeeklyMapper stockWeeklyMapper,
                       IndexDailyMapper indexDailyMapper,
                       MainFundFlowMapper mainFundFlowMapper,
                       DragonTigerMapper dragonTigerMapper,
                       DtDetailMapper dtDetailMapper) {
        this.limitPoolMapper = limitPoolMapper;
        this.stockBoardRelMapper = stockBoardRelMapper;
        this.boardDailyMapper = boardDailyMapper;
        this.stockDailyMapper = stockDailyMapper;
        this.stockWeeklyMapper = stockWeeklyMapper;
        this.indexDailyMapper = indexDailyMapper;
        this.mainFundFlowMapper = mainFundFlowMapper;
        this.dragonTigerMapper = dragonTigerMapper;
        this.dtDetailMapper = dtDetailMapper;
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

    /** 写入板块日线（board_daily）。逐行幂等 upsert，主键 (board_code, trade_date)。 */
    public void writeBoardDaily(List<Map<String, Object>> rows, SourceType source, String srcDetail) {
        int newCode = source.getCode();
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = LocalDate.now();
        for (Map<String, Object> r : rows) {
            String boardCode = str(r.get("board_code"));
            LocalDate tradeDate = toLocalDate(r.get("trade_date"));
            if (boardCode == null || tradeDate == null) {
                log.warn("skip board_daily row missing board_code/trade_date: {}", r);
                continue;
            }
            // 优先级裁决：已存在且优先级 >= 新来源 → 不覆盖
            Integer existing = boardDailyMapper.selectDataSource(boardCode, tradeDate);
            if (existing != null && existing >= newCode) {
                continue;
            }
            BoardDaily entity = toBoardDailyEntity(r, source, srcDetail);
            entity.setCreateDate(today);
            entity.setUpdateDate(now);
            boardDailyMapper.insertOrUpdate(entity);
        }
    }

    /** 写入板块-个股关联（stock_board_rel）。逐行幂等 upsert，主键四列由数据库兜底防重。 */
    public void writeStockBoardRel(List<Map<String, Object>> rows, SourceType source, String srcDetail) {
        int newCode = source.getCode();
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = LocalDate.now();
        for (Map<String, Object> r : rows) {
            String boardCode = str(r.get("board_code"));
            String tsCode = str(r.get("ts_code"));
            Integer boardType = intVal(r.get("board_type"));
            if (boardCode == null || tsCode == null || boardType == null) {
                log.warn("skip stock_board_rel row missing board_code/ts_code/board_type: {}", r);
                continue;
            }
            // 优先级裁决：已存在且优先级 >= 新来源 → 不覆盖
            Integer existing = stockBoardRelMapper.selectDataSource(boardCode, tsCode, boardType);
            if (existing != null && existing >= newCode) {
                continue;
            }
            StockBoardRel entity = new StockBoardRel();
            entity.setBoardCode(boardCode);
            entity.setTsCode(tsCode);
            entity.setBoardName(str(r.get("board_name")));
            entity.setBoardType(boardType);
            entity.setIsLeader(intVal(r.get("is_leader")));
            entity.setIsMidarm(intVal(r.get("is_midarm")));
            entity.setWeight(dec(r.get("weight")));
            entity.setEffectiveDate(today);
            entity.setDataSource(newCode);
            entity.setSrcDetail(srcDetail);
            entity.setCreateDate(today);
            entity.setUpdateDate(now);
            stockBoardRelMapper.insertOrUpdate(entity);
        }
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

    private BoardDaily toBoardDailyEntity(Map<String, Object> r, SourceType source, String srcDetail) {
        BoardDaily e = new BoardDaily();
        e.setTradeDate(toLocalDate(r.get("trade_date")));
        e.setBoardCode(str(r.get("board_code")));
        e.setBoardName(str(r.get("board_name")));
        e.setBoardType(intVal(r.get("board_type")));
        e.setPctChg(dec(r.get("pct_chg")));
        e.setAmount(dec(r.get("amount")));
        e.setUpCount(intVal(r.get("up_count")));
        e.setDownCount(intVal(r.get("down_count")));
        e.setLimitUpCount(intVal(r.get("limit_up_count")));
        e.setLeadingCode(str(r.get("leading_code")));
        e.setLeadingName(str(r.get("leading_name")));
        e.setMainNet(dec(r.get("main_net")));
        e.setBoardCode2(str(r.get("board_code2")));
        e.setDataSource(source.getCode());
        e.setSrcDetail(srcDetail);
        // 行情明细
        e.setPrice(dec(r.get("price")));
        e.setRiseFall(dec(r.get("rise_fall")));
        e.setVolume(dec(r.get("volume")));
        e.setAmplitude(dec(r.get("amplitude")));
        e.setHighPrice(dec(r.get("high_price")));
        e.setLowPrice(dec(r.get("low_price")));
        e.setTodayOpenPrice(dec(r.get("today_open_price")));
        e.setYesterdayReceivedPrice(dec(r.get("yesterday_received_price")));
        e.setVolumeRatio(dec(r.get("volume_ratio")));
        e.setTurnoverRatio(dec(r.get("turnover_ratio")));
        e.setTotalMarketValue(dec(r.get("total_market_value")));
        e.setCirculationMarketValue(dec(r.get("circulation_market_value")));
        return e;
    }

    private Map<String, SourceType> buildPrecedence() {
        Map<String, SourceType> m = new HashMap<>();
        // 东财优先 daily / board / fund / dragon
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

    // ----------------------------------------------------------------------
    // 路由入口（按 taskType 分派到对应 writer，修复「全部写进 limit_pool」的路由缺失）
    // ----------------------------------------------------------------------

    /**
     * 按 taskType 路由写入对应原始表。
     * <p>东财解析器产出的 rows 已按 schema 列名归一化（key=目标表列名，必带 trade_date），
     * 本方法仅做 table 路由；未显式路由的 taskType 打 warn 而非静默兜底到 limit_pool。</p>
     */
    public void write(String taskType, List<Map<String, Object>> rows, SourceType source, String srcDetail) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        switch (taskType) {
            case "LIMIT_UP", "LIMIT_DOWN", "LIMIT_ZHABAN", "STRONG_POOL", "CIXIN_POOL" ->
                    writeLimitPool(rows, source, srcDetail);
            case "STOCK_DAILY" -> writeStockDaily(rows, source, srcDetail);
            case "STOCK_WEEKLY" -> writeStockWeekly(rows, source, srcDetail);
            case "INDEX_DAILY" -> writeIndexDaily(rows, source, srcDetail);
            case "BOARD_DAILY", "REGION_DAILY", "INDUSTRY_DAILY", "CONCEPT_DAILY" ->
                    writeBoardDaily(rows, source, srcDetail);
            case "MAIN_FUND_STOCK", "MAIN_FUND_BOARD" -> writeMainFundFlow(rows, source, srcDetail);
            case "DRAGON_TIGER" -> writeDragonTiger(rows, source, srcDetail);
            case "DRAGON_TIGER_DETAIL" -> writeDtDetail(rows, source, srcDetail);
            case "STOCK_BY_BOARD" -> writeStockBoardRel(rows, source, srcDetail);
            default ->
                    log.warn("DedupWriter.write: 未路由的 taskType={}, 跳过 {} 行（避免静默写错表）", taskType, rows.size());
        }
    }

    // ----------------------------------------------------------------------
    // 个股日线 / 周线 / 指数日线 / 主力资金流 / 龙虎榜 / 龙虎榜明细
    // ----------------------------------------------------------------------

    /** 写入个股日线（stock_daily）。逐行幂等 upsert，主键 (ts_code, trade_date)。 */
    public void writeStockDaily(List<Map<String, Object>> rows, SourceType source, String srcDetail) {
        int newCode = source.getCode();
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = LocalDate.now();
        for (Map<String, Object> r : rows) {
            String tsCode = str(r.get("ts_code"));
            LocalDate tradeDate = toLocalDate(r.get("trade_date"));
            if (tsCode == null || tradeDate == null) {
                log.warn("skip stock_daily row missing ts_code/trade_date: {}", r);
                continue;
            }
            Integer existing = stockDailyMapper.selectDataSource(tsCode, tradeDate);
            if (existing != null && existing >= newCode) {
                continue;
            }
            StockDaily e = new StockDaily();
            e.setTradeDate(tradeDate);
            e.setTsCode(tsCode);
            e.setStockName(str(r.get("stock_name")));
            e.setOpen(dec(r.get("open")));
            e.setHigh(dec(r.get("high")));
            e.setLow(dec(r.get("low")));
            e.setClose(dec(r.get("close")));
            e.setPreClose(dec(r.get("pre_close")));
            e.setPctChg(dec(r.get("pct_chg")));
            e.setVol(dec(r.get("vol")));
            e.setAmount(dec(r.get("amount")));
            e.setTurnover(dec(r.get("turnover")));
            e.setTotalMv(dec(r.get("total_mv")));
            e.setCircMv(dec(r.get("circ_mv")));
            e.setPe(dec(r.get("pe")));
            e.setIsLimitUp(intVal(r.get("is_limit_up")));
            e.setIsLimitDown(intVal(r.get("is_limit_down")));
            e.setChgAmount(dec(r.get("chg_amount")));
            e.setAmplitude(dec(r.get("amplitude")));
            e.setVolumeRatio(dec(r.get("volume_ratio")));
            e.setAvgPrice(dec(r.get("avg_price")));
            e.setMainNet(dec(r.get("main_net")));
            e.setPeStatic(dec(r.get("pe_static")));
            e.setLeaderCode(str(r.get("leader_code")));
            e.setIndustryCode(str(r.get("industry_code")));
            e.setConceptCode(str(r.get("concept_code")));
            e.setMarketCode(intVal(r.get("market_code")));
            e.setReservedF24(dec(r.get("reserved_f24")));
            e.setReservedF25(dec(r.get("reserved_f25")));
            e.setReservedF107(dec(r.get("reserved_f107")));
            e.setReservedF136(dec(r.get("reserved_f136")));
            e.setReservedF173(dec(r.get("reserved_f173")));
            e.setDataSource(newCode);
            e.setSrcDetail(srcDetail);
            e.setCreateDate(today);
            e.setUpdateDate(now);
            stockDailyMapper.insertOrUpdate(e);
        }
    }

    /** 写入个股周线（stock_weekly）。逐行幂等 upsert，主键 (ts_code, trade_date)。 */
    public void writeStockWeekly(List<Map<String, Object>> rows, SourceType source, String srcDetail) {
        int newCode = source.getCode();
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = LocalDate.now();
        for (Map<String, Object> r : rows) {
            String tsCode = str(r.get("ts_code"));
            LocalDate tradeDate = toLocalDate(r.get("trade_date"));
            if (tsCode == null || tradeDate == null) {
                log.warn("skip stock_weekly row missing ts_code/trade_date: {}", r);
                continue;
            }
            Integer existing = stockWeeklyMapper.selectDataSource(tsCode, tradeDate);
            if (existing != null && existing >= newCode) {
                continue;
            }
            StockWeekly e = new StockWeekly();
            e.setTradeDate(tradeDate);
            e.setTsCode(tsCode);
            e.setStockName(str(r.get("stock_name")));
            e.setOpen(dec(r.get("open")));
            e.setHigh(dec(r.get("high")));
            e.setLow(dec(r.get("low")));
            e.setClose(dec(r.get("close")));
            e.setVol(dec(r.get("vol")));
            e.setAmount(dec(r.get("amount")));
            e.setChgAmount(dec(r.get("chg_amount")));
            e.setAmplitude(dec(r.get("amplitude")));
            e.setVolumeRatio(dec(r.get("volume_ratio")));
            e.setAvgPrice(dec(r.get("avg_price")));
            e.setMainNet(dec(r.get("main_net")));
            e.setPeStatic(dec(r.get("pe_static")));
            e.setLeaderCode(str(r.get("leader_code")));
            e.setIndustryCode(str(r.get("industry_code")));
            e.setConceptCode(str(r.get("concept_code")));
            e.setMarketCode(intVal(r.get("market_code")));
            e.setDataSource(newCode);
            e.setSrcDetail(srcDetail);
            e.setCreateDate(today);
            e.setUpdateDate(now);
            stockWeeklyMapper.insertOrUpdate(e);
        }
    }

    /** 写入指数日线（index_daily）。逐行幂等 upsert，主键 (index_code, trade_date)。 */
    public void writeIndexDaily(List<Map<String, Object>> rows, SourceType source, String srcDetail) {
        int newCode = source.getCode();
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = LocalDate.now();
        for (Map<String, Object> r : rows) {
            String indexCode = str(r.get("index_code"));
            LocalDate tradeDate = toLocalDate(r.get("trade_date"));
            if (indexCode == null || tradeDate == null) {
                log.warn("skip index_daily row missing index_code/trade_date: {}", r);
                continue;
            }
            Integer existing = indexDailyMapper.selectDataSource(indexCode, tradeDate);
            if (existing != null && existing >= newCode) {
                continue;
            }
            IndexDaily e = new IndexDaily();
            e.setTradeDate(tradeDate);
            e.setIndexCode(indexCode);
            e.setIndexName(str(r.get("index_name")));
            e.setOpen(dec(r.get("open")));
            e.setHigh(dec(r.get("high")));
            e.setLow(dec(r.get("low")));
            e.setClose(dec(r.get("close")));
            e.setPreClose(dec(r.get("pre_close")));
            e.setPctChg(dec(r.get("pct_chg")));
            e.setVol(dec(r.get("vol")));
            e.setAmount(dec(r.get("amount")));
            e.setTurnover(dec(r.get("turnover")));
            e.setDataSource(newCode);
            e.setSrcDetail(srcDetail);
            e.setCreateDate(today);
            e.setUpdateDate(now);
            indexDailyMapper.insertOrUpdate(e);
        }
    }

    /** 写入主力资金流（main_fund_flow）。逐行幂等 upsert，主键 (obj_type, ts_code, board_code, index_code, trade_date)。 */
    public void writeMainFundFlow(List<Map<String, Object>> rows, SourceType source, String srcDetail) {
        int newCode = source.getCode();
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = LocalDate.now();
        for (Map<String, Object> r : rows) {
            String objType = str(r.get("obj_type"));
            String tsCode = str(r.get("ts_code"));
            String boardCode = str(r.get("board_code"));
            String indexCode = str(r.get("index_code"));
            LocalDate tradeDate = toLocalDate(r.get("trade_date"));
            if (objType == null || tsCode == null || boardCode == null || indexCode == null || tradeDate == null) {
                log.warn("skip main_fund_flow row missing key fields: {}", r);
                continue;
            }
            Integer existing = mainFundFlowMapper.selectDataSource(objType, tsCode, boardCode, indexCode, tradeDate);
            if (existing != null && existing >= newCode) {
                continue;
            }
            MainFundFlow e = new MainFundFlow();
            e.setTradeDate(tradeDate);
            e.setObjType(objType);
            e.setTsCode(tsCode);
            e.setBoardCode(boardCode);
            e.setIndexCode(indexCode);
            e.setMainNet(dec(r.get("main_net")));
            e.setSuperBig(dec(r.get("super_big")));
            e.setBigNet(dec(r.get("big_net")));
            e.setMidNet(dec(r.get("mid_net")));
            e.setSmallNet(dec(r.get("small_net")));
            e.setDataSource(newCode);
            e.setSrcDetail(srcDetail);
            e.setCreateDate(today);
            e.setUpdateDate(now);
            mainFundFlowMapper.insertOrUpdate(e);
        }
    }

    /** 写入龙虎榜（dragon_tiger）。逐行幂等 upsert，主键 (ts_code, trade_date)。 */
    public void writeDragonTiger(List<Map<String, Object>> rows, SourceType source, String srcDetail) {
        int newCode = source.getCode();
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = LocalDate.now();
        for (Map<String, Object> r : rows) {
            String tsCode = str(r.get("ts_code"));
            LocalDate tradeDate = toLocalDate(r.get("trade_date"));
            if (tsCode == null || tradeDate == null) {
                log.warn("skip dragon_tiger row missing ts_code/trade_date: {}", r);
                continue;
            }
            Integer existing = dragonTigerMapper.selectDataSource(tsCode, tradeDate);
            if (existing != null && existing >= newCode) {
                continue;
            }
            DragonTiger e = new DragonTiger();
            e.setTradeDate(tradeDate);
            e.setTsCode(tsCode);
            e.setStockName(str(r.get("stock_name")));
            e.setReason(str(r.get("reason")));
            e.setExplanation(str(r.get("explanation")));
            e.setAbnormalType(str(r.get("abnormal_type")));
            e.setNetBuy(dec(r.get("net_buy")));
            e.setTotalBuy(dec(r.get("total_buy")));
            e.setTotalSell(dec(r.get("total_sell")));
            e.setBillboardDealAmt(dec(r.get("billboard_deal_amt")));
            e.setAccumAmount(dec(r.get("accum_amount")));
            e.setBuyRatio(dec(r.get("buy_ratio")));
            e.setSellRatio(dec(r.get("sell_ratio")));
            e.setBuySeat(intVal(r.get("buy_seat")));
            e.setSellSeat(intVal(r.get("sell_seat")));
            e.setBuySeatNew(intVal(r.get("buy_seat_new")));
            e.setSellSeatNew(intVal(r.get("sell_seat_new")));
            e.setChangeRate(dec(r.get("change_rate")));
            e.setClosePrice(dec(r.get("close_price")));
            e.setTurnoverrate(dec(r.get("turnoverrate")));
            e.setFreeMarketCap(dec(r.get("free_market_cap")));
            e.setMarket(str(r.get("market")));
            e.setDealAmountRatio(dec(r.get("deal_amount_ratio")));
            e.setDealNetRatio(dec(r.get("deal_net_ratio")));
            e.setSecurityInnerCode(str(r.get("security_inner_code")));
            e.setSecurityTypeCode(str(r.get("security_type_code")));
            e.setTradeId(r.get("trade_id") instanceof Number n ? n.longValue() : null);
            e.setTradeMarket(str(r.get("trade_market")));
            e.setTradeMarketCode(str(r.get("trade_market_code")));
            e.setDataSource(newCode);
            e.setSrcDetail(srcDetail);
            e.setCreateDate(today);
            e.setUpdateDate(now);
            dragonTigerMapper.insertOrUpdate(e);
        }
    }

    /** 写入龙虎榜席位明细（dt_detail）。逐行幂等 upsert，主键 (ts_code, trade_date, seat_name)。 */
    public void writeDtDetail(List<Map<String, Object>> rows, SourceType source, String srcDetail) {
        int newCode = source.getCode();
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = LocalDate.now();
        for (Map<String, Object> r : rows) {
            String tsCode = str(r.get("ts_code"));
            LocalDate tradeDate = toLocalDate(r.get("trade_date"));
            String seatName = str(r.get("seat_name"));
            if (tsCode == null || tradeDate == null || seatName == null) {
                log.warn("skip dt_detail row missing ts_code/trade_date/seat_name: {}", r);
                continue;
            }
            Integer existing = dtDetailMapper.selectDataSource(tsCode, tradeDate, seatName);
            if (existing != null && existing >= newCode) {
                continue;
            }
            DtDetail e = new DtDetail();
            e.setTradeDate(tradeDate);
            e.setTsCode(tsCode);
            e.setSeatName(seatName);
            e.setSeatType(str(r.get("seat_type")));
            e.setBuy(dec(r.get("buy")));
            e.setSell(dec(r.get("sell")));
            e.setIsInstitution(intVal(r.get("is_institution")));
            e.setIsFamous(intVal(r.get("is_famous")));
            e.setDataSource(newCode);
            e.setSrcDetail(srcDetail);
            e.setCreateDate(today);
            e.setUpdateDate(now);
            dtDetailMapper.insertOrUpdate(e);
        }
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
