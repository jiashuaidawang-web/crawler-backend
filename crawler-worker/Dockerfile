# 极简运行镜像：worker 节点（实际爬取执行器）。
# 注意：原 Dockerfile.worker 内的 python3 + CloakBrowser 依赖 apt/pip 外网，当前环境无外网会失败；
# 且 stealth-mode=CLOAK 当前未启用，故本镜像仅保留 JDK 运行环境（CLOAK 模式启用时再单独部署 cloakserve sidecar）。
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY target/crawler-worker-*.jar app.jar

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# server.port 默认 8080（与 xxl-job-admin 8080 冲突），部署时由 compose 注入 SERVER_PORT=8082 覆盖
EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
