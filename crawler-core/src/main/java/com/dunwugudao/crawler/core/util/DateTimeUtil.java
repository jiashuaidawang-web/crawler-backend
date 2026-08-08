package com.dunwugudao.crawler.core.util;

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
}
