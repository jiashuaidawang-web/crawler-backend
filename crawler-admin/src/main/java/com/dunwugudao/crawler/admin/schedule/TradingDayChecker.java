package com.dunwugudao.crawler.admin.schedule;

import com.dunwugudao.crawler.admin.seed.ProxyManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.function.Predicate;

/**
 * 交易日检查器：通过东财 push2his 接口查询指定日期是否有 K 线数据来判断是否为交易日。
 * <p>原理：上证指数(000001)在交易日有收盘数据，法定假日/周末 K 线为空。</p>
 * <p><b>统一走青果代理</b>（{@link ProxyManager}，内部自动换 IP 重试 + 熔断），
 * 服务器真实 IP 不直连东财。</p>
 * <p>带外层重试(MAX_RETRY=20, 指数退避 200ms→上限30s)。全部失败时返回 false(跳过跑批),
 * 宁可漏跑不可误跑(误跑会写入假日脏数据)。</p>
 */
@Slf4j
@Component
public class TradingDayChecker {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 上证指数 secid — 代表大盘，只要开盘就有日 K */
    private static final String SECID_SH_INDEX = "1.000001";

    /** push2his kline 端点（与 EastmoneyEndpoints 一致，但只取一天的最小集合） */
    private static final String KLINE_URL =
            "https://push2his.eastmoney.com/api/qt/stock/kline/get"
                    + "?secid=" + SECID_SH_INDEX
                    + "&klt=101&fqt=0"
                    + "&beg=%s&end=%s"
                    + "&fields1=f1,f2,f3"
                    + "&fields2=f51,f52,f53,f54,f55,f56,f57";

    /** 响应有效性：rc=0（非交易日同样是 rc=0 + 空 klines，属有效响应，由调用方解析 klines）。 */
    private static final Predicate<String> KLINE_VALIDATOR = resp -> resp.contains("\"rc\":0");

    private final ProxyManager proxyManager;
    private final ObjectMapper objectMapper;

    public TradingDayChecker(ProxyManager proxyManager, ObjectMapper objectMapper) {
        this.proxyManager = proxyManager;
        this.objectMapper = objectMapper;
    }

    /** 最大重试次数（连续失败N次后判定为不可信, 不执行跑批）。 */
    private static final int MAX_RETRY = 20;

    /** 退避基数(毫秒)。第 n 次等待 = min(BACKOFF_BASE_MS * 2^(n-1), MAX_BACKOFF_MS)。 */
    private static final long BACKOFF_BASE_MS = 200;

    /** 单次退避上限, 避免后期等待过久(30秒)。 */
    private static final long MAX_BACKOFF_MS = 30_000;

    /**
     * 判断指定日期是否为交易日（走青果代理，不直连）。
     * <p>策略：外层重试(MAX_RETRY=20 次, 指数退避 200ms→400ms→800ms...→上限30s)，
     * 每次内部经 {@link ProxyManager#executeWithRetry}（最多 10 次、失败自动换 IP）。
     * 全部失败时返回 false(跳过跑批),
     * 宁可漏跑不可误跑(误跑会写入假日脏数据)。</p>
     *
     * @param date 待查日期
     * @return true=交易日(确认有数据), false=非交易日 或 接口不可信(跳过)
     */
    public boolean isTradingDay(LocalDate date) {
        // IP 消耗埋点上下文（避免统计页显示 UNKNOWN）
        proxyManager.setCurrentContext("TRADING_DAY_CHECK", "TRADING_DAY_CHECK", date);
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                return queryKline(date);
            } catch (Exception e) {
                lastError = e;
                if (attempt < MAX_RETRY) {
                    // 严格指数退避: 200ms, 400ms, 800ms, 1.6s, 3.2s, 6.4s, 12.8s, 25.6s, 30s(上限)...
                    long sleepMs = Math.min(BACKOFF_BASE_MS * (1L << (attempt - 1)), MAX_BACKOFF_MS);
                    log.warn("[TradingDayChecker] 第 {}/{} 次查询失败(via proxy, {}), {}ms 后重试: {}",
                            attempt, MAX_RETRY, date, sleepMs, e.getMessage());
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        // 全部重试耗尽, 接口不可信 → 跳过跑批(安全侧: 不漏跑不误跑)
        log.warn("[TradingDayChecker] {} 连续 {} 次查询失败, 跳过跑批. lastError: {}",
                date, MAX_RETRY, lastError != null ? lastError.getMessage() : "unknown");
        return false;
    }

    /**
     * 判断今天是否为交易日（便捷方法）。
     */
    public boolean isTodayTradingDay() {
        return isTradingDay(LocalDate.now());
    }

    // ---------------- 内部实现 ----------------

    /** 查 push2his K 线，有数据=交易日，空=非交易日。统一走 ProxyManager(青果代理)。 */
    private boolean queryKline(LocalDate date) throws Exception {
        String ds = date.format(FMT);
        long ts = System.currentTimeMillis();
        String url = String.format(KLINE_URL + "&_=%d", ds, ds, ts);

        // 走 ProxyManager：青果代理 + 失败自动换 IP(内部最多 10 次) + 熔断
        String resp = proxyManager.executeWithRetry(url, KLINE_VALIDATOR);
        if (resp == null) {
            throw new RuntimeException("trading day query failed via proxy (retries exhausted or circuit breaker open)");
        }

        JsonNode root = objectMapper.readTree(resp);
        int rc = root.path("rc").asInt(-1);
        if (rc != 0) {
            throw new RuntimeException("东财返回 rc=" + rc);
        }
        JsonNode klines = root.path("data").path("klines");
        boolean hasData = klines.isArray() && !klines.isEmpty();

        log.info("[TradingDayChecker] date={} klines.size()={}, isTradingDay={}, via proxy",
                date, klines.isArray() ? klines.size() : -1, hasData);
        return hasData;
    }
}
