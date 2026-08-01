package com.dunwugudao.crawler.core.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 单次抓取结果。
 * <ul>
 *   <li>{@code success} 是否成功</li>
 *   <li>{@code data} 结构化行（字段映射后的 List<Map>），解析型策略填充</li>
 *   <li>{@code raw} 原始响应文本（浏览器/失败兜底），落库溯源用</li>
 *   <li>{@code rowCount} 实际采集行数（数据量校验用）</li>
 *   <li>{@code errorMsg} / {@code httpStatus} 诊断信息</li>
 * </ul>
 */
@Data
public class CrawlResult {
    private boolean success;
    private List<Map<String, Object>> data;
    private String raw;
    private int rowCount;
    private String errorMsg;
    private int httpStatus;
}
