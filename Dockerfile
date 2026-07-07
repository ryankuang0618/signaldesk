# ---- Stage 1: build the React frontend ----
FROM node:22-alpine AS frontend
WORKDIR /frontend
COPY frontend/package*.json ./
RUN npm install --no-audit --no-fund
COPY frontend/ ./
RUN npm run build          # -> /frontend/dist

# ---- Stage 2: build the Spring Boot jar with the frontend bundled in ----
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /app
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
# Bundle the built dashboard so the single app serves both the UI and the API.
COPY --from=frontend /frontend/dist ./src/main/resources/static
RUN mvn -q -B clean package -DskipTests

# ---- Stage 3: runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend /app/target/signaldesk-0.1.0.jar app.jar
# Render/Neon and all secrets are injected as environment variables at runtime.
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
