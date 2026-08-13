package com.dunwugudao.crawler.admin.pipeline;

import java.util.List;

/** 单条校验结果。 */
public record ValidateResult(boolean passed, String rule, String message,
                             int expected, int actual, int dupRows, int lostRows,
                             List<Long> lostTaskIds) {

    public static ValidateResult ok(String rule, String message, int expected, int actual) {
        return new ValidateResult(true, rule, message, expected, actual, 0, 0, List.of());
    }

    public static ValidateResult fail(String rule, String message,
                                      int expected, int actual, int dupRows, int lostRows,
                                      List<Long> lostTaskIds) {
        return new ValidateResult(false, rule, message, expected, actual, dupRows, lostRows, lostTaskIds);
    }
}
