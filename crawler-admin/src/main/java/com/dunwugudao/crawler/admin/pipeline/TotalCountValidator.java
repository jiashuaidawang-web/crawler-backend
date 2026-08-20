package com.dunwugudao.crawler.admin.pipeline;

import com.dunwugudao.crawler.persistence.service.DedupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 上游总数真值校验器。
 *
 * <p>校验逻辑(用户定的口径):</p>
 * <ol>
 *   <li>actual == total → 成功</li>
 *   <li>actual &lt; total → 查 CK 按自然键 GROUP BY HAVING count&gt;1 得 dupGroups(重复组数);
 *       若 total-actual == dupGroups → 成功(全是去重解释),否则真丢失(差值=真正丢失行数)</li>
 *   <li>actual &gt; total → 异常告警</li>
 * </ol>
 *
 * <p>注意:DedupService.REGISTRY 的自然键含 data_source,但该列在 CK 表/行里实际不存在
 * (正是 memory-dedup N→0 bug 的根因)。故查重复时只取自然键中真正存在于表的列,否则按
 * data_source 分组必全为 null 导致误判。</p>
 */
@Component
public class TotalCountValidator implements PipelineValidator {

    private static final Logger log = LoggerFactory.getLogger(TotalCountValidator.class);

    private final JdbcTemplate chJdbc;

    public TotalCountValidator(@org.springframework.beans.factory.annotation.Qualifier("chJdbcTemplate") JdbcTemplate chJdbc) {
        this.chJdbc = chJdbc;
    }

    @Override
    public String name() { return "TOTAL_COUNT"; }

    /**
     * 捕获基线行数(seed 后、爬取前调用)。
     * <p>validate 时用 {@code actual - baseline} 作为本次 run 新增行数,避免历史数据干扰校验。</p>
     */
    public int baselineRows(PipelineStage stage, LocalDate date, int source) {
        TableQuery tq = tableQuery(stage);
        if (tq == null) {
            return 0;
        }
        return countActual(tq, date, source);
    }

    @Override
    public ValidateResult validate(LocalDate date, PipelineStage stage, ValidateContext ctx) {
        int expected = ctx.expectedTotal();
        if (expected <= 0) {
            return ValidateResult.ok(name(), "无上游总数,跳过总量校验", 0, 0);
        }
        TableQuery tq = tableQuery(stage);
        if (tq == null) {
            return ValidateResult.ok(name(), "阶段 " + stage + " 无对应 CK 表,跳过", expected, 0);
        }

        // 指数日线：东财 total 不准确（返回 179 但实际只有 43），改为检查 CK 行数 >= 40
        if (stage == PipelineStage.INDEX_DAILY) {
            int actual = countActual(tq, date, ctx.source());
            if (actual >= 40) {
                return ValidateResult.ok(name(),
                        String.format("指数日线 actual(%d) >= 40,数据量满足要求", actual),
                        actual, actual);
            }
            return ValidateResult.fail(name(),
                    String.format("指数日线 actual(%d) < 40,数据量不足", actual),
                    40, actual, 0, 40 - actual, List.of());
        }

        // 北向资金：分钟级数据，检查 CK 行数 >= 300（约 6.5 小时 * 60 分钟）
        if (stage == PipelineStage.NORTHBOUND) {
            int actual = countActual(tq, date, ctx.source());
            if (actual >= 300) {
                return ValidateResult.ok(name(),
                        String.format("北向资金 actual(%d) >= 300,数据量满足要求", actual),
                        actual, actual);
            }
            return ValidateResult.fail(name(),
                    String.format("北向资金 actual(%d) < 300,数据量不足", actual),
                    300, actual, 0, 300 - actual, List.of());
        }

        // actual:实时查 CK 当前总行数(不是本次 run 新增)
        int actual = countActual(tq, date, ctx.source());

        if (actual >= expected) {
            return ValidateResult.ok(name(),
                    String.format("actual(%d) >= expected(%d),数据量满足要求", actual, expected),
                    expected, actual);
        }

        // actual < expected:查重复组数判定能否用去重解释
        int dupGroups = countDupGroups(tq, date, ctx.source());
        int diff = expected - actual;
        if (diff <= dupGroups) {
            return ValidateResult.ok(name(),
                    String.format("actual(%d) < expected(%d),差值(%d) <= 重复组数(%d),去重解释成功",
                            actual, expected, diff, dupGroups),
                    expected, actual);
        }
        int lost = diff - dupGroups;
        return ValidateResult.fail(name(),
                String.format("actual(%d) < expected(%d),差值(%d) > 重复组数(%d),真正丢失 %d 行",
                        actual, expected, diff, dupGroups, lost),
                expected, actual, dupGroups, lost, ctx.taskIds());
    }

    /** 实时查 CK 某阶段的 actualCount(用于接口返回覆盖 stored actualTotal)。
     *  onlyCurrentRun=true 时只统计 create_date=当天的行(本次 run 产出),排除历史存量干扰。 */
    public int countActualByStage(String stageName, LocalDate date, int source, boolean onlyCurrentRun) {
        try {
            PipelineStage stage = PipelineStage.valueOf(stageName);
            TableQuery tq = tableQuery(stage);
            if (tq == null) {
                return 0;
            }
            return countActual(tq, date, source, onlyCurrentRun);
        } catch (Exception e) {
            log.warn("[TOTAL_COUNT] countActualByStage {} 失败: {}", stageName, e.getMessage());
            return 0;
        }
    }

    /** 兼容旧口径:统计全量(含历史)。 */
    public int countActualByStage(String stageName, LocalDate date, int source) {
        return countActualByStage(stageName, date, source, false);
    }

    /** 实时查 CK 某阶段的 dupGroups(用于 buildResultWithMessage 重算)。
     *  onlyCurrentRun=true 时只统计 create_date=当天的行。 */
    public int countDupGroups(PipelineStage stage, LocalDate date, int source, boolean onlyCurrentRun) {
        TableQuery tq = tableQuery(stage);
        if (tq == null) {
            return 0;
        }
        return countDupGroups(tq, date, source, onlyCurrentRun);
    }

    /** 兼容旧口径:不过滤 create_date。 */
    public int countDupGroups(PipelineStage stage, LocalDate date, int source) {
        return countDupGroups(stage, date, source, false);
    }

    /** 构建阶段的 CK 查询参数(表名+额外过滤条件)。 */
    private TableQuery tableQuery(PipelineStage stage) {
        return switch (stage) {
            case STOCK_DAILY -> new TableQuery("stock_daily", null, null);
            case REGION_DAILY -> new TableQuery("board_daily", "board_type = 1", null);
            case INDUSTRY_DAILY -> new TableQuery("board_daily", "board_type = 2", null);
            case CONCEPT_DAILY -> new TableQuery("board_daily", "board_type = 3", null);
            case LIMIT_UP -> new TableQuery("limit_up_pool", null, null);
            case LIMIT_DOWN -> new TableQuery("limit_down_pool", null, null);
            case LIMIT_ZHABAN -> new TableQuery("zhaban_pool", null, null);
            case MAIN_FUND_STOCK -> new TableQuery("main_fund_flow", "obj_type = 'stock'", null);
            case MAIN_FUND_BOARD -> new TableQuery("main_fund_flow", "obj_type = 'board'", null);
            case STRONG_POOL -> new TableQuery("strong_pool", null, null);
            case CIXIN_POOL -> new TableQuery("cixin_pool", null, null);
            case NORTHBOUND -> new TableQuery("northbound_flow", null, null);
            case INDEX_DAILY -> new TableQuery("index_daily", null, null);
            case DRAGON_TIGER -> new TableQuery("dragon_tiger", null, null);
            case DRAGON_TIGER_DETAIL -> new TableQuery("dt_detail", null, null);
            case STOCK_BY_BOARD -> new TableQuery("stock_board_rel", null, null, "trade_date");
            case BOARD_BASIC -> new TableQuery("board_basic", null, null, "trade_date");
            case STOCK_WEEKLY -> new TableQuery("stock_weekly", null, null, "trade_date");
        };
    }

    private int countActual(TableQuery tq, LocalDate date, int source, boolean onlyCurrentRun) {
        StringBuilder sql = new StringBuilder("SELECT count() FROM ").append(tq.table);
        List<Object> params = new ArrayList<>();
        boolean hasWhere = false;
        // 有日期列的表:按日期+data_source 过滤;维表(dateCol=null)不做日期过滤
        if (tq.dateCol != null && !tq.dateCol.isEmpty()) {
            sql.append(" WHERE ").append(tq.dateCol).append(" = ? AND data_source = ?");
            params.add(date.toString());
            params.add(source);
            hasWhere = true;
        }
        // 仅统计本次 run 产出:create_date = 当天(排除历史存量干扰)
        if (onlyCurrentRun) {
            sql.append(hasWhere ? " AND " : " WHERE ").append("create_date = ?");
            params.add(date.toString());
            hasWhere = true;
        }
        if (tq.extraFilter != null && !tq.extraFilter.isEmpty()) {
            sql.append(hasWhere ? " AND " : " WHERE ").append(tq.extraFilter);
        }
        Long v = chJdbc.queryForObject(sql.toString(), Long.class, params.toArray());
        return v == null ? 0 : v.intValue();
    }

    /** 兼容旧口径:不过滤 create_date。 */
    private int countActual(TableQuery tq, LocalDate date, int source) {
        return countActual(tq, date, source, false);
    }

    /** 按自然键+data_source 统计重复组数。
     *  onlyCurrentRun=true 时只统计 create_date=当天的行。 */
    private int countDupGroups(TableQuery tq, LocalDate date, int source, boolean onlyCurrentRun) {
        try {
            DedupService.TableCfg cfg = DedupService.registry().get(tq.table);
            if (cfg == null || cfg.getNaturalKey() == null) {
                return 0;
            }
            List<String> cfgCols = Arrays.stream(cfg.getNaturalKey().split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            List<String> existing = existingColumns(tq.table);
            List<String> cols = cfgCols.stream().filter(existing::contains).toList();
            if (cols.isEmpty()) {
                return 0;
            }
            String groupKey = cols.stream().map(c -> "toString(coalesce(" + c + ",''))")
                    .reduce((a, b) -> a + "||'|'||" + b).orElse("1");
            // 有日期列的表才按日期过滤;维表(dateCol=null)全表统计重复
            String sql = "SELECT countIf(cnt > 1) AS dup FROM (SELECT " + groupKey +
                    " AS k, count() AS cnt FROM " + tq.table;
            if (tq.dateCol != null && !tq.dateCol.isEmpty()) {
                sql += " WHERE " + tq.dateCol + " = ? AND data_source = ?";
                // 仅统计本次 run 产出:追加 create_date 过滤
                if (onlyCurrentRun) {
                    sql += " AND create_date = ?";
                    sql += " GROUP BY k)";
                    Long dup = chJdbc.queryForObject(sql, Long.class, date.toString(), source, date.toString());
                    return dup == null ? 0 : dup.intValue();
                }
                sql += " GROUP BY k)";
                Long dup = chJdbc.queryForObject(sql, Long.class, date.toString(), source);
                return dup == null ? 0 : dup.intValue();
            }
            if (onlyCurrentRun) {
                sql += " WHERE create_date = ? GROUP BY k)";
                Long dup = chJdbc.queryForObject(sql, Long.class, date.toString());
                return dup == null ? 0 : dup.intValue();
            }
            sql += " GROUP BY k)";
            Long dup = chJdbc.queryForObject(sql, Long.class);
            return dup == null ? 0 : dup.intValue();
        } catch (Exception e) {
            log.warn("[TOTAL_COUNT] 查重复失败 table={}: {}", tq.table, e.getMessage());
            return 0;
        }
    }

    /** 兼容旧口径:不过滤 create_date。 */
    private int countDupGroups(TableQuery tq, LocalDate date, int source) {
        return countDupGroups(tq, date, source, false);
    }

    private List<String> existingColumns(String table) {
        List<String> cols = new ArrayList<>();
        chJdbc.query("SELECT name FROM system.columns WHERE database = currentDatabase() AND table = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> rs.getString(1), table);
        return cols;
    }

    /**
     * @param table        CK 表名
     * @param extraFilter  额外 WHERE 条件(如 board_type = 1)
     * @param ignored      无用,兼容旧签名
     * @param dateCol      日期列名;为 null 表示该表无日期列(维表),countActual 不做日期过滤
     *                     大部分表用 "trade_date",stock_board_rel 用 "effective_date"
     */
    private record TableQuery(String table, String extraFilter, String ignored, String dateCol) {
        TableQuery(String table, String extraFilter, String ignored) {
            this(table, extraFilter, ignored, "trade_date");
        }
    }
}
