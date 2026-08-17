package com.dunwugudao.crawler.admin.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常兜底:确保任何未捕获异常都【带堆栈】记入 error.log,避免 500 无日志无法排查。
 * <p>Spring 默认异常处理走 DefaultHandlerExceptionResolver(只打 WARN),堆栈常丢失;
 * 这里显式记 ERROR 并返回统一 JSON。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAll(Exception e) {
        // 注意:只记消息,不传异常对象。当前环境 logback 的 throwable-proxy 路径有
        // NoClassDefFoundError(ThrowableProxy),传 e 会导致日志框架自身崩、响应写不出去。
        log.error("[未捕获异常] {}: {}", e.getClass().getSimpleName(), e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", 500);
        body.put("error", "Internal Server Error");
        body.put("message", e.getMessage());
        return ResponseEntity.internalServerError().body(body);
    }
}
