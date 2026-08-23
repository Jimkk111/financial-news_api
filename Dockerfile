# ==================== Stage 1: Build ====================
FROM eclipse-temurin:23-jdk AS builder

# Install Maven
ARG MAVEN_VERSION=3.9.9
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    curl -fsSL https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz | \
    tar -xz -C /opt && \
    ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/bin/mvn && \
    apt-get purge -y curl && apt-get autoremove -y && \
    rm -rf /var/lib/apt/lists/*

ENV MAVEN_OPTS="-Xmx512m"

WORKDIR /build

# 先复制 pom.xml 利用 Docker 层缓存（依赖不变时不重新下载）
COPY pom.xml .
RUN mvn dependency:go-offline -B -q 2>/dev/null || true

# 复制源码并构建
COPY src/ ./src/
RUN mvn clean package -DskipTests -B -q

# ==================== Stage 2: Runtime ====================
FROM eclipse-temurin:23-jre

# Labels
LABEL maintainer="financial-news-api"
LABEL description="财经新闻 API 服务"

# Install timezone support
RUN apt-get update && \
    apt-get install -y --no-install-recommends tzdata && \
    ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -r appuser && useradd -r -g appuser -d /app -s /sbin/nologin appuser

WORKDIR /app

# Copy built JAR
COPY --from=builder /build/target/*.jar app.jar

# Create directories for uploads and logs
RUN mkdir -p /app/uploads /app/logs && \
    chown -R appuser:appuser /app

USER appuser

# JVM tuning
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs/"

EXPOSE 3000

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod}"]
