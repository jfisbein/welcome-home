# syntax=docker/dockerfile:1

# Build: the Spring Boot jar, extracted into layers so that a new release only changes the application layer.
# Tests are not run here: every change passes `./gradlew build` before it is committed.
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar \
    && java -Djarmode=tools -jar build/libs/*.jar extract --layers --launcher --destination build/extracted

# Run: a JRE only, as an unprivileged user. Configuration comes from environment variables (see the README).
FROM eclipse-temurin:25-jre-noble
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /workspace/build/extracted/dependencies/ ./
COPY --from=build /workspace/build/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/build/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/build/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
