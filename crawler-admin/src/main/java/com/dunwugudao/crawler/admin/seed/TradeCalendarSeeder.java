package com.dunwugudao.crawler.admin.seed;

import com.dunwugudao.crawler.core.util.DateTimeUtil;
import com.dunwugudao.crawler.persistence.entity.TradeCalendar;
import com.dunwugudao.crawler.persistence.mapper.TradeCalendarMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
 * 交易日历种子（trade_calendar）。
 * <p>主路径：从 akshare-bridge 拉交易所真实交易日（已排除周末 + 法定假日 + 调休上班日），
 * 权威可靠；bridge 不可用时降级为「周末推断」（仅排除周六日，工作日全标交易日，法定假日误差见注释）。</p>
 * <p>重复 seed 同 trade_date 由 ClickHouse ReplacingMergeTree(_ver) 自动覆盖，幂等。</p>
 */
@Slf4j
@Service
public class TradeCalendarSeeder {

    private static final int BATCH = 1000;

    private final TradeCalendarMapper tradeCalendarMapper;
    private final ObjectMapper objectMapper;

    @Value("${akshare.bridge.enabled:false}")
    private boolean bridgeEnabled;

    @Value("${akshare.bridge.url:http://localhost:8800}")
    private String bridgeUrl;

    public TradeCalendarSeeder(TradeCalendarMapper tradeCalendarMapper, ObjectMapper objectMapper) {
        this.tradeCalendarMapper = tradeCalendarMapper;
        this.objectMapper = objectMapper;
    }

    /** 生成 [from,to] 区间日历，返回写入条数。from/to 为 null 时取默认区间。 */
    public int seedRange(LocalDate from, LocalDate to, int source) {
        if (from == null) {
            from = LocalDate.of(2020, 1, 1);
        }
        if (to == null) {
            to = LocalDate.of(2030, 12, 31);
        }
        // 主路径：bridge 权威交易日；不可用时降级周末推断
        Set<LocalDate> tradingDays = fetchTradingDaysFromBridge();
        boolean authoritative = tradingDays != null;
        if (!authoritative) {
            log.warn("[TradeCalendarSeeder] bridge 不可用，降级为周末推断（法定假日未排除，仅作兜底）");
        }

        LocalDate today = LocalDate.now();
        LocalDateTime now = DateTimeUtil.nowSeconds();
        LocalDate d = from;
        List<TradeCalendar> batch = new ArrayList<>(BATCH);
        int n = 0;
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
            TradeCalendar t = new TradeCalendar();
            t.setTradeDate(d);
            t.setIsTrading(isTrading);
            t.setDataSource(source);
            t.setSrcDetail(authoritative ? "akshare.tool_trade_date_hist_sina" : "weekend_inference_fallback");
            t.setCreateDate(today);
            t.setUpdateDate(java.sql.Timestamp.valueOf(now));
            batch.add(t);
            n++;
            if (batch.size() >= BATCH) {
                tradeCalendarMapper.batchInsert(batch);
                batch.clear();
            }
            d = d.plusDays(1);
        }
        if (!batch.isEmpty()) {
            tradeCalendarMapper.batchInsert(batch);
        }
        log.info("[TradeCalendarSeeder] seedRange from={}, to={}, days={}, tradingDays={}, source={}, source_type={}",
                from, to, n, tradingCount, source, authoritative ? "akshare" : "fallback");
        return n;
    }

    /** 从 bridge 拉全量交易日集合；失败返回 null（由调用方降级）。 */
    private Set<LocalDate> fetchTradingDaysFromBridge() {
        if (!bridgeEnabled || bridgeUrl == null || bridgeUrl.isBlank()) {
            return null;
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(bridgeUrl.replaceAll("/$", "") + "/trade-calendar"))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[TradeCalendarSeeder] bridge HTTP {}，降级周末推断", resp.statusCode());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode arr = root.path("trade_dates");
            if (!arr.isArray()) {
                return null;
            }
            Set<LocalDate> set = new HashSet<>(arr.size());
            for (JsonNode node : arr) {
                set.add(LocalDate.parse(node.asText()));
            }
            log.info("[TradeCalendarSeeder] bridge 返回 {} 个交易日（权威：已含法定假日/调休处理）", set.size());
            return set;
        } catch (Exception e) {
            log.warn("[TradeCalendarSeeder] bridge 调用失败({})，降级周末推断", e.getMessage());
            return null;
        }
    }
}
