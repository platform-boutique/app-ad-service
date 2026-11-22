# 3. The image HEALTHCHECK starts a short JVM in exec form

Status: accepted (2026-11-22). Rule CODE-03: exec form, no shell, no `nc`, no `curl`.

## Context

The runtime image is `eclipse-temurin:21-jre-alpine`. It has no gRPC health client and the earlier `nc -z` probe needed a shell and busybox `nc`, and only proved that the port was open, not that the service answered. Kubernetes does not use the Docker `HEALTHCHECK` (it uses gRPC probes); Docker Compose and plain `docker run` do.

## Decision

`HEALTHCHECK CMD ["java", "-Xmx32m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:-UsePerfData", "-cp", "/app/lib/*", "hipstershop.HealthCheck"]` every 10 s (timeout 5 s, start period 20 s, 3 retries). `HealthCheck` opens one channel to the local port (`PORT`, default 9555), calls the standard `grpc.health.v1.Health/Check` and exits 0 only for `SERVING`. The JVM flags keep the probe small: 32 MB heap, serial GC, C1 only, no perf-data file.

## Consequences

- Every 10 s a JVM starts and exits. Measured cost on 2026-11-22 is in [Operations](../operations.md#cost-of-the-image-healthcheck).
- The probe is a real gRPC call to the running server, so a hung server fails it, which a port check would not catch.
- No extra binary is added to the image. A native probe (grpc-health-probe) would be cheaper per run but would add a binary that has to be pinned and scanned; not worth it for a demo that runs this under Docker only.
