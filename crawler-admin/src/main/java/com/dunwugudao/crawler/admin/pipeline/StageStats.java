package com.dunwugudao.crawler.admin.pipeline;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单pipeline运行的阶段统计(IP消耗/成功/失败)。
 * <p>通过 {@link #CURRENT} ThreadLocal 在当前线程跟踪,无需层层传参。</p>
 */
public class StageStats {

    /** 当前线程的统计实例。 */
    public static final ThreadLocal<StageStats> CURRENT = ThreadLocal.withInitial(StageStats::new);

    /** stageName -> ipCount */
    private final Map<String, AtomicInteger> ipCountMap = new ConcurrentHashMap<>();
    /** stageName -> successCount */
    private final Map<String, AtomicInteger> successCountMap = new ConcurrentHashMap<>();
    /** stageName -> failCount */
    private final Map<String, AtomicInteger> failCountMap = new ConcurrentHashMap<>();

    /** 记录某阶段消耗 1 个 IP。 */
    public void recordIp(String stageName) {
        ipCountMap.computeIfAbsent(stageName, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /** 记录某阶段成功。 */
    public void recordSuccess(String stageName) {
        successCountMap.computeIfAbsent(stageName, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /** 记录某阶段失败。 */
    public void recordFail(String stageName) {
        failCountMap.computeIfAbsent(stageName, k -> new AtomicInteger(0)).incrementAndGet();
    }

    public int getIpCount(String stageName) {
        return ipCountMap.getOrDefault(stageName, new AtomicInteger(0)).get();
    }

    public int getSuccessCount(String stageName) {
        return successCountMap.getOrDefault(stageName, new AtomicInteger(0)).get();
    }

    public int getFailCount(String stageName) {
        return failCountMap.getOrDefault(stageName, new AtomicInteger(0)).get();
    }

    /** 输出汇总表格。 */
    public String toSummaryTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== Pipeline 阶段统计 ==========\n");
        sb.append(String.format("%-20s %6s %8s %8s\n", "阶段", "IP", "成功", "失败"));
        sb.append("----------------------------------------\n");
        for (String stage : ipCountMap.keySet()) {
            sb.append(String.format("%-20s %6d %8d %8d\n",
                    stage,
                    getIpCount(stage),
                    getSuccessCount(stage),
                    getFailCount(stage)));
        }
        sb.append("========================================\n");
        return sb.append("========================================\n").toString();
    }

    public void clear() {
        ipCountMap.clear();
        successCountMap.clear();
        failCountMap.clear();
    }
}
