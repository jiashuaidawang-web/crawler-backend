package com.dunwugudao.crawler.admin.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 数据同步 API：本地 → 远程云服务器 (100.81.7.96)
 *
 * <p>提供手动触发同步的能力，前端可通过按钮调用。</p>
 *
 * <ul>
 *   <li>POST /api/sync/trigger — 触发增量同步</li>
 *   <li>POST /api/sync/trigger?full=true — 触发全量同步</li>
 *   <li>GET  /api/sync/status — 查询同步状态</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private static final Logger log = LoggerFactory.getLogger(SyncController.class);

    @Value("${sync.script.path:scripts/sync/sync_to_remote.py}")
    private String scriptPath;

    @Value("${sync.python.cmd:python}")
    private String pythonCmd;

    @Value("${sync.enabled:true}")
    private boolean syncEnabled;

    /** 当前运行的同步进程 */
    private final AtomicReference<Process> currentProcess = new AtomicReference<>();

    /** 上次同步结果摘要 */
    private final AtomicReference<String> lastResult = new AtomicReference<>("未执行");

    /** 上次启动时间 */
    private volatile long lastStartTime = 0;

    /**
     * 触发同步。
     *
     * @param full 是否全量同步（默认 false = 增量）
     * @return 同步结果或状态
     */
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> triggerSync(
            @RequestParam(value = "full", required = false, defaultValue = "false") boolean full) {

        Map<String, Object> result = new HashMap<>();

        if (!syncEnabled) {
            result.put("success", false);
            result.put("message", "同步功能未启用（sync.enabled=false）");
            return ResponseEntity.ok(result);
        }

        Process proc = currentProcess.get();
        if (proc != null && proc.isAlive()) {
            result.put("success", false);
            result.put("message", "同步正在进行中，请稍后再试");
            result.put("running", true);
            return ResponseEntity.ok(result);
        }

        lastStartTime = System.currentTimeMillis();
        result.put("success", true);
        result.put("message", full ? "全量同步已启动" : "增量同步已启动");
        result.put("mode", full ? "full" : "incremental");

        // 异步执行
        final boolean finalFull = full;
        CompletableFuture.runAsync(() -> {
            Process process = null;
            try {
                File script = new File(scriptPath).getAbsoluteFile();
                if (!script.exists()) {
                    lastResult.set("脚本不存在: " + script.getAbsolutePath());
                    log.error(lastResult.get());
                    return;
                }

                ProcessBuilder pb = new ProcessBuilder(pythonCmd, script.getAbsolutePath());
                if (finalFull) {
                    pb.command().add("--full");
                }
                pb.directory(script.getParentFile());
                pb.redirectErrorStream(true);

                log.info("开始执行同步脚本: {} (full={})", script.getAbsolutePath(), finalFull);
                process = pb.start();
                currentProcess.set(process);

                // 读取输出
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                        log.info("[sync] {}", line);
                    }
                }

                int exitCode = process.waitFor();
                String summary = String.format("退出码=%d, 输出行数=%d", exitCode, output.length());
                lastResult.set(summary);
                log.info("同步脚本执行完成: {}", summary);

            } catch (Exception e) {
                lastResult.set("异常: " + e.getMessage());
                log.error("同步脚本执行异常", e);
            } finally {
                if (process != null) {
                    try { process.destroy(); } catch (Exception ignored) {}
                }
                currentProcess.compareAndSet(process, null);
            }
        });

        return ResponseEntity.ok(result);
    }

    /**
     * 查询同步状态。
     *
     * @return 同步状态信息
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> result = new HashMap<>();

        Process proc = currentProcess.get();
        boolean running = proc != null && proc.isAlive();

        result.put("running", running);
        result.put("enabled", syncEnabled);
        result.put("lastStartTime", lastStartTime > 0 ? lastStartTime : null);
        result.put("lastResult", lastResult.get());
        result.put("scriptPath", new File(scriptPath).getAbsolutePath());

        return ResponseEntity.ok(result);
    }

    /**
     * 查看同步状态文件（详细）。
     *
     * @return 各表同步状态
     */
    @GetMapping("/detail-status")
    public ResponseEntity<Map<String, Object>> getDetailStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("lastResult", lastResult.get());
        result.put("running", currentProcess.get() != null && currentProcess.get().isAlive());
        // 详细状态从 sync_status.json 读取
        File statusFile = new File("scripts/sync/sync_status.json").getAbsoluteFile();
        result.put("statusFile", statusFile.getAbsolutePath());
        result.put("statusFileExists", statusFile.exists());
        return ResponseEntity.ok(result);
    }
}
