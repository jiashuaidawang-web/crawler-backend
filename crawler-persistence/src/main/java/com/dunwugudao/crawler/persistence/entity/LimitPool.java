package com.dunwugudao.crawler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A4 涨跌停/炸板/强势/次新股池（全书最关键输入：S2 情绪、S4 主线龙头、S5 分歧高低切换）。
 * <p>合并了原来的 strong_pool，用 type 字段区分：
 * limit_up涨停 / limit_down跌停 / zhaban炸板 / strong强势 / cixin次新</p>
 * <p>源自 push2ex getTopicZTPool/getTopicDTPool/getTopicZBPool/getTopicQSPool/getTopicCXPooll。
 * 实测 17-19 字段。</p>
 */
@Data
@TableName("limit_pool")
public class LimitPool {
    private LocalDate tradeDate;
    private String tsCode;
    private String stockName;
    private String type;             // limit_up涨停 / limit_down跌停 / zhaban炸板 / strong强势 / cixin次新
    private BigDecimal latestPrice;  // 最新价(元) = p/100
    private BigDecimal pctChg;       // 涨跌幅%
    private Integer boardPos;        // 连板数
    private Integer isFirst;         // 是否首板
    private Integer isContinuous;    // 是否连板(>=2)
    private String limitStyle;       // 一字 / T字 / 换手 / 自然 / 烂板
    private String openTime;         // 首次封板时间(fbt)，格式 HH:mm:ss
    private String lastTime;         // 最后封板/炸板时间(lbt)，格式 HH:mm:ss
    private Integer openTimes;       // 开板次数(zbc)
    private BigDecimal bidAmount;    // 涨停封单金额
    private BigDecimal turnover;     // 换手率%
    private String reason;           // 涨停原因/题材标签
    private String boardCode;        // 所属板块(hybk)
    private String boardName;        // 板块名称(实测无hymc)
    // 实测 push2ex 新增字段
    private BigDecimal amount;       // 成交额(元)
    private BigDecimal fund;         // 封单资金(涨停池)
    private BigDecimal hs;           // 换手率%
    private BigDecimal ltsz;         // 流通市值(元)
    private BigDecimal tshare;       // 总股本(元)
    private BigDecimal zf;           // 涨幅%(炸板池)
    private BigDecimal zs;           // 振幅%(炸板池)
    private BigDecimal ztp;          // 涨停价
    private Integer zttjCt;          // 连板统计-连板数(zttj.ct)
    private Integer zttjDays;        // 连板统计-天数(zttj.days)
    private Integer lb;              // 连板数(强势池)
    private Integer nh;              // N日新高(强势/次新池)
    private String ztf;              // 涨停封单描述(强势池)
    private LocalDate ipod;          // 上市日期(次新池)
    private BigDecimal o;            // 开盘价(次新池)
    private Integer od;              // 开板日期(YYYYMMDD)
    private Integer ods;             // 开板几日
    private Integer isNewHigh;       // 是否新高标识(o: 1=新高)
    private String ipod;             // 上市日期(YYYYMMDD)
    private Integer zttjCt;          // 连板统计-连板数(zttj.ct)
    private Integer zttjDays;        // 连板统计-天数(zttj.days)
    private Integer lb;              // 连板数(强势池)
    private Integer nh;              // N日新高(强势/次新池)
    private String ztf;              // 涨停封单描述(强势池)
    private BigDecimal bidAmount;    // 涨停封单金额(万元)
    // 基础字段
    private Integer dataSource;      // data_source: 0=东财 1=同花顺
    private String srcDetail;        // src_detail: 来源URL/接口/备注
    private LocalDate createDate;    // 创建日期
    private LocalDate updateDate;    // 修改日期
}
