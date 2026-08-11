package com.dunwugudao.crawler.core.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 日期时间工具。
 * <p>ClickHouse 的 {@code DateTime} 列是秒级精度，不接受小数秒。
 * 通过 JDBC 写入 {@link LocalDateTime} 时若带小数秒，CK 会报
 * {@code CANNOT_PARSE_TEXT: Cannot parse string ... as DateTime}。
 * 向这类列写「当前时间」时，统一用 {@link #nowSeconds()} 截断到秒。</p>
 * <p>MySQL 的 {@code TIMESTAMP}/{@code DATETIME} 接受小数秒，不需要截断，不要用这个方法。</p>
 */
public final class DateTimeUtil {

    private DateTimeUtil() {
    }

    /**
     * 当前时间截断到秒（小数秒置零），供 ClickHouse {@code DateTime} 列写入。
     */
    public static LocalDateTime nowSeconds() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * 将解析器产出的原始值转为 {@link LocalDateTime}（秒级），供 {@code DateTime} 列写入。
     * <p>支持：
     * <ul>
     *   <li>已是 {@link LocalDateTime} → 直接返回</li>
     *   <li>已是 {@link LocalDate} → 补 00:00:00</li>
     *   <li>{@code "yyyy-MM-dd HH:mm:ss"} / {@code "yyyy-MM-dd HH:mm"} → 解析（保留时分）</li>
     *   <li>{@code "yyyy-MM-dd"} → 补 00:00:00</li>
     *   <li>null / 空串 / 不可解析 → 返回 null（由调用方按 skip 处理）</li>
     * </ul>
     * </p>
     * <p>注意：本方法为通用时间解析，不含分钟线业务规则；调用方自行决定读哪个字段。</p>
     *
     * @param o 解析器产出的原始值（一般为 {@link String}）
     * @return 解析后的 {@link LocalDateTime}，或 null
     */
    public static LocalDateTime parseMinuteTime(Object o) {
        if (o instanceof LocalDateTime dt) {
            return dt;
        }
        if (o instanceof LocalDate d) {
            return d.atStartOfDay();
        }
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            // "yyyy-MM-dd HH:mm:ss" / "yyyy-MM-dd HH:mm" → 保留时分
            if (s.contains(":")) {
                int end = Math.min(s.length(), 19);
                return LocalDateTime.parse(s.substring(0, end).replace(' ', 'T'));
            }
            // "yyyy-MM-dd" → 补 00:00:00
            if (s.length() >= 10) {
                return LocalDate.parse(s.substring(0, 10)).atStartOfDay();
            }
        } catch (Exception ignored) {
            // 不可解析 → 返回 null，由调用方按 skip 处理
        }
        return null;
    }
}
