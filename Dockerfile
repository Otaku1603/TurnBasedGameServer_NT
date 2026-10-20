# ============================================================
# 多阶段构建：容器内完成 mvn package（Compose 一次性部署，无需本地 JDK/Maven）
# ============================================================
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/target/turn-based-game-server-nt-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080 8081 9510
ENTRYPOINT ["java", "-Xms512m", "-Xmx1g", "-jar", "app.jar"]
