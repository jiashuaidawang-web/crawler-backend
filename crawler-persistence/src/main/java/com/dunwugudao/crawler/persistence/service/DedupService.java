package com.dunwugudao.crawler.persistence.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;

/**
 * ClickHouse 去重治理服务（能力：存量去重 + 防未来重复）。
 *
 * <p>背景：CK 行情/资金流/池子表全是 MergeTree 纯追加，同一种子重跑会叠加重复行。
 * 治理分两层：</p>
 * <ol>
 *   <li><b>DB 层</b>：MergeTree → ReplacingMergeTree(_ver)，同自然键只保留 _ver 最大行。
 *       有 update_date 的表 _ver=MATERIALIZED update_date（重跑自动覆盖）；
 *       无 update_date 的表 _ver=now()（兜底）。</li>
 *   <li><b>代码层</b>：{@link DedupWriter} 批内 in-memory 去重（第一道防线）。</li>
 * </ol>
 *
 * <p>每张表的「自然键」= 现有 ORDER BY 键，与 schema 一一对应。</p>
 */
@Slf4j
@Service
public class DedupService {

    /** 单表去重统计。 */
    @Data
    public static class DupStats {
        private String table;
        private long totalRows;
        private long dupGroups;        // 出现重复的键组数
        private long removableRows;    // 可清除行数 = sum(cnt-1)
        private String engine;
        private boolean needsFix;      // 是否仍是 MergeTree（需 engine-fix）
    }

    /** rebuild 结果。 */
    @Data
    public static class RebuildResult {
        private String table;
        private boolean dryRun;
        private DupStats before;
        private Long afterRows;
        private String message;
    }

    /** 表去重配置：自然键列 + 分区表达式 + 版本列来源。 */
    @Data
    public static class TableCfg {
        String table;
        String naturalKey;     // 逗号分隔，如 "ts_code, trade_date"
        String partitionBy;    // 如 "toYYYYMM(trade_date)"，无分区则 null
        String versionCol;     // 用于 _ver 的列名；null 表示无（用 now()）
        boolean alreadyReplacing; // 已是 ReplacingMergeTree 则无需改引擎
    }

    // -------------------------------------------------------------------------
    // 表注册表：覆盖 clickhouse-schema.sql 全部 25 张分析表
    // -------------------------------------------------------------------------

    /** key=表名（小写），按治理优先级排序。 */
    private static final LinkedHashMap<String, TableCfg> REGISTRY = new LinkedHashMap<>();

    static {
        // 行情/周线/分钟
        reg("stock_daily", "ts_code, trade_date", "toYYYYMM(trade_date)", "update_date", false);
        reg("stock_weekly", "ts_code, trade_date", "toYYYYMM(trade_date)", "update_date", false);
        reg("stock_kline_minute", "ts_code, minute_time", "toYYYYMM(trade_date)", null, false);
        reg("index_daily", "index_code, trade_date", "toYYYYMM(trade_date)", "update_date", false);
        reg("board_daily", "board_code, trade_date", "toYYYYMM(trade_date)", "update_date", false);

        // 池子（自然键含 data_source）
        reg("limit_up_pool", "ts_code, trade_date, data_source", "toYYYYMM(trade_date)", "update_date", false);
        reg("limit_down_pool", "ts_code, trade_date, data_source", "toYYYYMM(trade_date)", "update_date", false);
        reg("zhaban_pool", "ts_code, trade_date, data_source", "toYYYYMM(trade_date)", "update_date", false);
        reg("strong_pool", "ts_code, trade_date, data_source", "toYYYYMM(trade_date)", "update_date", false);
        reg("cixin_pool", "ts_code, trade_date, data_source", "toYYYYMM(trade_date)", "update_date", false);

        // 龙虎榜
        reg("dragon_tiger", "ts_code, trade_date", "toYYYYMM(trade_date)", "update_date", false);
        reg("dt_detail", "ts_code, trade_date, seat_name", "toYYYYMM(trade_date)", "update_date", false);

        // 资金流 / 板块关联
        reg("main_fund_flow", "obj_type, ts_code, board_code, index_code, trade_date",
                "toYYYYMM(trade_date)", "update_date", false);
        reg("stock_board_rel", "board_code, ts_code, board_type, data_source",
                "toYYYYMM(effective_date)", null, true); // 已是 ReplacingMergeTree(_ver)

        // 北向 / 财务 / 新闻
        reg("northbound_flow", "trade_date", null, "update_date", false);
        reg("financial", "ts_code, end_date", "toYYYYMM(end_date)", "update_date", false);
        reg("news_event", "event_time, event_id", "toYYYYMM(toDate(event_time))", "update_date", false);

        // 维表（已是 ReplacingMergeTree，无需改引擎，但可 rebuild 去重）
        reg("board_basic", "board_type, board_code, data_source", "toYYYYMM(create_date)", null, true);
        reg("concept", "theme_code", null, null, true);
        reg("trade_calendar", "trade_date", null, null, true);

        // 复盘产出表（无 update_date，_ver=now() 兜底）
        reg("sentiment_daily", "trade_date", null, null, false);
        reg("theme_factor_daily", "board_code, trade_date", "toYYYYMM(trade_date)", null, false);
        reg("trend_candidate_daily", "ts_code, trade_date", "toYYYYMM(trade_date)", null, false);
        reg("four_dimension_daily", "trade_date", null, null, false);
        reg("mainline_daily", "trade_date, board_code", "toYYYYMM(trade_date)", null, false);
        reg("leader_pool_daily", "trade_date, ts_code", "toYYYYMM(trade_date)", null, false);

        // 同花顺板块
        reg("ths_plate", "plate_type, plate_code, trade_date", "toYYYYMM(trade_date)", "update_date", false);
    }

    private static void reg(String table, String key, String part, String ver, boolean replacing) {
        TableCfg c = new TableCfg();
        c.table = table; c.naturalKey = key; c.partitionBy = part;
        c.versionCol = ver; c.alreadyReplacing = replacing;
        REGISTRY.put(table, c);
    }

    private final JdbcTemplate chJdbcTemplate;

    public DedupService(@Qualifier("chJdbcTemplate") JdbcTemplate chJdbcTemplate) {
        this.chJdbcTemplate = chJdbcTemplate;
    }

    public static Map<String, TableCfg> registry() {
        return Collections.unmodifiableMap(REGISTRY);
    }

    // -------------------------------------------------------------------------
    // 统计
    // -------------------------------------------------------------------------

    /** 单表去重统计（空表/不存在返回 total=0）。 */
    public DupStats stats(String table) {
        TableCfg cfg = REGISTRY.get(table);
        if (cfg == null) {
            throw new IllegalArgumentException("未注册的表: " + table + "，请在 DedupService.REGISTRY 中补充");
        }
        DupStats s = new DupStats();
        s.table = table;
        try {
            s.totalRows = chJdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        } catch (Exception e) {
            log.warn("stats: 表 {} 查询失败（可能不存在）: {}", table, e.getMessage());
            s.totalRows = 0;
            s.needsFix = !cfg.alreadyReplacing;
            return s;
        }
        // 重复组 & 可清除行：按自然键分组
        String sql = "SELECT countIf(cnt > 1) AS dupGroups, sumIf(cnt - 1, cnt > 1) AS removable "
                + "FROM (SELECT " + cfg.naturalKey + ", count(*) AS cnt FROM " + table
                + " GROUP BY " + cfg.naturalKey + ")";
        chJdbcTemplate.query(sql, rs -> {
            s.dupGroups = rs.getLong("dupGroups");
            s.removableRows = rs.getLong("removable");
        });
        s.engine = engineOf(table);
        s.needsFix = s.dupGroups > 0 || (!cfg.alreadyReplacing && !s.engine.contains("Replacing"));
        return s;
    }

    /** 全表统计。 */
    public List<DupStats> statsAll() {
        List<DupStats> all = new ArrayList<>();
        for (String table : REGISTRY.keySet()) {
            try {
                all.add(stats(table));
            } catch (Exception e) {
                log.warn("statsAll: 跳过 {}: {}", table, e.getMessage());
            }
        }
        return all;
    }

    /** 查当前引擎。 */
    public String engineOf(String table) {
        try {
            return chJdbcTemplate.queryForObject(
                    "SELECT engine FROM system.tables WHERE database = currentDatabase() AND name = ?",
                    String.class, table);
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    // -------------------------------------------------------------------------
    // 防未来重复：engine-fix（MergeTree → ReplacingMergeTree）
    // -------------------------------------------------------------------------

    /**
     * 把单表改为 ReplacingMergeTree(_ver)。已 Replacing 则跳过。
     * <p>CK 不支持 ALTER MODIFY ENGINE，故用「建新表 → 去重迁移 → RENAME 切换 → DROP 旧表」。</p>
     */
    public RebuildResult engineFix(String table) {
        TableCfg cfg = REGISTRY.get(table);
        if (cfg == null) {
            throw new IllegalArgumentException("未注册的表: " + table);
        }
        String engine = engineOf(table);
        if (engine.contains("Replacing")) {
            RebuildResult r = new RebuildResult();
            r.table = table; r.message = "已是 " + engine + "，无需改引擎";
            return r;
        }
        // 改引擎必然伴随全量迁移，直接走 rebuild（dryRun=false 时执行）
        return rebuild(table, false);
    }

    // -------------------------------------------------------------------------
    // 存量去重：rebuild
    // -------------------------------------------------------------------------

    /**
     * 重建单表：新表 ReplacingMergeTree(_ver) + row_number 去重迁移 + RENAME 原子切换。
     *
     * @param dryRun true=只统计不执行
     */
    public RebuildResult rebuild(String table, boolean dryRun) {
        TableCfg cfg = REGISTRY.get(table);
        if (cfg == null) {
            throw new IllegalArgumentException("未注册的表: " + table);
        }
        DupStats before = stats(table);
        RebuildResult result = new RebuildResult();
        result.table = table;
        result.dryRun = dryRun;
        result.before = before;

        if (before.totalRows == 0) {
            result.message = "表空，无需处理";
            return result;
        }
        if (before.dupGroups == 0) {
            // 无重复，但若是 MergeTree 仍可升级引擎
            String engine = engineOf(table);
            if (!engine.contains("Replacing")) {
                if (dryRun) {
                    result.message = "无重复，dry-run：将升级引擎 " + engine + " → ReplacingMergeTree(_ver)";
                } else {
                    result.message = doRebuild(table, cfg, false);
                }
            } else {
                result.message = "无重复，引擎已是 " + engine + "，无需处理";
            }
            return result;
        }

        if (dryRun) {
            result.message = String.format("dry-run：将清除 %d 行重复（%d 组），重建为 ReplacingMergeTree(_ver)",
                    before.removableRows, before.dupGroups);
            return result;
        }
        result.message = doRebuild(table, cfg, true);
        // 重建后重统计
        try {
            result.afterRows = chJdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        } catch (Exception ignored) {
        }
        return result;
    }

    /** 实际执行 rebuild：建 _new → 去重插入 → RENAME 切换 → DROP 旧表。 */
    private String doRebuild(String table, TableCfg cfg, boolean dedup) {
        String newName = table + "_new";
        try {
            // 1. 列清单（保留原有列，追加 _ver）
            List<String[]> cols = columnsOf(table); // [name, typeDef]
            StringBuilder colDefs = new StringBuilder();
            StringBuilder colNames = new StringBuilder();
            for (String[] c : cols) {
                if ("_ver".equals(c[0])) continue; // 旧表不应有 _ver，防御
                if (colDefs.length() > 0) {
                    colDefs.append(", ");
                    colNames.append(", ");
                }
                colDefs.append(c[0]).append(" ").append(c[1]);
                colNames.append(c[0]);
            }
            // _ver 定义：有 versionCol 则 MATERIALIZED，否则 now()
            String verExpr = cfg.versionCol != null
                    ? "coalesce(" + cfg.versionCol + ", toDateTime(0))"
                    : "now()";
            colDefs.append(", _ver DateTime MATERIALIZED ").append(verExpr);

            // 2. 建 _new 表
            String create = "CREATE TABLE " + newName + " ("
                    + colDefs + ") ENGINE = ReplacingMergeTree(_ver) ";
            if (cfg.partitionBy != null) {
                create += "PARTITION BY " + cfg.partitionBy + " ";
            }
            create += "ORDER BY (" + cfg.naturalKey + ") SETTINGS index_granularity = 8192";
            log.info("[dedup] CREATE {}: {}", newName, create);
            chJdbcTemplate.execute(create);

            // 3. 去重迁移
            String insert;
            if (dedup) {
                // row_number 窗口：同自然键保留 _ver 最大（最新）行
                String verOrder = cfg.versionCol != null ? cfg.versionCol : "1";
                insert = "INSERT INTO " + newName + " (" + colNames + ") "
                        + "SELECT " + colNames + " FROM ("
                        + "SELECT " + colNames + ", row_number() OVER ("
                        + "PARTITION BY " + cfg.naturalKey
                        + " ORDER BY " + verOrder + " DESC) AS rn FROM " + table
                        + ") WHERE rn = 1";
            } else {
                insert = "INSERT INTO " + newName + " (" + colNames + ") SELECT " + colNames + " FROM " + table;
            }
            log.info("[dedup] INSERT {}", newName);
            chJdbcTemplate.execute(insert);

            // 4. RENAME 原子切换（CK 支持单语句多表重命名）
            log.info("[dedup] RENAME {} -> {}_del, {} -> {}", table, table, newName, table);
            chJdbcTemplate.execute(
                    "RENAME TABLE " + table + " TO " + table + "_del,"
                    + newName + " TO " + table);

            // 5. DROP 旧表
            chJdbcTemplate.execute("DROP TABLE " + table + "_del");
            return "rebuild 完成：已切换为 ReplacingMergeTree(_ver)" + (dedup ? " 并去重" : "");
        } catch (Exception e) {
            log.error("[dedup] rebuild {} 失败: {}", table, e.getMessage(), e);
            // 清理残留 _new
            try {
                chJdbcTemplate.execute("DROP TABLE IF EXISTS " + newName);
            } catch (Exception ignored) {
            }
            return "rebuild 失败: " + e.getMessage();
        }
    }

    /** 读 system.columns 拿到 [name, typeDef] 列表。 */
    private List<String[]> columnsOf(String table) {
        return chJdbcTemplate.query(
                "SELECT name, type FROM system.columns WHERE database = currentDatabase() AND table = ? ORDER BY position",
                (rs, i) -> new String[]{rs.getString("name"), rs.getString("type")}, table);
    }

    // -------------------------------------------------------------------------
    // 批内去重工具（供 DedupWriter 调用）
    // -------------------------------------------------------------------------

    /**
     * 对同 batch 内按自然键去重（保留最后一条）。
     *
     * @param rows       行数据
     * @param keyColumns 构成自然键的列名
     * @param <T>        Map 行
     * @return 去重后的列表
     */
    public static <T extends Map<String, Object>> List<T> dedupInBatch(List<T> rows, List<String> keyColumns) {
        if (rows == null || rows.size() <= 1) return rows;
        // 保留最后出现的：倒序遍历，见过键就跳过
        LinkedHashSet<T> reversed = new LinkedHashSet<>();
        List<T> copy = new ArrayList<>(rows);
        for (int i = copy.size() - 1; i >= 0; i--) {
            T r = copy.get(i);
            String key = keyOf(r, keyColumns);
            if (key != null) reversed.add(r); // LinkedHashSet 保留首次（即原序末位）
        }
        List<T> out = new ArrayList<>(reversed.size());
        // reversed 现在是「原序末位优先」，再倒回来恢复原序
        List<T> revList = new ArrayList<>(reversed);
        Collections.reverse(revList);
        return revList;
    }

    private static String keyOf(Map<String, Object> row, List<String> keyColumns) {
        StringBuilder sb = new StringBuilder();
        for (String k : keyColumns) {
            Object v = row.get(k);
            if (v == null) return null; // 缺键列 → 不参与去重（留给 writer 跳过）
            sb.append(k).append('=').append(v).append('|');
        }
        return sb.toString();
    }
}
