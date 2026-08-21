package com.dunwugudao.crawler.admin.init;

import com.dunwugudao.crawler.persistence.entity.StockAnomaly;
import com.dunwugudao.crawler.persistence.mapper.StockAnomalyMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FilenameFilter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 同花顺股票异动 JSON → stock_anomaly 表初始化服务。
 * <p>读取 ths_anomaly_data/stocks/*.json，解析后批量写入 ClickHouse。
 * 去重由 ReplacingMergeTree(data_source) 承接（anomaly_id 全局唯一）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAnomalyInitService {

    private final StockAnomalyMapper stockAnomalyMapper;
    private final ObjectMapper objectMapper;

    /** JSON 文件所在目录（可在 application.yml 配置） */
    @Value("${init.stock-anomaly.json-dir:}")
    private String configuredJsonDir;

    // 数据来源：0=同花顺
    private static final int DATA_SOURCE_TONGHUASHUN = 0;
    // 批量插入每批大小
    private static final int BATCH_SIZE = 1000;

    /**
     * 执行初始化。
     *
     * @param dir JSON 目录（为空则使用配置项，再为空则默认桌面 ths_anomaly_data/stocks）
     * @return 结果统计
     */
    public InitResult init(String dir) {
        String targetDir = resolveDir(dir);
        log.info("[StockAnomalyInit] 开始初始化, 目录={}", targetDir);

        File jsonDirFile = new File(targetDir);
        if (!jsonDirFile.exists() || !jsonDirFile.isDirectory()) {
            throw new IllegalArgumentException("目录不存在: " + targetDir);
        }

        // 收集 JSON 文件
        File[] files = jsonDirFile.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                return name.endsWith(".json");
            }
        });
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("目录中无 JSON 文件: " + targetDir);
        }
        log.info("[StockAnomalyInit] 找到 {} 个 JSON 文件", files.length);

        // 解析所有 JSON
        List<StockAnomaly> rows = new ArrayList<>();
        int parseErrors = 0;
        for (File f : files) {
            try {
                parseJsonFile(f, rows);
            } catch (Exception e) {
                parseErrors++;
                log.warn("[StockAnomalyInit] 解析失败: {} - {}", f.getName(), e.getMessage());
            }
        }
        log.info("[StockAnomalyInit] 解析完成: {} 行, 失败: {}", rows.size(), parseErrors);

        if (rows.isEmpty()) {
            throw new RuntimeException("无有效数据行");
        }

        // 增量去重：过滤数据库中已存在的 anomaly_id
        List<StockAnomaly> newRows = filterExisting(rows);
        log.info("[StockAnomalyInit] 增量过滤: 解析 {} 行 → 新增 {} 行 (已存在 {} 行跳过)",
                rows.size(), newRows.size(), rows.size() - newRows.size());

        if (newRows.isEmpty()) {
            log.info("[StockAnomalyInit] 无新增数据，跳过写入");
            InitResult result = new InitResult();
            result.setTotalFiles(files.length);
            result.setTotalRows(0);
            result.setParseErrors(parseErrors);
            result.setTargetDir(targetDir);
            return result;
        }

        // 分批写入
        int inserted = 0;
        for (int i = 0; i < newRows.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, newRows.size());
            List<StockAnomaly> batch = newRows.subList(i, end);
            stockAnomalyMapper.batchInsert(batch);
            inserted += batch.size();
            log.info("[StockAnomalyInit] 已写入 {}/{}", inserted, newRows.size());
        }

        InitResult result = new InitResult();
        result.setTotalFiles(files.length);
        result.setTotalRows(inserted);
        result.setParseErrors(parseErrors);
        result.setTargetDir(targetDir);
        log.info("[StockAnomalyInit] 完成! 文件数={}, 新增行数={}, 解析失败={}", files.length, inserted, parseErrors);
        return result;
    }

    private String resolveDir(String dir) {
        if (dir != null && !dir.isBlank()) {
            return dir;
        }
        if (configuredJsonDir != null && !configuredJsonDir.isBlank()) {
            return configuredJsonDir;
        }
        // 默认：用户桌面 ths_anomaly_data/stocks
        return System.getProperty("user.home") + File.separator + "Desktop"
                + File.separator + "ths_anomaly_data" + File.separator + "stocks";
    }

    private void parseJsonFile(File f, List<StockAnomaly> rows) throws Exception {
        JsonNode root = objectMapper.readTree(f);
        String stockCode = root.path("stock_code").asText();
        String marketId = root.path("market_id").asText();
        String tsCode = toTsCode(stockCode, marketId);
        String stockName = root.path("stock_name").asText();
        if (stockName == null || stockName.isEmpty()) {
            stockName = null;
        }

        JsonNode anomalyList = root.path("anomaly_list");
        if (!anomalyList.isArray()) {
            return;
        }

        LocalDate today = LocalDate.now();
        // 截断到秒精度，避免纳秒格式(如 13:24:23.2546284)导致 ClickHouse DateTime 解析失败
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        for (JsonNode a : anomalyList) {
            StockAnomaly anomaly = new StockAnomaly();
            anomaly.setTsCode(tsCode);
            anomaly.setAnomalyId(a.path("id").asLong());
            anomaly.setAnomalyDate(parseDate(a.path("date").asText()));
            anomaly.setTagCode(a.path("tagCode").asText());
            anomaly.setTagName(a.path("tagName").asText());
            anomaly.setReason(a.path("reason").asText());

            // keywords 数组 → JSON 字符串
            JsonNode kwList = a.path("keywordList");
            if (kwList.isArray()) {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < kwList.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(kwList.get(i).asText()).append("\"");
                }
                sb.append("]");
                anomaly.setKeywords(sb.toString());
            } else {
                anomaly.setKeywords("[]");
            }

            anomaly.setStockName(stockName);
            // feature = 原始 JSON 串
            anomaly.setFeature(a.toString());
            anomaly.setDataSource(DATA_SOURCE_TONGHUASHUN);
            anomaly.setCreateDate(today);
            anomaly.setUpdateDate(now);

            rows.add(anomaly);
        }
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr);
        } catch (Exception e) {
            return null;
        }
    }

    private String toTsCode(String stockCode, String marketId) {
        switch (marketId) {
            case "17":
            case "128":
                return stockCode + ".SH";
            case "33":
            case "48":
                return stockCode + ".SZ";
            default:
                if (stockCode.startsWith("60") || stockCode.startsWith("68")) {
                    return stockCode + ".SH";
                }
                return stockCode + ".SZ";
        }
    }

    /**
     * 增量去重：过滤掉数据库中已存在的 anomaly_id。
     * 分批查询避免 IN 列表过大。
     */
    private List<StockAnomaly> filterExisting(List<StockAnomaly> rows) {
        // 提取所有 anomaly_id
        List<Long> allIds = rows.stream()
                .map(StockAnomaly::getAnomalyId)
                .distinct()
                .collect(java.util.stream.Collectors.toList());

        // 分批查询已存在的 ID（每批 5000）
        Set<Long> existingIds = new java.util.HashSet<>();
        int queryBatch = 5000;
        for (int i = 0; i < allIds.size(); i += queryBatch) {
            int end = Math.min(i + queryBatch, allIds.size());
            List<Long> batch = allIds.subList(i, end);
            List<Long> found = stockAnomalyMapper.findExistingAnomalyIds(batch);
            existingIds.addAll(found);
        }

        // 过滤
        List<StockAnomaly> newRows = rows.stream()
                .filter(r -> !existingIds.contains(r.getAnomalyId()))
                .collect(java.util.stream.Collectors.toList());
        return newRows;
    }

    /** 初始化结果 */
    @Data
    public static class InitResult {
        private int totalFiles;
        private int totalRows;
        private int parseErrors;
        private String targetDir;
    }
}
