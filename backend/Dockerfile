# syntax=docker/dockerfile:1
# A tested bootJar is the only application input. Build context excludes source secrets and test evidence.
FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269
ARG APP_REVISION
LABEL org.opencontainers.image.source="https://github.com/RTAman/TEACHMi" \
      org.opencontainers.image.revision="${APP_REVISION}"
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates fontconfig fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 teachmi && useradd --uid 10001 --gid teachmi --create-home teachmi
WORKDIR /app
COPY --chown=10001:10001 build/libs/teachmi-backend-0.0.1-SNAPSHOT.jar /app/teachmi.jar
COPY --chmod=0555 deploy/bin/healthcheck.sh /app/healthcheck.sh
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=7s --start-period=120s --retries=3 CMD ["/app/healthcheck.sh"]
ENTRYPOINT ["java", "-jar", "/app/teachmi.jar"]
