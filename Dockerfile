# ==================== Stage 1: Build ====================
FROM eclipse-temurin:23-jdk AS builder

WORKDIR /build

# Copy Maven wrapper if exists, otherwise use system mvn
COPY pom.xml .
COPY src/ ./src/

# Build application JAR (skip tests in Docker)
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn clean package -DskipTests -q

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
