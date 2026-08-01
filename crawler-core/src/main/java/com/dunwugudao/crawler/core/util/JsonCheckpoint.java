package com.dunwugudao.crawler.core.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * checkpoint（断点）序列化工具：将 {@code Map<String,Object>} 与 JSON 互转。
 * <p>对应 crawl_task.checkpoint 列，用于记录已处理到的日期/页码以便续传。</p>
 */
public class JsonCheckpoint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String serialize(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("serialize checkpoint failed", e);
        }
    }

    public static Map<String, Object> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new RuntimeException("deserialize checkpoint failed: " + json, e);
        }
    }
}
