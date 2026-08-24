# syntax=docker/dockerfile:1.7.1@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e
FROM eclipse-temurin:22.0.2_9-jdk-jammy@sha256:d8e6ba486df17bf758888d2b1b608133d1eedca8daf69d3fc6bf78d8be81e07e AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    bash ./gradlew :server:installDist --no-daemon --max-workers=1 \
    -Pkotlin.compiler.execution.strategy=in-process \
    -Dorg.gradle.jvmargs="-Xmx700m -XX:MaxMetaspaceSize=320m -Dfile.encoding=UTF-8"

FROM eclipse-temurin:22.0.2_9-jre-jammy@sha256:dbcae8b5dd4d63f81739a538ec2c09797735f04a21d814f9071b62f018326043
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home --home-dir /home/jarvis jarvis
WORKDIR /opt/jarvis-server
COPY --from=build --chown=jarvis:jarvis /workspace/server/build/install/server/ ./
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=5 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/v1/health >/dev/null || exit 1
ENTRYPOINT ["/opt/jarvis-server/bin/server"]
