# 极简运行镜像：复用已 seed 的 eclipse-temurin:21-jre（无需 maven 基础镜像、无需外网）。
# jar 由宿主机 /opt/apache-maven 离线 `mvn -o package` 产出后 COPY 进来。
FROM eclipse-temurin:21-jre

WORKDIR /app

# crawler-admin 模块打包产物：crawler-admin-0.1.0.jar（通配容错）
COPY target/crawler-admin-*.jar app.jar

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# server.port 由 application.yml 决定（8081）；host 网络模式下容器直接监听宿主机 8081
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
