# syntax=docker/dockerfile:1

# The jar is the one `./gradlew build` (or `bootJar`) left in build/libs, which CI builds and tests before the
# image: the image does not compile again. It is extracted into Spring Boot's layers, so that a new release only
# changes the application layer.
FROM eclipse-temurin:25-jre-noble AS layers
WORKDIR /workspace
COPY build/libs/welcome-home.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# Run: a JRE only, as an unprivileged user. Configuration comes from environment variables (see the README).
FROM eclipse-temurin:25-jre-noble
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=layers /workspace/extracted/dependencies/ ./
COPY --from=layers /workspace/extracted/spring-boot-loader/ ./
COPY --from=layers /workspace/extracted/snapshot-dependencies/ ./
COPY --from=layers /workspace/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
# Compact object headers (JDK 25, JEP 519): 8-byte object headers instead of 12, so the same objects take less heap.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+UseCompactObjectHeaders", "org.springframework.boot.loader.launch.JarLauncher"]
