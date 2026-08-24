# syntax=docker/dockerfile:1.7.1@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e
FROM eclipse-temurin:17.0.20_8-jdk-jammy@sha256:400014962ad7224461f945bb1cc3d7d5a1927ce15b8245b72d9cedcda554cd2a AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    bash ./gradlew :server:installDist --no-daemon --max-workers=1 \
    -Pkotlin.compiler.execution.strategy=in-process \
    -Dorg.gradle.jvmargs="-Xmx700m -XX:MaxMetaspaceSize=320m -Dfile.encoding=UTF-8"

FROM eclipse-temurin:17.0.20_8-jre-jammy@sha256:e17d77fb030dd4b642dc078d048a5fb9efcb3676ee20305d905949105a6ccd5a
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
