package com.dunwugudao.crawler.admin.seed;

import com.dunwugudao.crawler.core.model.SourceType;
import com.dunwugudao.crawler.persistence.mapper.StockDailyMapper;
import com.dunwugudao.crawler.persistence.service.DedupWriter;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 财报种子（financial）。
 * <p>财报非东财 datacenter 直爬（该接口当前 9501 不可用），改由 <b>akshare-bridge</b> 服务获取——
 * akshare 封装了新浪/东财财报页面解析，返回 营收/净利润/同比/ROE 等字段。</p>
 * <p>流程：取股票池（指定 tsCodes 或 StockDailyMapper 最新交易日去重池，封顶 maxStocks）
 * → 逐只 POST {@code {bridgeUrl}/financial} → 聚合全部报告期行 → {@code dedupWriter.write("FINANCIAL", ...)}。</p>
 * <p>bridge 不可达 / 未启用时优雅跳过（返回 0），不影响 admin 启动与其它种子。</p>
 */
@Slf4j
@Service
public class FinancialSeeder {

    private final DedupWriter dedupWriter;
    private final StockDailyMapper stockDailyMapper;
    private final ObjectMapper objectMapper;

    @Value("${akshare.bridge.enabled:false}")
    private boolean bridgeEnabled;
    @Value("${akshare.bridge.url:http://localhost:8800}")
    private String bridgeUrl;
    @Value("${akshare.bridge.max-stocks:200}")
    private int maxStocks;

    public FinancialSeeder(DedupWriter dedupWriter, StockDailyMapper stockDailyMapper, ObjectMapper objectMapper) {
        this.dedupWriter = dedupWriter;
        this.stockDailyMapper = stockDailyMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 拉取财报并写入 financial。
     *
     * @param source  数据源（0=东财，默认 0）
     * @param tsCodes 指定股票池（可空；空则从 stock_daily 最新交易日去重取，封顶 maxStocks）
     * @return 写入行数
     */
    public int seed(int source, List<String> tsCodes) {
        if (!bridgeEnabled || bridgeUrl == null || bridgeUrl.isBlank()) {
            log.warn("[FinancialSeeder] akshare.bridge 未启用或 url 为空，跳过（enable akshare.bridge.enabled=true 并部署 bridge 服务）");
            return 0;
        }
        List<String> codes = (tsCodes != null && !tsCodes.isEmpty()) ? tsCodes : resolveUniverse();
        if (codes.isEmpty()) {
            log.warn("[FinancialSeeder] 股票池为空（未指定 tsCodes 且 stock_daily 无可去重数据），跳过");
            return 0;
        }
        if (codes.size() > maxStocks) {
            codes = codes.subList(0, maxStocks);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int ok = 0, fail = 0;
        for (String code : codes) {
            try {
                List<Map<String, Object>> one = fetchOne(code);
                if (one != null && !one.isEmpty()) {
                    rows.addAll(one);
                    ok++;
                }
            } catch (Exception e) {
                fail++;
                if (fail <= 5) {
                    log.warn("[FinancialSeeder] 个股 {} 拉取失败: {}", code, e.getMessage());
                }
            }
        }
        if (rows.isEmpty()) {
            log.warn("[FinancialSeeder] 0 行财报（bridge 可能未返回数据），ok={}, fail={}", ok, fail);
            return 0;
        }
        dedupWriter.write("FINANCIAL", rows, SourceType.fromCode(source), "akshare_bridge@" + bridgeUrl);
        log.info("[FinancialSeeder] 写入 financial {} 行（覆盖 {} 只股票，fail={}）", rows.size(), ok, fail);
        return rows.size();
    }

    /** 从 stock_daily 最新交易日取去重股票池（不强制后缀，bridge 负责补交易所后缀）。 */
    private List<String> resolveUniverse() {
        try {
            List<String> all = stockDailyMapper.selectDistinctTsCode();
            if (all == null) {
                return new ArrayList<>();
            }
            // 去掉 .SZ/.SH 后缀，统一交 bridge 推断交易所（避免重复推断不一致）
            List<String> cleaned = new ArrayList<>(all.size());
            for (String c : all) {
                if (c == null) {
                    continue;
                }
                int dot = c.indexOf('.');
                cleaned.add(dot >= 0 ? c.substring(0, dot) : c);
            }
            return cleaned;
        } catch (Exception e) {
            log.warn("[FinancialSeeder] 取股票池失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<Map<String, Object>> fetchOne(String code) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        String reqBody = "{\"ts_code\":\"" + code + "\",\"start_year\":\"2018\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(bridgeUrl.replaceAll("/$", "") + "/financial"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(reqBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("bridge HTTP " + resp.statusCode() + " for " + code);
        }
        JsonNode root = objectMapper.readTree(resp.body());
        JsonNode arr = root.path("rows");
        if (!arr.isArray()) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode r : arr) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts_code", r.path("ts_code").asText(code));
            m.put("end_date", r.path("end_date").asText(null));
            m.put("report_type", r.path("report_type").asText(null));
            m.put("ann_date", r.path("ann_date").asText(null));
            m.put("revenue", numOrNull(r, "revenue"));
            m.put("net_profit", numOrNull(r, "net_profit"));
            m.put("net_profit_yoy", numOrNull(r, "net_profit_yoy"));
            m.put("roe", numOrNull(r, "roe"));
            out.add(m);
        }
        return out;
    }

    private Object numOrNull(JsonNode r, String f) {
        JsonNode n = r.path(f);
        if (n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.isNumber()) {
            return n.doubleValue();
        }
        String s = n.asText(null);
        if (s == null || s.isEmpty() || "--".equals(s)) {
            return null;
        }
        try {
            return Double.parseDouble(s.replace(",", "").replace("%", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
