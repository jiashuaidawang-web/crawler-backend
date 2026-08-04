package com.dunwugudao.crawler.persistence.service;

import com.dunwugudao.crawler.core.model.SourceType;
import com.dunwugudao.crawler.persistence.entity.BoardDaily;
import com.dunwugudao.crawler.persistence.entity.DragonTiger;
import com.dunwugudao.crawler.persistence.entity.DtDetail;
import com.dunwugudao.crawler.persistence.entity.IndexDaily;
import com.dunwugudao.crawler.persistence.entity.LimitUpPool;
import com.dunwugudao.crawler.persistence.entity.LimitDownPool;
import com.dunwugudao.crawler.persistence.entity.ZhabanPool;
import com.dunwugudao.crawler.persistence.entity.StrongPool;
import com.dunwugudao.crawler.persistence.entity.CixinPool;
import com.dunwugudao.crawler.persistence.entity.MainFundFlow;
import com.dunwugudao.crawler.persistence.entity.StockBoardRel;
import com.dunwugudao.crawler.persistence.entity.StockDaily;
import com.dunwugudao.crawler.persistence.entity.StockWeekly;
import com.dunwugudao.crawler.persistence.mapper.BoardDailyMapper;
import com.dunwugudao.crawler.persistence.mapper.DragonTigerMapper;
import com.dunwugudao.crawler.persistence.mapper.DtDetailMapper;
import com.dunwugudao.crawler.persistence.mapper.IndexDailyMapper;
import com.dunwugudao.crawler.persistence.mapper.LimitUpPoolMapper;
import com.dunwugudao.crawler.persistence.mapper.LimitDownPoolMapper;
import com.dunwugudao.crawler.persistence.mapper.ZhabanPoolMapper;
import com.dunwugudao.crawler.persistence.mapper.StrongPoolMapper;
import com.dunwugudao.crawler.persistence.mapper.CixinPoolMapper;
import com.dunwugudao.crawler.persistence.mapper.MainFundFlowMapper;
import com.dunwugudao.crawler.persistence.mapper.StockBoardRelMapper;
import com.dunwugudao.crawler.persistence.mapper.StockDailyMapper;
import com.dunwugudao.crawler.persistence.mapper.StockWeeklyMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

    private final LimitUpPoolMapper limitUpPoolMapper;
    private final LimitDownPoolMapper limitDownPoolMapper;
    private final ZhabanPoolMapper zhabanPoolMapper;
    private final StrongPoolMapper strongPoolMapper;
    private final CixinPoolMapper cixinPoolMapper;
    private final StockBoardRelMapper stockBoardRelMapper;
    private final BoardDailyMapper boardDailyMapper;
    private final StockDailyMapper stockDailyMapper;
    private final StockWeeklyMapper stockWeeklyMapper;
    private final IndexDailyMapper indexDailyMapper;
    private final MainFundFlowMapper mainFundFlowMapper;
    private final DragonTigerMapper dragonTigerMapper;
    private final DtDetailMapper dtDetailMapper;
    private final BoardBasicSyncService boardBasicSyncService;

    public DedupWriter(LimitUpPoolMapper limitUpPoolMapper,
                       LimitDownPoolMapper limitDownPoolMapper,
                       ZhabanPoolMapper zhabanPoolMapper,
                       StrongPoolMapper strongPoolMapper,
                       CixinPoolMapper cixinPoolMapper,
                       StockBoardRelMapper stockBoardRelMapper,
                       BoardDailyMapper boardDailyMapper,
                       StockDailyMapper stockDailyMapper,
                       StockWeeklyMapper stockWeeklyMapper,
                       IndexDailyMapper indexDailyMapper,
                       MainFundFlowMapper mainFundFlowMapper,
                       DragonTigerMapper dragonTigerMapper,
                       DtDetailMapper dtDetailMapper,
                       BoardBasicSyncService boardBasicSyncService
    ) {
        this.limitUpPoolMapper = limitUpPoolMapper;
        this.limitDownPoolMapper = limitDownPoolMapper;
        this.zhabanPoolMapper = zhabanPoolMapper;
        this.strongPoolMapper = strongPoolMapper;
        this.cixinPoolMapper = cixinPoolMapper;
        this.boardDailyMapper = boardDailyMapper;
        this.stockBoardRelMapper = stockBoardRelMapper;
        this.stockDailyMapper = stockDailyMapper;
        this.stockWeeklyMapper = stockWeeklyMapper;
        this.indexDailyMapper = indexDailyMapper;
        this.mainFundFlowMapper = mainFundFlowMapper;
        this.dragonTigerMapper = dragonTigerMapper;
        this.dtDetailMapper = dtDetailMapper;
        this.boardBasicSyncService = boardBasicSyncService;
    }

//        this.stockBoardRelMapper = stockBoardRelMapper;
//        this.boardDailyMapper = boardDailyMapper;
//        this.stockDailyMapper = stockDailyMapper;
//        this.stockWeeklyMapper = stockWeeklyMapper;
//        this.indexDailyMapper = indexDailyMapper;
//        this.mainFundFlowMapper = mainFundFlowMapper;
//        this.dragonTigerMapper = dragonTigerMapper;
//        this.dtDetailMapper = dtDetailMapper;


    /** 写入 limit_pool（示例原始表）。openGauss 兼容：select → update/insert。 */
//    @Transactional(rollbackFor = Exception.class)
//    public void writeLimitPool(List<Map<String, Object>> rows, SourceType source, String srcDetail) {
//        int newCode = source.getCode();
//        LocalDateTime now = LocalDateTime.now();
//        LocalDate today = LocalDate.now();
//        for (Map<String, Object> r : rows) {
//            String tsCode = String.valueOf(r.get("ts_code"));
//            LocalDate tradeDate = toLocalDate(r.get("trade_date"));
//            if (tsCode == null || tradeDate == null) {
//                log.warn("skip limit_pool row missing ts_code/trade_date: {}", r);
//                continue;
//            }
//            // 优先级裁决：已存在且优先级 >= 新来源 → 不覆盖
//            Integer existing = limitPoolMapper.selectDataSource(tsCode, tradeDate);
//            if (existing != null && existing >= newCode) {
//                continue;
//            }
//            LimitPool entity = toEntity(r, source, srcDetail);
//            if (existing != null) {
//                limitPoolMapper.updateRow(entity);
//            } else {
//                limitPoolMapper.insertIfAbsent(entity);
//            }
//        }
//    }

    /** 返回某表的权威来源（供种子/调度选择策略时使用）。 */
//    public SourceType preferredSource(String tableName) {
//        return precedence.getOrDefault(tableName, SourceType.EASTMONEY);
//    }

    /** 写入板块日线（board_daily）。openGauss 兼容：select → update/insert。 */
    @Transactional(rollbackFor = Exception.class)
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
            Integer existing = boardDailyMapper.selectDataSource(boardCode, tradeDate);
            if (existing != null && existing >= newCode) {
                continue;
            }
            BoardDaily entity = toBoardDailyEntity(r, source, srcDetail);
            entity.setCreateDate(today);
            entity.setUpdateDate(now);
            if (existing != null) {
                boardDailyMapper.updateRow(entity);
            } else {
                boardDailyMapper.insertIfAbsent(entity);
            }
            // board_daily 落库后，顺手同步 board_basic 维表（幂等，失败不影响主流程）
            try {
                boardBasicSyncService.syncBoard(
                        boardCode, str(r.get("board_name")),
                        intVal(r.get("board_type")), newCode);
            } catch (Exception e) {
                log.warn("syncBoard 副作用失败(boardCode={}, tradeDate={}): {}",
                        boardCode, tradeDate, e.getMessage());
            }
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
            entity.setStockName(str(r.get("stock_name")));
            entity.setBoardType(boardType);
            entity.setIsLeader(intVal(r.get("is_leader")));
            entity.setIsMidarm(intVal(r.get("is_midarm")));
            entity.setWeight(bigDec(r.get("weight")));
            entity.setEffectiveDate(today);
            entity.setDataSource(newCode);
            entity.setSrcDetail(srcDetail);
            entity.setCreateDate(today);
            entity.setUpdateDate(now);
            // openGauss 兼容：拆成 updateRow / insertIfAbsent（不用 ON CONFLICT）
            if (existing != null) {
                stockBoardRelMapper.updateRow(entity);
            } else {
                stockBoardRelMapper.insertIfAbsent(entity);
            }
        }
    }

//    private LimitPool toEntity(Map<String, Object> r, SourceType source, String srcDetail) {
//        LimitPool e = new LimitPool();
//        e.setTradeDate(toLocalDate(r.get("trade_date")));
//        e.setTsCode(str(r.get("ts_code")));
//        e.setStockName(str(r.get("stock_name")));
//        e.setType(str(r.get("type")));
//        e.setLatestPrice(bigDec(r.get("latest_price")));  // 最新价(元)
//        e.setPctChg(bigDec(r.get("pct_chg")));            // 涨跌幅%
//        e.setBoardPos(intVal(r.get("board_pos")));
//        e.setIsFirst(intVal(r.get("is_first")));
//        e.setIsContinuous(intVal(r.get("is_continuous")));
//        e.setLimitStyle(str(r.get("limit_style")));
//        // open_time/last_time 已是 HH:mm:ss 字符串
//        e.setOpenTime(str(r.get("open_time")));
//        e.setLastTime(str(r.get("last_time")));
//        e.setOpenTimes(intVal(r.get("open_times")));
//        e.setBidAmount(bigDec(r.get("bid_amount")));
//        e.setHs(bigDec(r.get("hs")));                    // 换手率%
//        e.setTurnover(bigDec(r.get("hs")));              // 换手率%（复用）
//        e.setZtp(bigDec(r.get("ztp")));                  // 涨停价
//        e.setAmount(bigDec(r.get("amount")));            // 成交额
//        e.setFund(bigDec(r.get("fund")));                // 封单资金
//        e.setLtsz(bigDec(r.get("ltsz")));                // 流通市值
//        e.setTshare(bigDec(r.get("tshare")));            // 总市值
//        e.setZf(bigDec(r.get("zf")));                    // 炸板涨幅%
//        e.setZs(bigDec(r.get("zs")));                    // 炸板振幅%
//        e.setZttjCt(intVal(r.get("zttj_ct")));           // 连板统计-连板数
//        e.setZttjDays(intVal(r.get("zttj_days")));       // 连板统计-天数
//        e.setLb(intVal(r.get("lb")));                    // 强势池连板数
//        e.setNh(intVal(r.get("nh")));                    // N日新高
//        e.setZtf(str(r.get("ztf")));                     // 涨停封单描述
//        e.setBidAmount(bigDec(r.get("bid_amount")));     // 封单金额(万元)
//        e.setIpod(str(r.get("ipod")));                   // 上市日期(YYYYMMDD)
//        e.setOd(str(r.get("od")));                       // 开板日期(YYYYMMDD)
//        e.setOds(intVal(r.get("ods")));                  // 开板几日
//        e.setIsNewHigh(intVal(r.get("o")));              // 是否新高标识
//        e.setLb(intVal(r.get("lb")));                    // 连板数(强势池)
//        e.setBoardCode(str(r.get("board_code")));
//        e.setBoardName(str(r.get("board_name")));
//        e.setDataSource(source.getCode());
//        e.setSrcDetail(srcDetail);
//        return e;
//    }

    /** 解析 HH:mm:ss 字符串为 LocalTime。 */
    private static LocalTime parseTime(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return LocalTime.parse(s.length() == 5 ? s + ":00" : s);
        } catch (Exception e) {
            return null;
        }
    }

    private BoardDaily toBoardDailyEntity(Map<String, Object> r, SourceType source, String srcDetail) {
        BoardDaily e = new BoardDaily();
        e.setTradeDate(toLocalDate(r.get("trade_date")));
        e.setBoardCode(str(r.get("board_code")));
        e.setBoardName(str(r.get("board_name")));
        e.setBoardType(intVal(r.get("board_type")));
        e.setPctChg(bigDec(r.get("pct_chg")));
        e.setAmount(bigDec(r.get("amount")));
        e.setUpCount(intVal(r.get("up_count")));
        e.setDownCount(intVal(r.get("down_count")));
        e.setLimitUpCount(intVal(r.get("limit_up_count")));
        e.setLeadingCode(str(r.get("leading_code")));
        e.setLeadingName(str(r.get("leading_name")));
        e.setMainNet(bigDec(r.get("main_net")));
        e.setBoardCode2(str(r.get("board_code2")));
        e.setDataSource(source.getCode());
        e.setSrcDetail(srcDetail);
        // 行情明细
        e.setPrice(bigDec(r.get("price")));
        e.setRiseFall(bigDec(r.get("rise_fall")));
        e.setVolume(bigDec(r.get("volume")));
        e.setAmplitude(bigDec(r.get("amplitude")));
        e.setHighPrice(bigDec(r.get("high_price")));
        e.setLowPrice(bigDec(r.get("low_price")));
        e.setTodayOpenPrice(bigDec(r.get("today_open_price")));
        e.setYesterdayReceivedPrice(bigDec(r.get("yesterday_received_price")));
        e.setVolumeRatio(bigDec(r.get("volume_ratio")));
        e.setTurnoverRatio(bigDec(r.get("turnover_ratio")));
        e.setTotalMarketValue(bigDec(r.get("total_market_value")));
        e.setCirculationMarketValue(bigDec(r.get("circulation_market_value")));
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

    // 5 个池子独立 writer（插在这里，路由调用）
    @Transactional(rollbackFor = Exception.class)
    public void writeLimitUpPool(List<Map<String, Object>> rows, SourceType source, String srcDetail) {
        int newCode = source.getCode();
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> r : rows) {
            String tsCode = str(r.get("ts_code"));
            LocalDate tradeDate = toLocalDate(r.get("trade_date"));
            if (tsCode == null || tradeDate == null) continue;
            Integer existing = limitUpPoolMapper.selectDataSource(tsCode, tradeDate, newCode);
            LimitUpPool e = new LimitUpPool();
            setPoolBaseFields(e, r, tradeDate, tsCode, source, srcDetail, today, now);
            e.setFund(bigDec(r.get("fund")));
            e.setZttjCt(intVal(r.get("zttj_ct"))); e.setZttjDays(intVal(r.get("zttj_days")));
            if (existing != null) limitUpPoolMapper.updateRow(e); else limitUpPoolMapper.insertIfAbsent(e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void writeLimitDownPool(List<Map<String, Object>> rows, SourceType source, String srcDetail) {
        int newCode = source.getCode();
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> r : rows) {
            String tsCode = str(r.get("ts_code"));
            LocalDate tradeDate = toLocalDate(r.get("trade_date"));
            if (tsCode == null || tradeDate == null) continue;
            Integer existing = limitDownPoolMapper.selectDataSource(tsCode, tradeDate, newCode);
            LimitDownPool e = new LimitDownPool();
            setPoolBaseFields(e, r, tradeDate, tsCode, source, srcDetail, today, now);
            e.setPe(bigDec(r.get("pe"))); e.setFba(bigDec(r.get("fba")));
            e.setDays(intVal(r.get("days"))); e.setOc(intVal(r.get("oc")));
            if (existing != null) limitDownPoolMapper.updateRow(e); else limitDownPoolMapper.insertIfAbsent(e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void writeZhabanPool(List<Map<String, Object>> rows, SourceType source, String srcDetail) {
        int newCode = source.getCode();
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> r : rows) {
            String tsCode = str(r.get("ts_code"));
            LocalDate tradeDate = toLocalDate(r.get("trade_date"));
            if (tsCode == null || tradeDate == null) continue;
            Integer existing = zhabanPoolMapper.selectDataSource(tsCode, tradeDate, newCode);
            ZhabanPool e = new ZhabanPool();
            setPoolBaseFields(e, r, tradeDate, tsCode, source, srcDetail, today, now);
            e.setZtp(bigDec(r.get("ztp"))); e.setZf(bigDec(r.get("zf"))); e.setZs(bigDec(r.get("zs")));
            e.setZttjCt(intVal(r.get("zttj_ct"))); e.setZttjDays(intVal(r.get("zttj_days")));
            if (existing != null) zhabanPoolMapper.updateRow(e); else zhabanPoolMapper.insertIfAbsent(e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void writeStrongPool(List<Map<String, Object>> rows, SourceType source, String srcDetail) {
        int newCode = source.getCode();
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> r : rows) {
            String tsCode = str(r.get("ts_code"));
            LocalDate tradeDate = toLocalDate(r.get("trade_date"));
            if (tsCode == null || tradeDate == null) continue;
            Integer existing = strongPoolMapper.selectDataSource(tsCode, tradeDate, newCode);
            StrongPool e = new StrongPool();
            setPoolBaseFields(e, r, tradeDate, tsCode, source, srcDetail, today, now);
            e.setZtp(bigDec(r.get("ztp"))); e.setZs(bigDec(r.get("zs"))); e.setNh(intVal(r.get("nh"))); e.setLb(bigDec(r.get("lb")));
            e.setZttjCt(intVal(r.get("zttj_ct"))); e.setZttjDays(intVal(r.get("zttj_days")));
            if (existing != null) strongPoolMapper.updateRow(e); else strongPoolMapper.insertIfAbsent(e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void writeCixinPool(List<Map<String, Object>> rows, SourceType source, String srcDetail) {
        int newCode = source.getCode();
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> r : rows) {
            String tsCode = str(r.get("ts_code"));
            LocalDate tradeDate = toLocalDate(r.get("trade_date"));
            if (tsCode == null || tradeDate == null) continue;
            // 主键 (ts_code, trade_date, data_source)：仅当同一来源已存在时才更新，否则插入（不同来源共存）
            Integer existing = cixinPoolMapper.selectDataSource(tsCode, tradeDate, newCode);
            CixinPool e = new CixinPool();
            setPoolBaseFields(e, r, tradeDate, tsCode, source, srcDetail, today, now);
            e.setZtp(bigDec(r.get("ztp"))); e.setOds(intVal(r.get("ods"))); e.setOd(str(r.get("od"))); e.setIpod(str(r.get("ipod")));
            e.setO(intVal(r.get("o"))); e.setNh(intVal(r.get("nh")));
            e.setZttjCt(intVal(r.get("zttj_ct"))); e.setZttjDays(intVal(r.get("zttj_days")));
            if (existing != null) cixinPoolMapper.updateRow(e); else cixinPoolMapper.insertIfAbsent(e);
        }
    }

    /** 设置 5 个池子实体的公共字段。 */
    private void setPoolBaseFields(Object e, Map<String, Object> r, LocalDate tradeDate, String tsCode,
                                   SourceType source, String srcDetail, LocalDate today, LocalDateTime now) {
        try {
            e.getClass().getMethod("setTradeDate", LocalDate.class).invoke(e, tradeDate);
            e.getClass().getMethod("setTsCode", String.class).invoke(e, tsCode);
            e.getClass().getMethod("setStockName", String.class).invoke(e, str(r.get("stock_name")));
            e.getClass().getMethod("setLatestPrice", BigDecimal.class).invoke(e, bigDec(r.get("latest_price")));
            e.getClass().getMethod("setPctChg", BigDecimal.class).invoke(e, bigDec(r.get("pct_chg")));
            e.getClass().getMethod("setTurnoverRate", BigDecimal.class).invoke(e, bigDec(r.get("turnover_rate")));
            e.getClass().getMethod("setBoardCode", String.class).invoke(e, str(r.get("board_code")));
            try { e.getClass().getMethod("setOpenTime", String.class).invoke(e, str(r.get("open_time"))); } catch (Exception ignored) {}
            try { e.getClass().getMethod("setLastTime", String.class).invoke(e, str(r.get("last_time"))); } catch (Exception ignored) {}
            try { e.getClass().getMethod("setOpenTimes", Integer.class).invoke(e, intVal(r.get("open_times"))); } catch (Exception ignored) {}
            try { e.getClass().getMethod("setBoardPos", Integer.class).invoke(e, intVal(r.get("board_pos"))); } catch (Exception ignored) {}
            try { e.getClass().getMethod("setIsFirst", Integer.class).invoke(e, intVal(r.get("is_first"))); } catch (Exception ignored) {}
            try { e.getClass().getMethod("setIsContinuous", Integer.class).invoke(e, intVal(r.get("is_continuous"))); } catch (Exception ignored) {}
            try { e.getClass().getMethod("setLimitStyle", String.class).invoke(e, str(r.get("limit_style"))); } catch (Exception ignored) {}
            e.getClass().getMethod("setAmount", BigDecimal.class).invoke(e, bigDec(r.get("amount")));
            e.getClass().getMethod("setLtsz", BigDecimal.class).invoke(e, bigDec(r.get("ltsz")));
            e.getClass().getMethod("setTshare", BigDecimal.class).invoke(e, bigDec(r.get("tshare")));
            e.getClass().getMethod("setDataSource", Integer.class).invoke(e, source.getCode());
            e.getClass().getMethod("setSrcDetail", String.class).invoke(e, srcDetail);
            e.getClass().getMethod("setCreateDate", LocalDate.class).invoke(e, today);
            e.getClass().getMethod("setUpdateDate", LocalDateTime.class).invoke(e, now);
        } catch (Exception ex) {
            log.warn("setPoolBaseFields failed: {}", ex.getMessage());
        }
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
            case "LIMIT_UP" -> writeLimitUpPool(rows, source, srcDetail);
            case "LIMIT_DOWN" -> writeLimitDownPool(rows, source, srcDetail);
            case "LIMIT_ZHABAN" -> writeZhabanPool(rows, source, srcDetail);
            case "STRONG_POOL" -> writeStrongPool(rows, source, srcDetail);
            case "CIXIN_POOL" -> writeCixinPool(rows, source, srcDetail);
            case "STOCK_DAILY" -> writeStockDaily(rows, source, srcDetail);
            case "STOCK_WEEKLY" -> writeStockWeekly(rows, source, srcDetail);
            case "INDEX_DAILY" -> writeIndexDaily(rows, source, srcDetail);
            case "REGION_DAILY", "INDUSTRY_DAILY", "CONCEPT_DAILY" ->
                    writeBoardDaily(rows, source, srcDetail);
            case "MAIN_FUND_STOCK", "MAIN_FUND_BOARD" -> writeMainFundFlow(rows, source, srcDetail);
            case "DRAGON_TIGER" -> writeDragonTiger(rows, source, srcDetail);
            case "DRAGON_TIGER_DETAIL" -> writeDtDetail(rows, source, srcDetail);
            case "STOCK_BY_BOARD" -> writeStockBoardRel(rows, source, srcDetail);
            // 板块基础维表（board_basic）—— 独立抓取，幂等写入
            case "REGION_BOARD", "INDUSTRY_BOARD", "CONCEPT_BOARD" ->
                    writeBoardBasic(rows, source, srcDetail);
            default ->
                    log.warn("DedupWriter.write: 未路由的 taskType={}, 跳过 {} 行（避免静默写错表）", taskType, rows.size());
        }
    }

    /**
     * 写入板块基础维表（board_basic）。
     * <p>唯一键 (board_type, board_code, data_source)，幂等：已存在则跳过（名称变化不追）。
     * 复用 BoardBasicSyncService.syncBoard() —— 与 board_daily 副作用同步同逻辑。</p>
     */
    public void writeBoardBasic(List<Map<String, Object>> rows, SourceType source, String srcDetail) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        int dataSource = source.getCode();
        int processed = 0, skipped = 0;
        for (Map<String, Object> r : rows) {
            try {
                String boardCode = (String) r.get("board_code");
                String boardName = (String) r.get("board_name");
                Integer boardType = (Integer) r.get("board_type");
                if (boardType == null || boardType <= 0) {
                    skipped++;
                    continue;
                }
                // syncBoard 内部幂等：按 (board_type, board_code, data_source) 查，有则跳过无则新增
                boardBasicSyncService.syncBoard(boardCode, boardName, boardType, dataSource);
                processed++;
            } catch (Exception e) {
                log.warn("DedupWriter.writeBoardBasic 行写入失败(source={}, board_code={}): {}",
                        dataSource, r.get("board_code"), e.getMessage());
            }
        }
        log.info("DedupWriter.writeBoardBasic 写入完成: source={}, total={}, processed={}, skipped={}",
                dataSource, rows.size(), processed, skipped);
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
            e.setOpen(bigDec(r.get("open")));
            e.setHigh(bigDec(r.get("high")));
            e.setLow(bigDec(r.get("low")));
            e.setClose(bigDec(r.get("close")));
            e.setPreClose(bigDec(r.get("pre_close")));
            e.setPctChg(bigDec(r.get("pct_chg")));
            e.setVol(bigDec(r.get("vol")));
            e.setAmount(bigDec(r.get("amount")));
            e.setTurnover(bigDec(r.get("turnover")));
            e.setTotalMv(bigDec(r.get("total_mv")));
            e.setCircMv(bigDec(r.get("circ_mv")));
            e.setPe(bigDec(r.get("pe")));
            e.setIsLimitUp(intVal(r.get("is_limit_up")));
            e.setIsLimitDown(intVal(r.get("is_limit_down")));
            e.setChgAmount(bigDec(r.get("chg_amount")));
            e.setAmplitude(bigDec(r.get("amplitude")));
            e.setVolumeRatio(bigDec(r.get("volume_ratio")));
            e.setChg60d(bigDec(r.get("chg_60d")));
            e.setMarketCode(intVal(r.get("market_code")));
            e.setDataSource(newCode);
            e.setSrcDetail(srcDetail);
            e.setCreateDate(today);
            e.setUpdateDate(now);
            if (existing != null) {
                stockDailyMapper.updateRow(e);
            } else {
                stockDailyMapper.insertIfAbsent(e);
            }
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
            e.setOpen(bigDec(r.get("open")));
            e.setHigh(bigDec(r.get("high")));
            e.setLow(bigDec(r.get("low")));
            e.setClose(bigDec(r.get("close")));
            e.setVol(bigDec(r.get("vol")));
            e.setAmount(bigDec(r.get("amount")));
            e.setChgAmount(bigDec(r.get("chg_amount")));
            e.setAmplitude(bigDec(r.get("amplitude")));
            e.setVolumeRatio(bigDec(r.get("volume_ratio")));
            e.setAvgPrice(bigDec(r.get("avg_price")));
            e.setMainNet(bigDec(r.get("main_net")));
            e.setPeStatic(bigDec(r.get("pe_static")));
            e.setLeaderCode(str(r.get("leader_code")));
            e.setIndustryCode(str(r.get("industry_code")));
            e.setConceptCode(str(r.get("concept_code")));
            e.setMarketCode(intVal(r.get("market_code")));
            e.setDataSource(newCode);
            e.setSrcDetail(srcDetail);
            e.setCreateDate(today);
            e.setUpdateDate(now);
            if (existing != null) {
                stockWeeklyMapper.updateRow(e);
            } else {
                stockWeeklyMapper.insertIfAbsent(e);
            }
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
            e.setOpen(bigDec(r.get("open")));
            e.setHigh(bigDec(r.get("high")));
            e.setLow(bigDec(r.get("low")));
            e.setClose(bigDec(r.get("close")));
            e.setPreClose(bigDec(r.get("pre_close")));
            e.setPctChg(bigDec(r.get("pct_chg")));
            e.setVol(bigDec(r.get("vol")));
            e.setAmount(bigDec(r.get("amount")));
            e.setTurnover(bigDec(r.get("turnover")));
            e.setDataSource(newCode);
            e.setSrcDetail(srcDetail);
            e.setCreateDate(today);
            e.setUpdateDate(now);
            if (existing != null) {
                indexDailyMapper.updateRow(e);
            } else {
                indexDailyMapper.insertIfAbsent(e);
            }
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
            e.setMainNet(bigDec(r.get("main_net")));
            e.setSuperBig(bigDec(r.get("super_big")));
            e.setBigNet(bigDec(r.get("big_net")));
            e.setMidNet(bigDec(r.get("mid_net")));
            e.setSmallNet(bigDec(r.get("small_net")));
            e.setDataSource(newCode);
            e.setSrcDetail(srcDetail);
            e.setCreateDate(today);
            e.setUpdateDate(now);
            if (existing != null) {
                mainFundFlowMapper.updateRow(e);
            } else {
                mainFundFlowMapper.insertIfAbsent(e);
            }
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
            e.setNetBuy(bigDec(r.get("net_buy")));
            e.setTotalBuy(bigDec(r.get("total_buy")));
            e.setTotalSell(bigDec(r.get("total_sell")));
            e.setBillboardDealAmt(bigDec(r.get("billboard_deal_amt")));
            e.setAccumAmount(bigDec(r.get("accum_amount")));
            e.setBuyRatio(bigDec(r.get("buy_ratio")));
            e.setSellRatio(bigDec(r.get("sell_ratio")));
            e.setBuySeat(intVal(r.get("buy_seat")));
            e.setSellSeat(intVal(r.get("sell_seat")));
            e.setBuySeatNew(intVal(r.get("buy_seat_new")));
            e.setSellSeatNew(intVal(r.get("sell_seat_new")));
            e.setChangeRate(bigDec(r.get("change_rate")));
            e.setClosePrice(bigDec(r.get("close_price")));
            e.setTurnoverrate(bigDec(r.get("turnoverrate")));
            e.setFreeMarketCap(bigDec(r.get("free_market_cap")));
            e.setMarket(str(r.get("market")));
            e.setDealAmountRatio(bigDec(r.get("deal_amount_ratio")));
            e.setDealNetRatio(bigDec(r.get("deal_net_ratio")));
            e.setSecurityInnerCode(str(r.get("security_inner_code")));
            e.setSecurityTypeCode(str(r.get("security_type_code")));
            e.setTradeId(r.get("trade_id") instanceof Number n ? n.longValue() : null);
            e.setTradeMarket(str(r.get("trade_market")));
            e.setTradeMarketCode(str(r.get("trade_market_code")));
            e.setDataSource(newCode);
            e.setSrcDetail(srcDetail);
            e.setCreateDate(today);
            e.setUpdateDate(now);
            if (existing != null) {
                dragonTigerMapper.updateRow(e);
            } else {
                dragonTigerMapper.insertIfAbsent(e);
            }
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
            e.setBuy(bigDec(r.get("buy")));
            e.setSell(bigDec(r.get("sell")));
            e.setIsInstitution(intVal(r.get("is_institution")));
            e.setIsFamous(intVal(r.get("is_famous")));
            e.setDataSource(newCode);
            e.setSrcDetail(srcDetail);
            e.setCreateDate(today);
            e.setUpdateDate(now);
            if (existing != null) {
                dtDetailMapper.updateRow(e);
            } else {
                dtDetailMapper.insertIfAbsent(e);
            }
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer intVal(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private static BigDecimal bigDec(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (o instanceof String s && !s.isEmpty()) {
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static LocalDate toLocalDate(Object o) {
        if (o instanceof LocalDate d) {
            return d;
        }
        if (o instanceof java.time.LocalDateTime dt) {
            return dt.toLocalDate();
        }
        if (o != null) {
            String s = String.valueOf(o).replace("-", "");
            // 兼容 YYYY-M-D / YYYY-MM-DD / YYYYMMDD
            if (s.length() >= 8) {
                return LocalDate.parse(s.substring(0, 8), java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            }
            return LocalDate.parse(s);
        }
        return null;
    }
}
