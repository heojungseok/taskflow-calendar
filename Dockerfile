FROM eclipse-temurin:17-jdk-jammy@sha256:9283f99ad21802850dd7420a865c495642a804c5a201f73377aef232ef12bccb AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew build.gradle settings.gradle gradle.lockfile ./
RUN ./gradlew dependencies --no-daemon
COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre-jammy@sha256:1e38389ecd9e5c444e40d4385be4a4a5f56a836a38a6e41c099810c32ec1c595
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 taskflow \
    && useradd --uid 10001 --gid taskflow --no-create-home taskflow
WORKDIR /app
COPY --from=build /workspace/build/libs/taskflow-calendar-*.jar app.jar
USER 10001:10001
EXPOSE 8080 9091
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
