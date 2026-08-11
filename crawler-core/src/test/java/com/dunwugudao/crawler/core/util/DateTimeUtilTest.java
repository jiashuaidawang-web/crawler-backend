package com.dunwugudao.crawler.core.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link DateTimeUtil#parseMinuteTime} 的单元测试。
 * <p>覆盖场景：东财分钟线 f[0]="2026-08-07 09:31:00"、日线 "2026-08-07"、null、空串、已经是 LocalDateTime 类型。</p>
 */
class DateTimeUtilTest {

    /** 核心场景：东财分钟线返回 "yyyy-MM-dd HH:mm:ss"，必须保留时分。 */
    @Test
    void parseMinuteTime_fullDateTimeString_returnsLocalDateTimeWithMinute() {
        LocalDateTime result = DateTimeUtil.parseMinuteTime("2026-08-07 09:31:00");
        assertEquals(LocalDateTime.of(2026, 8, 7, 9, 31, 0), result);
    }

    /** 无秒的 "yyyy-MM-dd HH:mm" 也应能解析。 */
    @Test
    void parseMinuteTime_noSecondString_returnsLocalDateTime() {
        LocalDateTime result = DateTimeUtil.parseMinuteTime("2026-08-07 09:31");
        assertEquals(LocalDateTime.of(2026, 8, 7, 9, 31, 0), result);
    }

    /** 仅日期字符串（日线格式），补 00:00:00。 */
    @Test
    void parseMinuteTime_dateOnlyString_returnsStartOfDay() {
        LocalDateTime result = DateTimeUtil.parseMinuteTime("2026-08-07");
        assertEquals(LocalDateTime.of(2026, 8, 7, 0, 0, 0), result);
    }

    /** 已经是 LocalDateTime，直接返回。 */
    @Test
    void parseMinuteTime_localDateTimeInstance_returnsAsIs() {
        LocalDateTime input = LocalDateTime.of(2026, 8, 7, 9, 31, 0);
        assertEquals(input, DateTimeUtil.parseMinuteTime(input));
    }

    /** 已经是 LocalDate，补 00:00:00。 */
    @Test
    void parseMinuteTime_localDateInstance_returnsStartOfDay() {
        LocalDate input = LocalDate.of(2026, 8, 7);
        assertEquals(LocalDateTime.of(2026, 8, 7, 0, 0, 0), DateTimeUtil.parseMinuteTime(input));
    }

    /** null 输入返回 null（由调用方按 skip 处理）。 */
    @Test
    void parseMinuteTime_null_returnsNull() {
        assertNull(DateTimeUtil.parseMinuteTime(null));
    }

    /** 空串返回 null。 */
    @Test
    void parseMinuteTime_blankString_returnsNull() {
        assertNull(DateTimeUtil.parseMinuteTime("   "));
    }

    /** 不可解析的串返回 null，不抛异常。 */
    @Test
    void parseMinuteTime_garbage_returnsNull() {
        assertNull(DateTimeUtil.parseMinuteTime("not-a-date"));
    }

    /** 端到端模拟：从解析器产出的 row Map（key=trade_date）读字段，还原分钟时间。 */
    @Test
    void parseMinuteTime_fromParserRowMap_canExtractMinute() {
        Map<String, Object> row = new HashMap<>();
        // 解析器只产出 trade_date，不产出 minute_time（业务独立原则）
        row.put("trade_date", "2026-08-07 09:31:00");
        row.put("ts_code", "600000.SH");

        LocalDateTime minuteTime = DateTimeUtil.parseMinuteTime(row.get("trade_date"));
        assertEquals(LocalDateTime.of(2026, 8, 7, 9, 31, 0), minuteTime);
    }
}
