# syntax=docker/dockerfile:1.7

# ==================== Stage 1: Build ====================
FROM maven:3.9-eclipse-temurin-23 AS builder

ENV MAVEN_OPTS="-Xmx512m"

WORKDIR /build

# 配置阿里云 Maven 镜像加速
RUN mkdir -p /root/.m2 && \
    echo '<settings><mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><url>https://maven.aliyun.com/repository/central</url></mirror></mirrors></settings>' \
    > /root/.m2/settings.xml

# 先复制 pom.xml 利用 Docker 层缓存（依赖不变时不重新下载）
COPY pom.xml .

# 复制源码
COPY src/ ./src/

# 挂载 Maven 本地仓库到 BuildKit 缓存，避免每次重新下载依赖
# 不再执行 dependency:go-offline：它每次都会联网核对全部依赖元数据，是构建变慢的主因；
# package 阶段本身就会解析依赖，配合缓存挂载第二次构建即为纯本地编译
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn package -DskipTests -B -T 1C

# 提取 Layered JAR 的各层（变化频率从低到高）
RUN mkdir -p /extracted && \
    cd /extracted && \
    java -Djarmode=layertools -jar /build/target/*.jar extract

# ==================== Stage 2: Runtime ====================
FROM eclipse-temurin:23-jre

# Labels
LABEL maintainer="financial-news-api"
LABEL description="财经新闻 API 服务"

# 配置阿里云 APT 镜像加速（运行时阶段，下载快 10 倍+）
RUN sed -i 's|http://archive.ubuntu.com|https://mirrors.aliyun.com|g' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || \
    sed -i 's|http://archive.ubuntu.com|https://mirrors.aliyun.com|g' /etc/apt/sources.list 2>/dev/null || true

# Install timezone support
RUN apt-get update && \
    apt-get install -y --no-install-recommends tzdata && \
    ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -r appuser && useradd -r -g appuser -d /app -s /sbin/nologin appuser

WORKDIR /app

# ---- 按变化频率从低到高逐层复制（底层被缓存的概率最大） ----
# 1) 第三方依赖 —— 几乎不变，命中缓存后跳过后续所有层
COPY --from=builder /extracted/dependencies/ ./
# 2) Spring Boot Loader —— 框架升级才变
COPY --from=builder /extracted/spring-boot-loader/ ./
# 3) SNAPSHOT 依赖 —— 偶尔变动
COPY --from=builder /extracted/snapshot-dependencies/ ./
# 4) 应用代码 + 配置 —— 每次发版都变，放最顶层
COPY --from=builder /extracted/application/ ./

# Create directories for uploads and logs
RUN mkdir -p /app/uploads /app/logs && \
    chown -R appuser:appuser /app

USER appuser

# JVM tuning
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs/"

EXPOSE 3000

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod}"]
