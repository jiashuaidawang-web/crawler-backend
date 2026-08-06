package com.dunwugudao.crawler.core.model;

import lombok.Getter;

/**
 * 数据来源枚举，与数据库 {@code data_source} 列（SMALLINT / UInt8）严格对应。
 * <ul>
 *   <li>0 = 同花顺（TONGHUASHUN，浏览器策略）</li>
 *   <li>1 = 东方财富（EASTMONEY，HTTP/JSON API 策略）</li>
 *   <li>2 = 其他（OTHER）</li>
 * </ul>
 * <p>去 MyBatis-Plus {@code @EnumValue}，改用自定义 {@link SourceTypeTypeHandler} 按 code 读写 DB。</p>
 */
@Getter
public enum SourceType {
    TONGHUASHUN(0),
    EASTMONEY(1),
    OTHER(2);

    private final int code;

    SourceType(int code) {
        this.code = code;
    }

    public static SourceType fromCode(int code) {
        for (SourceType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown SourceType code: " + code);
    }
}
