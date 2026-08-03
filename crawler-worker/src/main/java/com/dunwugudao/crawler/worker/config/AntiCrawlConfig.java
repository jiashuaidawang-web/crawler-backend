package com.dunwugudao.crawler.worker.config;

import com.dunwugudao.crawler.core.model.SourceType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 反爬配置（能力2）：UA 池 / 代理池 / 限速 / stealth / Cookie / 浏览器参数。
 * <p>实现 core 接口 {@code com.dunwugudao.crawler.core.config.AntiCrawlConfig}，
 * 使 worker 与 strategy 模块均可用而不形成循环依赖（类名与接口同名，故 implements 用全限定名）。
 * 对应 application.yml 的 {@code anti-crawl.*} 前缀。M2 由策略层注入使用。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "anti-crawl")
public class AntiCrawlConfig implements com.dunwugudao.crawler.core.config.AntiCrawlConfig {

    /** UA 池，每次请求随机取一个。 */
    private List<String> uaPool = new ArrayList<>();

    /** 全局代理列表（host:port 或 scheme://host:port）。 */
    private List<String> proxyList = new ArrayList<>();

    /** 是否启用代理。 */
    private boolean proxyEnabled = false;

    /** 单节点每秒请求上限（令牌桶）。 */
    private double rateLimitPerSec = 5.0;

    /** 按 source 分组的代理池（key = SourceType.code：0 同花顺 / 1 东财）。 */
    private Map<Integer, List<String>> perSourceProxies = new HashMap<>();

    /** 是否启用浏览器 stealth（同花顺策略使用）。 */
    private boolean stealthEnabled = true;

    /**
     * Stealth 模式：SELF（默认，现状 JS 注入）或 CLOAK（CloakBrowser CDP）。
     * CLOAK 模式下，BrowserPool 会走 CDP 连接、跳过 stealth JS / UA / viewport 注入。
     */
    private String stealthMode = "SELF";

    /** Cookie 目录（同花顺登录态持久化），可空。 */
    private String cookieDir = "/data/crawler/cookies";

    /** 浏览器启动参数（如 --disable-blink-features=AutomationControlled）。 */
    private List<String> browserArgs = new ArrayList<>(List.of(
            "--disable-blink-features=AutomationControlled",
            "--no-sandbox"));

    /** 代理轮换策略：RANDOM / ROUND_ROBIN。 */
    private String proxyRotation = "RANDOM";

    // ---- CloakBrowser(CLOAK 模式)参数 ----

    /**
     * CLOAK 模式下 CDP server 地址。默认按 cloak-local-port 拼出 http://127.0.0.1:{port}。
     * 若显式设置则直接用（可用于 Docker sidecar 等场景）。
     */
    private String cloakCdpUrl = null;

    /** CLOAK 模式下 CloakBrowser license key（空=用 v146 公开免费版）。 */
    private String cloakLicenseKey = "";

    /** CLOAK 模式下是否启用 humanize（人类化鼠标/键盘/滚动）。 */
    private boolean cloakHumanize = true;

    /** CLOAK 模式下固定指纹 seed（空=每次随机）。同一 seed 在同一代理下模拟回访者身份。 */
    private String cloakFingerprintSeed = "";

    /** CLOAK 模式下本地自动拉起 cloakserve 的端口。 */
    private int cloakLocalPort = 9222;

    /** CLOAK 模式下 cloakserve 启动脚本路径（相对工作目录或绝对路径）。 */
    private String cloakServeScript = "scripts/cloak_serve.java";

    private final Random random = new Random();
    private final AtomicInteger rrCounter = new AtomicInteger(0);

    @Override
    public String getProxyFor(SourceType source) {
        if (!proxyEnabled) {
            return null;
        }
        List<String> list = null;
        if (perSourceProxies != null) {
            list = perSourceProxies.get(source.getCode());
        }
        if (list == null || list.isEmpty()) {
            list = proxyList;
        }
        if (list == null || list.isEmpty()) {
            return null;
        }
        if ("ROUND_ROBIN".equalsIgnoreCase(proxyRotation)) {
            int i = rrCounter.getAndIncrement();
            return list.get(Math.abs(i % list.size()));
        }
        return list.get(random.nextInt(list.size()));
    }

    /**
     * 返回 CLOAK 模式下的 CDP URL：优先用显式 cloakCdpUrl，
     * 否则按 cloakLocalPort 拼出 http://127.0.0.1:{port}。
     */
    @Override
    public String getCloakCdpUrl() {
        if (cloakCdpUrl != null && !cloakCdpUrl.isBlank()) {
            return cloakCdpUrl;
        }
        return "http://127.0.0.1:" + cloakLocalPort;
    }
}
