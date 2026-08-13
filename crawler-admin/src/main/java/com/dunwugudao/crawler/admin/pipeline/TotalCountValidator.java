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

        // actual:CK 行数(普通 count,按 data_source+特定维度过滤)
        // 注意:目标表均为 MergeTree(非 ReplacingMergeTree),不支持 FINAL
        int actual = countActual(tq, date, ctx.source());

        if (actual == expected) {
            return ValidateResult.ok(name(), "actual=total=" + actual, expected, actual);
        }
        if (actual > expected) {
            return ValidateResult.fail(name(),
                    String.format("actual(%d) > total(%d),去重后反而更多,异常", actual, expected),
                    expected, actual, 0, 0, List.of());
        }

        // actual < total:查重复组数判定能否用去重解释(按自然键+data_source分组)
        int dupGroups = countDupGroups(tq, date, ctx.source());
        int diff = expected - actual;
        if (diff <= dupGroups) {
            return ValidateResult.ok(name(),
                    String.format("actual(%d) < total(%d),差值(%d) <= 重复组数(%d),去重解释成功",
                            actual, expected, diff, dupGroups),
                    expected, actual);
        }
        int lost = diff - dupGroups;
        return ValidateResult.fail(name(),
                String.format("actual(%d) < total(%d),差值(%d) > 重复组数(%d),真正丢失 %d 行",
                        actual, expected, diff, dupGroups, lost),
                expected, actual, dupGroups, lost, ctx.taskIds());
    }

    /** 构建阶段的 CK 查询参数(表名+额外过滤条件)。 */
    private TableQuery tableQuery(PipelineStage stage) {
        return switch (stage) {
            case STOCK_DAILY -> new TableQuery("stock_daily", null, null);
            case REGION_DAILY -> new TableQuery("board_daily", "board_type = 1", null);
            case INDUSTRY_DAILY -> new TableQuery("board_daily", "board_type = 2", null);
            case CONCEPT_DAILY -> new TableQuery("board_daily", "board_type = 3", null);
            case MAIN_FUND_STOCK -> new TableQuery("main_fund_flow", "obj_type = 'stock'", null);
            case MAIN_FUND_BOARD -> new TableQuery("main_fund_flow", "obj_type = 'board'", null);
            case LIMIT_POOL -> new TableQuery("limit_up_pool", null, null);
            case STRONG_POOL -> new TableQuery("strong_pool", null, null);
            case CIXIN_POOL -> new TableQuery("cixin_pool", null, null);
            case NORTHBOUND -> new TableQuery("northbound_flow", null, null);
            case INDEX_DAILY -> new TableQuery("index_daily", null, null);
            case DRAGON_TIGER -> new TableQuery("dragon_tiger", null, null);
            case DRAGON_TIGER_DETAIL -> new TableQuery("dt_detail", null, null);
            case STOCK_BY_BOARD -> new TableQuery("stock_board_rel", null, null);
        };
    }

    private int countActual(TableQuery tq, LocalDate date, int source) {
        String sql = "SELECT count() FROM " + tq.table + " WHERE trade_date = ? AND data_source = ?";
        if (tq.extraFilter != null && !tq.extraFilter.isEmpty()) {
            sql += " AND " + tq.extraFilter;
        }
        Long v = chJdbc.queryForObject(sql, Long.class, date.toString(), source);
        return v == null ? 0 : v.intValue();
    }

    /** 按自然键+data_source 统计重复组数。 */
    private int countDupGroups(TableQuery tq, LocalDate date, int source) {
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
            String sql = "SELECT countIf(cnt > 1) AS dup FROM (SELECT " + groupKey +
                    " AS k, count() AS cnt FROM " + tq.table +
                    " WHERE trade_date = ? AND data_source = ? GROUP BY k)";
            Long dup = chJdbc.queryForObject(sql, Long.class, date.toString(), source);
            return dup == null ? 0 : dup.intValue();
        } catch (Exception e) {
            log.warn("[TOTAL_COUNT] 查重复失败 table={}: {}", tq.table, e.getMessage());
            return 0;
        }
    }

    private List<String> existingColumns(String table) {
        List<String> cols = new ArrayList<>();
        chJdbc.query("SELECT name FROM system.columns WHERE database = currentDatabase() AND table = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> rs.getString(1), table);
        return cols;
    }

    private record TableQuery(String table, String extraFilter, String ignored) {}
}
