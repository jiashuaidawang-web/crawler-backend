package com.dunwugudao.crawler.admin.controller;

import com.dunwugudao.crawler.admin.service.MonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 监控 REST（能力6）。
 * <ul>
 *   <li>GET /api/crawl/stats → 状态计数 + 成功率</li>
 *   <li>GET /api/crawl/stats?groupBy=source → 按来源分布</li>
 *   <li>GET /api/crawl/stats?groupBy=node → 按节点分布</li>
 *   <li>GET /api/crawl/alerts?resolved=0 → 未处理告警</li>
 *   <li>POST /api/crawl/alerts/{alertId}/resolve → 标记告警已处理</li>
 *   <li>GET /api/crawl/nodes → 节点列表与心跳</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/crawl")
@RequiredArgsConstructor
public class MonitorController {

    private final MonitorService monitorService;

    @GetMapping("/stats")
    public Object stats(@RequestParam(required = false) String groupBy) {
        if ("source".equals(groupBy)) {
            return monitorService.statsBySource();
        }
        if ("node".equals(groupBy)) {
            return monitorService.statsByNode();
        }
        return monitorService.stats();
    }

    @GetMapping("/alerts")
    public Object alerts(@RequestParam(defaultValue = "0") int resolved) {
        return monitorService.alerts(resolved);
    }

    @PostMapping("/alerts/{alertId}/resolve")
    public Map<String, Object> resolveAlert(@PathVariable Long alertId) {
        int n = monitorService.resolveAlert(alertId);
        Map<String, Object> r = new HashMap<>();
        r.put("alertId", alertId);
        r.put("resolved", n);
        return r;
    }

    @GetMapping("/nodes")
    public Object nodes() {
        return monitorService.nodes();
    }
}
