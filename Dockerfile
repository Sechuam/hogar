# syntax=docker/dockerfile:1

# --- Build ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/hogar-api-*.jar extract --layers --launcher --destination extracted

# --- Runtime ---
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --home /app app \
    && mkdir -p /app/storage && chown app:app /app/storage
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER app
# 8080: API · 8081: Actuator (gestión, no publicar)
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
