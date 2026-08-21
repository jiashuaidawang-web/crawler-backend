package com.dunwugudao.crawler.admin.init;

import com.dunwugudao.crawler.persistence.entity.StockBoardRel;
import com.dunwugudao.crawler.persistence.mapper.StockBoardRelMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FilenameFilter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 同花顺股票-板块关系 JSON → stock_board_rel 表初始化服务。
 * <p>读取指定目录下的 *.json 文件，解析后批量写入 ClickHouse。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockBoardRelInitService {

    private final StockBoardRelMapper stockBoardRelMapper;
    private final ObjectMapper objectMapper;

    /** JSON 文件所在目录（可在 application.yml 配置） */
    @Value("${init.stock-board-rel.json-dir:}")
    private String configuredJsonDir;

    // 板块类型：1=地域 2=行业 3=概念
    private static final int BOARD_TYPE_CONCEPT = 3;
    // 数据来源：0=同花顺
    private static final int DATA_SOURCE_TONGHUASHUN = 0;
    // 批量插入每批大小
    private static final int BATCH_SIZE = 1000;

    /**
     * 执行初始化。
     *
     * @param dir JSON 目录（为空则使用配置项，再为空则默认桌面 stockBoardRef）
     * @return 结果统计
     */
    public InitResult init(String dir) {
        String targetDir = resolveDir(dir);
        log.info("[StockBoardRelInit] 开始初始化, 目录={}", targetDir);

        File jsonDirFile = new File(targetDir);
        if (!jsonDirFile.exists() || !jsonDirFile.isDirectory()) {
            throw new IllegalArgumentException("目录不存在: " + targetDir);
        }

        // 收集 JSON 文件（排除 errors_*.json）
        File[] files = jsonDirFile.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                return name.endsWith(".json") && !name.startsWith("errors_");
            }
        });
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("目录中无 JSON 文件: " + targetDir);
        }
        log.info("[StockBoardRelInit] 找到 {} 个 JSON 文件", files.length);

        // 解析所有 JSON
        List<StockBoardRel> rows = new ArrayList<>();
        int parseErrors = 0;
        for (File f : files) {
            try {
                parseJsonFile(f, rows);
            } catch (Exception e) {
                parseErrors++;
                log.warn("[StockBoardRelInit] 解析失败: {} - {}", f.getName(), e.getMessage());
            }
        }
        log.info("[StockBoardRelInit] 解析完成: {} 行, 失败: {}", rows.size(), parseErrors);

        if (rows.isEmpty()) {
            throw new RuntimeException("无有效数据行");
        }

        // 分批写入
        int inserted = 0;
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, rows.size());
            List<StockBoardRel> batch = rows.subList(i, end);
            stockBoardRelMapper.batchInsert(batch);
            inserted += batch.size();
            log.info("[StockBoardRelInit] 已写入 {}/{}", inserted, rows.size());
        }

        InitResult result = new InitResult();
        result.setTotalFiles(files.length);
        result.setTotalRows(inserted);
        result.setParseErrors(parseErrors);
        result.setTargetDir(targetDir);
        log.info("[StockBoardRelInit] 完成! 文件数={}, 写入行数={}, 解析失败={}", files.length, inserted, parseErrors);
        return result;
    }

    private String resolveDir(String dir) {
        if (dir != null && !dir.isBlank()) {
            return dir;
        }
        if (configuredJsonDir != null && !configuredJsonDir.isBlank()) {
            return configuredJsonDir;
        }
        // 默认：用户桌面 stockBoardRef
        return System.getProperty("user.home") + File.separator + "Desktop" + File.separator + "stockBoardRef";
    }

    private void parseJsonFile(File f, List<StockBoardRel> rows) throws Exception {
        JsonNode root = objectMapper.readTree(f);
        String stockCode = root.path("stock_code").asText();
        String marketId = root.path("market_id").asText();
        String tsCode = toTsCode(stockCode, marketId);
        String stockName = root.path("stock_name").asText();
        if (stockName == null || stockName.isEmpty()) {
            stockName = null;
        }

        JsonNode boards = root.path("boards");
        if (!boards.isArray()) {
            return;
        }

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        for (JsonNode b : boards) {
            StockBoardRel rel = new StockBoardRel();
            rel.setTsCode(tsCode);
            rel.setBoardCode(b.path("quote_code").asText());
            rel.setBoardName(b.path("name").asText());
            rel.setStockName(stockName);
            rel.setBoardType(BOARD_TYPE_CONCEPT);

            int fitRank = b.path("fit_rank").asInt(999);
            rel.setIsLeader(fitRank == 1 ? 1 : 0);
            rel.setIsMidarm(fitRank >= 2 && fitRank <= 5 ? 1 : 0);
            rel.setWeight(BigDecimal.valueOf(fitRank));

            rel.setEffectiveDate(today);
            rel.setDataSource(DATA_SOURCE_TONGHUASHUN);
            rel.setSrcDetail(b.path("concept_id").asText());
            rel.setCreateDate(today);
            rel.setUpdateDate(now);

            rows.add(rel);
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

    /** 初始化结果 */
    @Data
    public static class InitResult {
        private int totalFiles;
        private int totalRows;
        private int parseErrors;
        private String targetDir;
    }
}
