# ---- Build stage ----
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# libc6-compat needed for protoc-gen-grpc-java binary on Alpine (musl)
RUN apk add --no-cache libc6-compat

COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon
COPY src src
RUN ./gradlew installDist --no-daemon

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine AS runtime

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

# The service has no healthcheck subcommand: TCP connect to the gRPC port with busybox nc (JRE alpine image)
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=3 \
    CMD nc -z localhost ${PORT:-9555} || exit 1

ENTRYPOINT ["bin/AdService"]