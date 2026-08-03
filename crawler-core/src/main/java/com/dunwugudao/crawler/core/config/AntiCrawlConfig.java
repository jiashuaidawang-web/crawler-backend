package com.dunwugudao.crawler.core.config;

import com.dunwugudao.crawler.core.model.SourceType;

import java.util.List;

/**
 * 反爬配置接口（能力2）。
 * <p>放在 core 层，使 strategy 模块与 worker 模块都能引用而<b>不形成 Maven 模块循环依赖</b>
 * （worker 模块里的具体实现 {@code com.dunwugudao.crawler.worker.config.AntiCrawlConfig}
 * 实现了本接口）。策略类只依赖此接口，由 worker 在装配时注入具体实例。</p>
 *
 * <p>涵盖：UA 池、代理（按 source 分组 + 轮换策略）、限速、stealth 开关、Cookie 目录、浏览器启动参数。</p>
 */
public interface AntiCrawlConfig {

    /** UA 池，每次请求随机取一个。 */
    List<String> getUaPool();

    /** 单节点每秒请求上限（令牌桶）。 */
    double getRateLimitPerSec();

    /** 是否启用代理。 */
    boolean isProxyEnabled();

    /**
     * 按 source 取一个代理（host:port 或 scheme://host:port）。
     * 优先取 perSourceProxies 中该 source 的代理池，否则回退全局 proxyList；
     * 按 proxyRotation 策略（RANDOM/ROUND_ROBIN）挑一个；无可用代理返回 null。
     */
    String getProxyFor(SourceType source);

    /** 是否启用浏览器 stealth（同花顺策略使用）。 */
    boolean isStealthEnabled();

    /**
     * Stealth 模式：SELF（现状，Java 侧 JS 注入 + 自管 Playwright）或
     * CLOAK（CloakBrowser CDP server，C++ 源码级补丁）。
     * 默认 SELF，开 CLOAK 时需要本机/sidecar 起 cloakserve。
     */
    String getStealthMode();

    /** Cookie 目录（同花顺登录态持久化），可空。 */
    String getCookieDir();

    /** 浏览器启动参数（如 --disable-blink-features=AutomationControlled）。 */
    List<String> getBrowserArgs();

    /** 代理轮换策略：RANDOM / ROUND_ROBIN。 */
    String getProxyRotation();

    // ---- CloakBrowser(CLOAK 模式)参数 ----

    /** CLOAK 模式下 CDP server 地址（如 http://127.0.0.1:9222）。 */
    String getCloakCdpUrl();

    /** CLOAK 模式下 CloakBrowser license key（免费 key 或空使用 v146 公开版）。 */
    String getCloakLicenseKey();

    /** CLOAK 模式下是否启用 humanize（人类化鼠标/键盘/滚动）。 */
    boolean isCloakHumanize();

    /**
     * CLOAK 模式下固定指纹 seed（空=每次随机）。
     * 同一 seed 在同一代理下模拟回访者身份，对风控评分有利。
     */
    String getCloakFingerprintSeed();

    /**
     * CLOAK 模式下本地自动拉起 cloakserve 的端口（默认 9222）。
     * 若该端口已有 CDP server 在监听则直接复用，否则启动 scripts/cloak_serve.py。
     */
    int getCloakLocalPort();

    /** CLOAK 模式下 cloakserve 启动脚本路径（相对工作目录或绝对路径）。 */
    String getCloakServeScript();
}
