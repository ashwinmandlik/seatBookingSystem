# syntax=docker/dockerfile:1

# ---- build: compile and package with the project's own Gradle wrapper ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Dependencies first, in their own layer: source edits don't re-download them.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon -q dependencies > /dev/null

COPY src src
# Tests need embedded Postgres/Redis binaries and run in CI / locally
# (./gradlew test); the image build only packages.
RUN ./gradlew --no-daemon -q bootJar -x test \
 && java -Djarmode=tools -jar build/libs/app.jar extract --layers --launcher --destination /extracted

# ---- runtime: JRE only, non-root ----
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --uid 10001 app
WORKDIR /app

# Spring Boot layers, least to most frequently changing, for small image updates.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

USER app
EXPOSE 8080

# Size the heap from the container's memory limit, and if the heap is ever
# exhausted, exit so the platform restarts a clean instance rather than
# limping on in a broken state.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
