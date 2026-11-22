# Container image

Multi-stage [`Dockerfile`](https://github.com/platform-boutique/app-ad-service/tree/main/Dockerfile):

| Stage | Base | What happens |
|---|---|---|
| builder | `eclipse-temurin:21-jdk-alpine` | installs `libc6-compat` (needed by `protoc-gen-grpc-java` on musl), resolves deps, `./gradlew installDist` |
| runtime | `eclipse-temurin:21-jre-alpine` | copies `build/install/hipstershop`, runs as non-root |

- **Entrypoint:** `bin/AdService` (working dir `/app`). Honours `JAVA_OPTS` / `JAVA_TOOL_OPTIONS`.
- **User:** numeric `USER 10001:10001` (`appuser`/`appgroup`, created with these fixed ids; `id` in the container prints `uid=10001(appuser) gid=10001(appgroup)`). Build args `SERVICE_VERSION` (default `dev`) and `SERVICE_COMMIT` (default `unknown`) set the OCI labels `version`/`revision` (plus `source`, `licenses=Apache-2.0`) and the ENV of the same names.
- **EXPOSE:** `9555`.
- **Docker `HEALTHCHECK`:** exec form (no shell, nc or curl): `java -cp "/app/lib/*" hipstershop.HealthCheck` asks the local server for its standard `grpc.health.v1` status and exits 0 only for `SERVING` (`PORT` honoured, default 9555; heap capped at 32 MB for the probe). It starts a JVM every 10 s: cost in [Operations](operations.md#cost-of-the-image-healthcheck), reason in [ADR 3](adr/0003-healthcheck-runs-a-jvm-in-exec-form.md). Kubernetes ignores it and uses gRPC probes (see [Kubernetes](kubernetes.md)).
- **Image size:** 109 MB (`docker image inspect`, built 2026-11-22 for linux/amd64; `docker images` lists the same image as 363 MB).
- **Base images** are pinned by digest (`@sha256:`, IMG-01); Dependabot (monthly) proposes new ones. The image is `linux/amd64` only.
- **Build context:** the service root with a [`.dockerignore`](https://github.com/platform-boutique/app-ad-service/tree/main/.dockerignore) that keeps `.git`, `build/`, `.gradle/`, IDE files, keys and docs out of the context (the image build needs `gradlew`, `gradle/`, `build.gradle`, `settings.gradle` and `src/`).
