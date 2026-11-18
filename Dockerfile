# ============================================================
# 多阶段构建：容器内完成 mvn package（Compose 一次性部署，无需本地 JDK/Maven）
# ============================================================
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
# 构建期可选镜像源：不把任何特定地区的镜像硬编码进仓库。
# 通过 docker compose 的 build args 传入（空值 = 使用 Maven 默认）。
ARG MAVEN_MIRROR_URL=""
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then \
        mkdir -p /root/.m2 && \
        printf '%s\n' \
          '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">' \
          '  <mirrors>' \
          '    <mirror>' \
          '      <id>build-mirror</id>' \
          '      <mirrorOf>central</mirrorOf>' \
          "      <url>${MAVEN_MIRROR_URL}</url>" \
          '    </mirror>' \
          '  </mirrors>' \
          '</settings>' > /root/.m2/settings.xml; \
    fi
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/target/turn-based-game-server-nt-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080 8081 9510
ENTRYPOINT ["java", "-Xms512m", "-Xmx1g", "-jar", "app.jar"]
