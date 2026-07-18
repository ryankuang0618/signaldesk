# ---- Stage 1: build the Spring Boot jar ----
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /app
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- Stage 2: runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend /app/target/signaldesk-0.1.0.jar app.jar
# Render/Neon and all secrets are injected as environment variables at runtime.
ENV SPRING_PROFILES_ACTIVE=prod
# Render Starter is 512MB. Cap the heap (~330MB) so metaspace, threads and the JDBC pool have
# room and the JVM isn't OOM-killed. Raise this if you move to a larger instance.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
