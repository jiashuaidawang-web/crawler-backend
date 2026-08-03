package com.dunwugudao.crawler.strategy.tonghuashun;

import com.dunwugudao.crawler.core.config.AntiCrawlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本机开发/单进程场景下,为 CLOAK 模式自动拉起 {@code cloakbrowser cloakserve} 的辅助类。
 *
 * <p>单例、懒启动、线程安全。首次 {@link #ensureRunning(AntiCrawlConfig)} 时：
 * <ol>
 *   <li>先探测 CDP 端口是否已有服务在监听,有则直接复用(不启动新进程)</li>
 *   <li>否则启动 {@code scripts/cloak_serve.py}(或 cfg.cloakServeScript 指定路径),
 *       并轮询端口直到就绪</li>
 * </ol>
 *
 * <p>JVM 关闭钩子会停掉自己拉起的进程。Docker sidecar 模式下通常不会走到这里
 * (容器里由 supervisor/compose 管理 cloakserve),但本类仍安全可用。
 *
 * <p>生产多实例部署建议用 Docker sidecar 或独立 cloakserve,而不是依赖本类。
 */
public class CloakServerProcess {

    private static final Logger log = LoggerFactory.getLogger(CloakServerProcess.class);

    private static final CloakServerProcess INSTANCE = new CloakServerProcess();

    private Process process;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private CloakServerProcess() {
    }

    public static CloakServerProcess getInstance() {
        return INSTANCE;
    }

    /**
     * 确保 cloakserve 已在运行。
     *
     * @param cfg 反爬配置(取 stealthMode / cloakLocalPort / cloakServeScript / 代理等)
     */
    public static void ensureRunning(AntiCrawlConfig cfg) {
        if (!"CLOAK".equalsIgnoreCase(cfg.getStealthMode())) {
            return;
        }
        INSTANCE.startIfNeeded(cfg);
    }

    private void startIfNeeded(AntiCrawlConfig cfg) {
        if (started.get()) {
            return;
        }
        synchronized (this) {
            if (started.get()) {
                return;
            }
            String cdpUrl = cfg.getCloakCdpUrl();
            int port = cfg.getCloakLocalPort();
            // 如果 CDP URL 指向远程(非 127.0.0.1/::1),说明是 Docker sidecar 或独立服务,直接复用
            if (!isLocalCdpUrl(cdpUrl)) {
                log.info("[CloakServerProcess] CDP URL {} is remote, reuse existing server", cdpUrl);
                started.set(true);
                return;
            }
            if (isPortListening(port)) {
                log.info("[CloakServerProcess] port {} already listening, reuse existing cloakserve", port);
                started.set(true);
                return;
            }
            doStart(cfg);
            started.set(true);
        }
    }

    /** CDP URL 是否指向本机(需要本地自动拉起)。 */
    private static boolean isLocalCdpUrl(String url) {
        if (url == null) {
            return true;
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return true;
            }
            return "127.0.0.1".equals(host)
                    || "localhost".equalsIgnoreCase(host)
                    || "::1".equals(host)
                    || "0:0:0:0:0:0:0:1".equals(host);
        } catch (Exception e) {
            return true;
        }
    }

    private void doStart(AntiCrawlConfig cfg) {
        String script = cfg.getCloakServeScript();
        Path scriptPath = resolveScript(script);
        if (scriptPath == null || !Files.exists(scriptPath)) {
            throw new RuntimeException("[CloakServerProcess] cloakserve script not found: " + script
                    + " (resolved=" + scriptPath + "). "
                    + "Set anti-crawl.cloak-serve-script to an absolute path, "
                    + "or place scripts/cloak_serve.py in the working directory.");
        }
        int port = cfg.getCloakLocalPort();
        log.info("[CloakServerProcess] starting cloakserve via {} (port {})", scriptPath, port);

        ProcessBuilder pb = new ProcessBuilder(buildCommand(cfg, scriptPath));
        pb.redirectErrorStream(true);
        // 把关键配置透传给 Python 脚本
        pb.environment().put("CLOAK_PORT", String.valueOf(port));
        if (cfg.getCloakLicenseKey() != null && !cfg.getCloakLicenseKey().isBlank()) {
            pb.environment().put("CLOAKBROWSER_LICENSE_KEY", cfg.getCloakLicenseKey());
        }
        String proxy = cfg.getProxyFor(com.dunwugudao.crawler.core.model.SourceType.TONGHUASHUN);
        if (proxy != null && !proxy.isBlank()) {
            pb.environment().put("CLOAK_PROXY", proxy);
        }
        pb.environment().put("CLOAK_HEADLESS", "true");
        pb.environment().put("CLOAK_HUMANIZE", String.valueOf(cfg.isCloakHumanize()));
        if (cfg.getCloakFingerprintSeed() != null && !cfg.getCloakFingerprintSeed().isBlank()) {
            pb.environment().put("CLOAK_FINGERPRINT_SEED", cfg.getCloakFingerprintSeed());
        }

        try {
            process = pb.start();
        } catch (IOException e) {
            throw new RuntimeException("[CloakServerProcess] failed to start cloakserve: " + e.getMessage(), e);
        }

        // JVM 退出时关掉自己拉起的进程
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stopQuietly(), "cloakserve-shutdown"));

        // 轮询端口直到就绪
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new RuntimeException("[CloakServerProcess] cloakserve exited prematurely, exit=" + process.exitValue());
            }
            if (isPortListening(port)) {
                log.info("[CloakServerProcess] cloakserve ready on port {}", port);
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("[CloakServerProcess] interrupted while waiting for cloakserve");
            }
        }
        stopQuietly();
        throw new RuntimeException("[CloakServerProcess] cloakserve did not become ready on port " + port + " within 60s");
    }

    private Path resolveScript(String script) {
        Path p = Paths.get(script);
        if (p.isAbsolute()) {
            return p;
        }
        // 先相对工作目录,再相对 user.home,最后相对 /app(Docker 内常见部署目录)
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path candidate = cwd.resolve(script);
        if (Files.exists(candidate)) {
            return candidate;
        }
        Path home = Paths.get(System.getProperty("user.home")).resolve(script);
        if (Files.exists(home)) {
            return home;
        }
        Path app = Paths.get("/app").resolve(script);
        if (Files.exists(app)) {
            return app;
        }
        return candidate; // 返回 cwd 下的,让调用方报"not found"
    }

    private static List<String> buildCommand(AntiCrawlConfig cfg, Path scriptPath) {
        // 优先用 cfg 里显式指定的可执行命令,否则按脚本后缀推断
        if (scriptPath.toString().endsWith(".java")) {
            // 占位:Java 端没有原生 CloakBrowser SDK,建议用 Python 脚本。
            // 这里给一个友好提示,实际不会跑到(因为文件不存在 .java 后缀的脚本)。
            throw new RuntimeException(
                    "[CloakServerProcess] Java 无法直接驱动 CloakBrowser。请改用 Python 脚本 scripts/cloak_serve.java, "
                            + "或把 anti-crawl.cloak-serve-script 指向一个可执行文件。");
        }
        return List.of("python3", scriptPath.toString());
    }

    private static boolean isPortListening(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void stopQuietly() {
        if (process != null && process.isAlive()) {
            try {
                process.destroy();
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
