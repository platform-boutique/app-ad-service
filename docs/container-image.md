# Container image

Multi-stage [`Dockerfile`](https://github.com/platform-boutique/app-ad-service/tree/main/Dockerfile):

| Stage | Base | What happens |
|---|---|---|
| builder | `eclipse-temurin:21-jdk-alpine` | installs `libc6-compat` (needed by `protoc-gen-grpc-java` on musl), resolves deps, `./gradlew installDist` |
| runtime | `eclipse-temurin:21-jre-alpine` | copies `build/install/hipstershop`, runs as non-root |

- **Entrypoint:** `bin/AdService` (working dir `/app`). Honours `JAVA_OPTS` / `JAVA_TOOL_OPTIONS`.
- **User:** numeric `USER 10001:10001` (`appuser`/`appgroup`, created with these fixed ids; `id` in the container prints `uid=10001(appuser) gid=10001(appgroup)`). Build args `SERVICE_VERSION` (default `dev`) and `SERVICE_COMMIT` (default `unknown`) set the OCI labels `version`/`revision` (plus `source`, `licenses=Apache-2.0`) and the ENV of the same names.
- **EXPOSE:** `9555`.
- **Docker `HEALTHCHECK`:** exec form (no shell, nc or curl): `java -cp "/app/lib/*" hipstershop.HealthCheck` asks the local server for its standard `grpc.health.v1` status and exits 0 only for `SERVING` (`PORT` honoured, default 9555; heap capped at 32 MB for the probe). Kubernetes ignores it and uses gRPC probes (below).
- **Image size:** ~107 MB.
- **Build context:** the service root with a [`.dockerignore`](https://github.com/platform-boutique/app-ad-service/tree/main/.dockerignore) that keeps `.git`, `build/`, `.gradle/`, IDE files, keys and docs out of the context (the image build needs `gradlew`, `gradle/`, `build.gradle`, `settings.gradle` and `src/`).
