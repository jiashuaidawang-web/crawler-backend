package com.dunwugudao.crawler.core.model;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * 数据来源枚举，与数据库 {@code data_source} 列（SMALLINT）严格对应。
 * <ul>
 *   <li>0 = 同花顺（TONGHUASHUN，浏览器策略）</li>
 *   <li>1 = 东方财富（EASTMONEY，HTTP/JSON API 策略）</li>
 *   <li>2 = 其他（OTHER）</li>
 * </ul>
 * {@code code} 标注 {@code @EnumValue}，MyBatis-Plus 全局枚举处理器据此按序数读写 DB，
 * 无需自定义 TypeHandler（MP 3.5.x 的全局 CompositeEnumTypeHandler 优先级高于 field-level handler）。
 */
@Getter
public enum SourceType {
    TONGHUASHUN(0),
    EASTMONEY(1),
    OTHER(2);

    @EnumValue
    public final int code;

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
