# ---- Build stage ----
FROM eclipse-temurin:21-jdk-alpine@sha256:c07bb82e004a166b5d3d63b84ad70e2e822f228505e2e0c0b8dfdc2890d22f13 AS builder

WORKDIR /app

# libc6-compat needed for protoc-gen-grpc-java binary on Alpine (musl)
RUN apk add --no-cache libc6-compat

COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
# Locked dependency versions (SEC-01): the build fails when the resolution differs from gradle.lockfile
COPY gradle.lockfile .
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon
COPY src src
RUN ./gradlew installDist --no-daemon

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine@sha256:5d7689f47d670aca766b9503a70bccc2d73b4eb9221ae8f714ae958313c4ce70 AS runtime

# Pick up Alpine security fixes published after the base image was built (the image scan fails on fixable HIGH/CRITICAL findings)
RUN apk upgrade --no-cache

ARG SERVICE_VERSION=dev
ARG SERVICE_COMMIT=unknown

LABEL org.opencontainers.image.title="ad-service" \
      org.opencontainers.image.source="https://github.com/platform-boutique/app-ad-service" \
      org.opencontainers.image.revision="${SERVICE_COMMIT}" \
      org.opencontainers.image.version="${SERVICE_VERSION}" \
      org.opencontainers.image.licenses="Apache-2.0"

# SERVICE_VERSION and SERVICE_COMMIT are read by the app at run time (BuildInfo.java).
ENV SERVICE_VERSION=${SERVICE_VERSION} \
    SERVICE_COMMIT=${SERVICE_COMMIT}

RUN addgroup -S -g 10001 appgroup && adduser -S -u 10001 -G appgroup appuser

WORKDIR /app

COPY --from=builder --chown=10001:10001 /app/build/install/hipstershop ./

# Numeric so Kubernetes runAsNonRoot can verify it (IMG-02: UID:GID 10001)
USER 10001:10001

EXPOSE 9555

# Exec form (CODE-03): the JVM runs the gRPC health probe itself (hipstershop.HealthCheck), no shell, nc or curl.
# Small heap and the cheap JIT tier: the probe only opens one channel.
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=3 \
    CMD ["java", "-Xmx32m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:-UsePerfData", "-cp", "/app/lib/*", "hipstershop.HealthCheck"]

ENTRYPOINT ["bin/AdService"]