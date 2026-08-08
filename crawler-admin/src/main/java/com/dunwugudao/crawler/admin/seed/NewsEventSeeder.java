package com.dunwugudao.crawler.admin.seed;

import com.dunwugudao.crawler.core.model.SourceType;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 新闻/政策/题材事件种子（news_event）。
 * <p>纯 Java 直连东财 7×24 快讯端点（即 akshare {@code stock_info_global_em()} 的真实数据源，
 * 无需 akshare / 代理）：</p>
 * <pre>
 *   https://np-weblist.eastmoney.com/comm/web/getFastNewsList
 *     ?client=web&biz=web_724&fastColumn=102&pageSize=N&req_trace=...
 * </pre>
 * <p>响应 {@code data.fastNewsList[]} 每条含 {@code title/summary/showTime/code/stockList}，
 * 其中 {@code stockList}（如 {@code ["90.BK1211","1007.BK000060"]}）已是板块代码，
 * 去掉前缀即 {@code related_board}，可直接支撑 S7 题材催化与 S1 政策维。</p>
 * <p>说明：{@code event_id} 直接用东财返回的 {@code code}（唯一快讯 ID）；
 * {@code sentiment_score} 与精细 {@code related_ts_code} 由后续情感分类/实体链接步骤填充（留 null）。</p>
 */
@Slf4j
@Service
public class NewsEventSeeder {

    private static final String ENDPOINT =
            "https://np-weblist.eastmoney.com/comm/web/getFastNewsList";
    private static final DateTimeFormatter SHOW_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private final DedupWriter dedupWriter;
    private final ObjectMapper objectMapper;

    @Value("${news.crawl.limit:200}")
    private int defaultLimit;

    public NewsEventSeeder(DedupWriter dedupWriter, ObjectMapper objectMapper) {
        this.dedupWriter = dedupWriter;
        this.objectMapper = objectMapper;
    }

    /**
     * 拉取最新快讯并写入 news_event。
     *
     * @param source 数据源（0=东财，默认 0）
     * @param limit  拉取条数（默认 200，上限 500）
     * @return 写入行数
     */
    public int seedRecent(int source, Integer limit) {
        int n = (limit != null && limit > 0) ? Math.min(limit, 500) : defaultLimit;
        String url = ENDPOINT + "?client=web&biz=web_724&fastColumn=102&sortEnd=&pageSize=" + n
                + "&req_trace=" + System.currentTimeMillis();
        String body;
        try {
            body = fetch(url);
        } catch (Exception e) {
            log.error("[NewsEventSeeder] 拉取快讯失败: {}", e.getMessage(), e);
            return 0;
        }
        List<Map<String, Object>> rows = parse(body);
        if (rows.isEmpty()) {
            log.warn("[NewsEventSeeder] 解析到 0 条快讯（端点可能变动），未写入");
            return 0;
        }
        dedupWriter.write("NEWS_EVENT", rows, SourceType.fromCode(source), "eastmoney_7x24@getFastNewsList");
        log.info("[NewsEventSeeder] 写入 news_event {} 条", rows.size());
        return rows.size();
    }

    /** 解析 7×24 响应为 news_event 行（key=目标表列名）。 */
    private List<Map<String, Object>> parse(String body) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode list = root.path("data").path("fastNewsList");
            if (list.isMissingNode() || !list.isArray()) {
                log.warn("[NewsEventSeeder] 响应结构异常（无 data.fastNewsList）: {}",
                        body.length() > 300 ? body.substring(0, 300) : body);
                return rows;
            }
            for (JsonNode it : list) {
                String code = it.path("code").asText(null);
                String title = it.path("title").asText(null);
                String summary = it.path("summary").asText(null);
                String showTime = it.path("showTime").asText(null);
                if (code == null || title == null) {
                    continue;
                }
                Long eventId;
                try {
                    eventId = Long.parseLong(code);
                } catch (NumberFormatException ex) {
                    eventId = (long) Math.abs(Objects.hash(code, showTime, title));
                }
                LocalDateTime eventTime = null;
                if (showTime != null) {
                    try {
                        eventTime = LocalDateTime.parse(showTime, SHOW_FMT);
                    } catch (Exception ignored) {
                        eventTime = null;
                    }
                }
                String relatedBoard = parseRelatedBoard(it.path("stockList"));
                String text = (title + " " + (summary == null ? "" : summary));
                String category = classifyCategory(text);
                int isPolicy = "政策".equals(category) ? 1 : 0;

                Map<String, Object> r = new LinkedHashMap<>();
                r.put("event_id", eventId);
                r.put("event_time", eventTime);
                r.put("title", title);
                r.put("content", summary);
                r.put("source", "eastmoney_7x24");
                r.put("category", category);
                r.put("related_board", relatedBoard);     // 板块代码逗号分隔
                r.put("related_ts_code", null);            // TODO 实体链接填充个股
                r.put("sentiment_score", null);            // TODO 情感分类填充
                r.put("is_policy", isPolicy);
                r.put("src_detail", "https://finance.eastmoney.com/a/" + code + ".html");
                rows.add(r);
            }
        } catch (Exception e) {
            log.error("[NewsEventSeeder] 解析失败: {}", e.getMessage(), e);
        }
        return rows;
    }

    /** stockList 形如 ["90.BK1211","1007.BK000060"] → "BK1211,BK000060"。 */
    private String parseRelatedBoard(JsonNode stockList) {
        if (stockList == null || !stockList.isArray() || stockList.isEmpty()) {
            return null;
        }
        List<String> boards = new ArrayList<>();
        for (JsonNode s : stockList) {
            String v = s.asText(null);
            if (v == null) {
                continue;
            }
            int dot = v.indexOf('.');
            String b = dot >= 0 ? v.substring(dot + 1) : v;
            if (!b.isEmpty()) {
                boards.add(b);
            }
        }
        return boards.isEmpty() ? null : String.join(",", boards);
    }

    /** 极简分类：政策关键词 → 政策；行业/板块/产业链 → 行业；否则 题材。 */
    private String classifyCategory(String text) {
        if (text == null) {
            return "题材";
        }
        if (text.contains("政策") || text.contains("国务院") || text.contains("央行")
                || text.contains("证监会") || text.contains("发改委") || text.contains("财政部")
                || text.contains("政治局") || text.contains("印发") || text.contains("通知")
                || text.contains("会议") && text.contains("部署")) {
            return "政策";
        }
        if (text.contains("行业") || text.contains("板块") || text.contains("产业链")
                || text.contains("概念") || text.contains("指数")) {
            return "行业";
        }
        return "题材";
    }

    private String fetch(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Referer", "https://kuaixun.eastmoney.com/")
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }
}
