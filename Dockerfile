# Multi-stage build: Gradle + JDK 21 for compilation, JRE 21 slim for runtime.
# Build integration tests are excluded here; they run in CI with Testcontainers.
#
# The build stage is pinned to $BUILDPLATFORM so Gradle runs natively on the
# builder exactly once (the jar is arch-independent bytecode); only the runtime
# stage is materialized per target platform (linux/amd64 + linux/arm64, RNF-10).

FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Cache dependency layer (invalidated only when build files change)
COPY gradle gradle
COPY gradlew .
COPY settings.gradle build.gradle ./
RUN ./gradlew dependencies --no-daemon --quiet

COPY src src
RUN ./gradlew build -x integrationTest --no-daemon

# ── Runtime ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
EXPOSE 8080
WORKDIR /app

COPY --from=build /app/build/libs/hyperbrain-core-*.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
