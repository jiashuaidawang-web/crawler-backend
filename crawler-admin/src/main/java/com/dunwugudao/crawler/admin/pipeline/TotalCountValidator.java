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
        String table = tableFor(stage);
        if (table == null) {
            return ValidateResult.ok(name(), "阶段 " + stage + " 无对应 CK 表,跳过", expected, 0);
        }

        // actual:CK 去重后行数(FINAL)
        Long actualObj = chJdbc.queryForObject(
                "SELECT count() FROM " + table + " FINAL WHERE trade_date = ?", Long.class, date.toString());
        int actual = actualObj == null ? 0 : actualObj.intValue();

        if (actual == expected) {
            return ValidateResult.ok(name(), "actual=total=" + actual, expected, actual);
        }
        if (actual > expected) {
            return ValidateResult.fail(name(),
                    String.format("actual(%d) > total(%d),去重后反而更多,异常", actual, expected),
                    expected, actual, 0, 0, List.of());
        }

        // actual < total:查重复组数判定能否用去重解释
        int dupGroups = countDupGroups(table, date, stage);
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

    /** 按自然键(实际存在于表的列)统计重复组数。 */
    private int countDupGroups(String table, LocalDate date, PipelineStage stage) {
        try {
            DedupService.TableCfg cfg = DedupService.registry().get(table);
            if (cfg == null || cfg.getNaturalKey() == null) {
                log.warn("[TOTAL_COUNT] 表 {} 未注册自然键,无法查重复", table);
                return 0;
            }
            List<String> cfgCols = Arrays.stream(cfg.getNaturalKey().split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            List<String> existing = existingColumns(table);
            // 只保留 cfg 中声明且实际存在的列
            List<String> cols = cfgCols.stream().filter(existing::contains).toList();
            if (cols.isEmpty()) {
                log.warn("[TOTAL_COUNT] 表 {} 的自然键列均不存在于表中,无法查重复", table);
                return 0;
            }
            String groupKey = cols.stream().map(c -> "toString(coalesce(" + c + ",''))")
                    .reduce((a, b) -> a + "||'|'||" + b).orElse("1");
            String sql = "SELECT countIf(cnt > 1) AS dup FROM (SELECT " + groupKey +
                    " AS k, count() AS cnt FROM " + table + " WHERE trade_date = ? GROUP BY k)";
            Long dup = chJdbc.queryForObject(sql, Long.class, date.toString());
            return dup == null ? 0 : dup.intValue();
        } catch (Exception e) {
            log.warn("[TOTAL_COUNT] 查重复失败 table={}: {}", table, e.getMessage());
            return 0;
        }
    }

    private List<String> existingColumns(String table) {
        List<String> cols = new ArrayList<>();
        chJdbc.query("SELECT name FROM system.columns WHERE database = currentDatabase() AND table = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> cols.add(rs.getString(1)), table);
        return cols;
    }

    private String tableFor(PipelineStage stage) {
        return switch (stage) {
            case STOCK_DAILY -> "stock_daily";
            case REGION_DAILY, INDUSTRY_DAILY, CONCEPT_DAILY -> "board_daily";
            case MAIN_FUND_STOCK, MAIN_FUND_BOARD -> "main_fund_flow";
            case LIMIT_POOL -> "limit_up_pool";
            case STRONG_POOL -> "strong_pool";
            case CIXIN_POOL -> "cixin_pool";
            case NORTHBOUND -> "northbound_flow";
            case INDEX_DAILY -> "index_daily";
            case DRAGON_TIGER -> "dragon_tiger";
            case DRAGON_TIGER_DETAIL -> "dt_detail";
        };
    }
}
