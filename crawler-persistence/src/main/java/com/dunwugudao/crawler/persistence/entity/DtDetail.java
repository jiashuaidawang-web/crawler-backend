package com.dunwugudao.crawler.persistence.entity;import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A9 龙虎榜席位明细（S3 破除主力迷信：知名游资≠必胜）。
 * <p>报表名待确认（RPT_BILLBOARD_DETAIL 不存在，需拦截真实页面请求）。</p>
 */
@Data
public class DtDetail {
    private LocalDate tradeDate;
    private String tsCode;
    private String seatName;         // 席位名称
    private String seatType;         // 机构 / 游资 / 深股通 / 沪股通 / 营业部
    private BigDecimal buy;
    private BigDecimal sell;
    private Integer isInstitution;    // 是否机构
    private Integer isFamous;         // 是否知名游资
    private Integer dataSource;          // data_source: 0=东财 1=同花顺          // 0=东财 1=同花顺
    private String srcDetail;        // src_detail: 来源URL/接口/备注
    private LocalDate createDate;
    private LocalDateTime updateDate;    // 修改日期
}
