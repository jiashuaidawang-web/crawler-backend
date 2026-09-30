package com.dunwugudao.crawler.admin.backfill;

import com.dunwugudao.crawler.persistence.entity.StockTradeCalendar;
import com.dunwugudao.crawler.persistence.mapper.StockTradeCalendarMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A股交易日历构建器（独立于项目现有 TradeCalendarSeeder）。
 * <p>从 akshare-bridge 拉取1990-12-19至今所有真实交易日，写入 stock_trade_calendar 表。
 * 主路径：bridge 权威数据（已排除周末+法定假日+调休）；降级：周末推断。</p>
 * <p>幂等：ClickHouse ReplacingMergeTree(update_date) 自动覆盖同 trade_date 旧版。</p>
 */
@Slf4j
@Component
public class TradingCalendarBuilder {

    private static final int BATCH = 1000;

    /** A股开市日（上交所 1990-12-19） */
    private static final LocalDate A_SHARE_START = LocalDate.of(1990, 12, 19);

    private final StockTradeCalendarMapper mapper;
    private final ObjectMapper objectMapper;

    @Value("${akshare.bridge.enabled:false}")
    private boolean bridgeEnabled;

    @Value("${akshare.bridge.url:http://localhost:8800}")
    private String bridgeUrl;

    public TradingCalendarBuilder(StockTradeCalendarMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 构建 [from, to] 区间的交易日历并写入 ClickHouse。
     *
     * @param from   起始日期（含），null 则取 1990-12-19
     * @param to     结束日期（含），null 则取今天
     * @param source 数据源标记
     * @return 写入的总天数
     */
    public int buildRange(LocalDate from, LocalDate to, int source) {
        if (from == null) {
            from = A_SHARE_START;
        }
        if (to == null) {
            to = LocalDate.now();
        }

        // 主路径：bridge 权威交易日（全量返回，调用方按区间过滤）；不可用时降级周末推断
        Set<LocalDate> tradingDays = fetchTradingDaysFromBridge();
        boolean authoritative = tradingDays != null;
        if (!authoritative) {
            log.warn("[TradingCalendarBuilder] bridge 不可用，降级为周末推断（法定假日未排除，仅作兜底）");
        }

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        LocalDate d = from;
        List<StockTradeCalendar> batch = new ArrayList<>(BATCH);
        int totalDays = 0;
        int tradingCount = 0;

        while (!d.isAfter(to)) {
            int isTrading;
            if (authoritative) {
                isTrading = tradingDays.contains(d) ? 1 : 0;
            } else {
                // 兜底：仅排除周末
                DayOfWeek dow = d.getDayOfWeek();
                isTrading = (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) ? 1 : 0;
            }
            if (isTrading == 1) {
                tradingCount++;
            }

            StockTradeCalendar t = new StockTradeCalendar();
            t.setTradeDate(d);
            t.setIsTrading(isTrading);
            t.setDataSource(source);
            t.setSrcDetail(authoritative ? "akshare.tool_trade_date_hist_sina" : "weekend_inference_fallback");
            t.setCreateDate(today);
            t.setUpdateDate(now);
            batch.add(t);
            totalDays++;

            if (batch.size() >= BATCH) {
                mapper.batchInsert(batch);
                batch.clear();
            }
            d = d.plusDays(1);
        }
        if (!batch.isEmpty()) {
            mapper.batchInsert(batch);
        }

        log.info("[TradingCalendarBuilder] 日历构建完成: from={}, to={}, 总天数={}, 交易日={}, 来源={}",
                from, to, totalDays, tradingCount, authoritative ? "akshare" : "fallback");
        return totalDays;
    }

    /**
     * 从 akshare-bridge 拉全量交易日集合（bridge 返回全量，调用方按区间过滤）。
     * 返回 null 表示 bridge 不可用，由调用方降级。
     */
    private Set<LocalDate> fetchTradingDaysFromBridge() {
        if (!bridgeEnabled || bridgeUrl == null || bridgeUrl.isBlank()) {
            return null;
        }
        try {
            String url = bridgeUrl.replaceAll("/$", "") + "/trade-calendar";
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                log.warn("[TradingCalendarBuilder] bridge HTTP {}，降级周末推断", resp.statusCode());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode arr = root.path("trade_dates");
            if (!arr.isArray()) {
                log.warn("[TradingCalendarBuilder] bridge 响应格式异常（trade_dates 非数组），降级周末推断");
                return null;
            }
            Set<LocalDate> set = new HashSet<>(arr.size());
            for (JsonNode node : arr) {
                set.add(LocalDate.parse(node.asText()));
            }
            log.info("[TradingCalendarBuilder] bridge 返回 {} 个交易日（全量权威数据）", set.size());
            return set;
        } catch (Exception e) {
            log.warn("[TradingCalendarBuilder] bridge 调用失败({})，降级周末推断", e.getMessage());
            return null;
        }
    }
}
