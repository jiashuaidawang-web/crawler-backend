# 多阶段构建：编译 + 运行
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app
COPY pom.xml .
COPY crawler-core/pom.xml crawler-core/
COPY crawler-strategy/pom.xml crawler-strategy/
COPY crawler-persistence/pom.xml crawler-persistence/
COPY crawler-worker/pom.xml crawler-worker/
COPY crawler-admin/pom.xml crawler-admin/

# 先下载依赖（利用 Docker 缓存）
RUN mvn dependency:go-offline -B

# 复制源码并打包
COPY crawler-core/src crawler-core/src
COPY crawler-strategy/src crawler-strategy/src
COPY crawler-persistence/src crawler-persistence/src
COPY crawler-worker/src crawler-worker/src
COPY crawler-admin/src crawler-admin/src

RUN mvn package -DskipTests -B

# 运行阶段
FROM eclipse-temurin:21-jre

WORKDIR /app

# 复制打包好的 JAR
COPY --from=builder /app/crawler-admin/target/crawler-admin-*.jar app.jar

# 时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
