# Develop & test

## Checks and commands

Requirements: JDK 21 (the host has Java 1.8, so run Gradle in the `eclipse-temurin:21-jdk-alpine` container), Docker for the image, Python 3.12 for the docs check. The commands below are the ones the shared pipeline (`platform-demo-workflows`, `service-ci-java`) runs, so a failure can be reproduced without pushing. `Running locally` has the run commands.

| Step | Command | Result on 2026-11-22 |
|---|---|---|
| Unit tests | `./gradlew cleanTest test --no-daemon` (in `eclipse-temurin:21-jdk-alpine` + `apk add libc6-compat`, `--memory 1500m`, run as the host user with a Gradle home outside the repository, as JDK 21 is not on the host) | run: BUILD SUCCESSFUL, 241 tests, 0 failures, 0 errors, 0 skipped |
| Build | `./gradlew build -x test --no-daemon` (a separate call; `installDist test` in one call fails, see Known limitations) | run: BUILD SUCCESSFUL |
| Lint | `./gradlew compileJava --warning-mode all` is the pipeline's compile; the pipeline also reports `javac -Xlint:all` (v3.8.0, report only), reproduce with an init script that adds `-Xlint:all` to every `JavaCompile` task | run on `compileJava` and `compileTestJava` with `--rerun-tasks`: 0 warnings (it was 6 in main and 8 in the tests before this bundle) |
| Coverage | not in the build or the pipeline; measured once with the JaCoCo plugin 0.8.13 added by a Gradle init script (nothing committed) | run: 83.6 % of the lines of the hand-written classes (498 of 596), 37.1 % including the generated protobuf classes |
| Image | `docker build --pull --platform linux/amd64 --build-arg SERVICE_VERSION=final-test --build-arg SERVICE_COMMIT=<sha> -t platform-demo/ad-service:local .` | run: built, 109 MB (`docker image inspect`), `USER 10001:10001`, exec-form HEALTHCHECK reports `healthy`, reflection and the three RPCs answer, `docker stop` ends it in 0.8 s with exit 143 and the log line `Shutting down AdService...` |
| Image scan | `trivy image --severity CRITICAL,HIGH --exit-code 1 platform-demo/ad-service:local` (Trivy 0.76.1) | run: exit 0, no CRITICAL or HIGH findings |
| Dependency scan | `trivy fs --scanners vuln --severity CRITICAL,HIGH --exit-code 1 .` (what the pipeline's Dependency scan does, reads `gradle.lockfile`) | run: exit 0, no CRITICAL or HIGH findings |
| Code scan | `semgrep scan --config p/java --config p/owasp-top-ten --error --metrics=off` (semgrep 1.180.0, as the pipeline) | run: 0 findings (100 rules, 50 files) |
| Secret scan | `pre-commit run --all-files` (`.pre-commit-config.yaml`, gitleaks) or `gitleaks git --redact` | run with the gitleaks binary of the pre-commit cache, full history: no leaks found |
| Workflow lint | `actionlint` | run on 2026-11-21 (actionlint 1.7.12), no findings; the workflow files changed since only in the pinned `uses:` lines (v3.8.0): not re-run |
| Docs | `mkdocs build --strict` (mkdocs 1.6.1 + mkdocs-techdocs-core 1.7.1, Python 3.12 venv) and the link check `python3 -I tools/check-md-links.py .` from `platform-demo-workflows` | run, both passed (19 relative links in 16 pages, 0 broken) |
| Whole shop | local compose stack with this image, `local/smoke.sh` three times in a row | run: SMOKE PASSED 3 times, 87 PASS each, including the ad steps on the product, department and search pages and the ad click redirects; afterwards the ad-service container was `healthy` with 0 restarts, 134 log lines of which 0 `error`/`fatal`/`warning`, 132 MiB |
| Telemetry export | the image next to a throwaway Alloy with a debug exporter, `OTEL_EXPORTER_OTLP_ENDPOINT` set | run: the image started next to a throwaway Alloy with a debug exporter (`OTEL_EXPORTER_OTLP_ENDPOINT` set, 2 s metric interval). Received: `ads_impressions_total{ad_id}` (men-1 and men-2 = 3 after 3 `GetAds` calls), `ads_clicks_total{ad_id=men-1}` = 1, `build_info{version,commit}`, `rpc_server_*`, 20 `jvm.*` metrics, spans and log records; 0 error lines in the service log |

Not run: a Kubernetes deployment, the real LGTM stack (Mimir, Tempo, Loki; only a debug receiver was used), Pyroscope profiles, arm64 (amd64 only by decision), `buf` locally (the pipeline's Proto check runs it; `ad.proto` is unchanged by this bundle), a Gradle dependency audit beyond Trivy.

## Running locally

Requirements: JDK 21, Docker, [`grpcurl`](https://github.com/fullstorydev/grpcurl).

```bash
# build + run
./gradlew installDist
build/install/hipstershop/bin/AdService

# or via Docker
docker build -t ad-service .
docker run --rm -p 9555:9555 \
  -e OTEL_EXPORTER_OTLP_ENDPOINT=http://host.docker.internal:4317 \
  -e PYROSCOPE_ADDR=http://host.docker.internal:4040 \
  ad-service

# unit tests
./gradlew test
```

### Manual gRPC testing

Reflection is on, so `grpcurl` needs no proto (`-proto` also works):

```bash
# ads for given categories
grpcurl -plaintext -proto src/main/proto/ad.proto \
  -d '{"context_keys": ["clothing", "kitchen"]}' \
  localhost:9555 hipstershop.AdService/GetAds

# random ads
grpcurl -plaintext -proto src/main/proto/ad.proto -d '{}' \
  localhost:9555 hipstershop.AdService/GetAds

# health (this repo has no health.proto; borrow the one from any sibling service)
grpcurl -plaintext -import-path ../email-service/proto -proto health.proto \
  localhost:9555 grpc.health.v1.Health/Check

# metrics
```

## Project structure

```
├── src/main/
│   ├── java/hipstershop/
│   │   ├── AdService.java       # entrypoint: ad config load (exit 1 if invalid), telemetry bootstrap, gRPC server, shutdown hook
│   │   ├── AdServiceImpl.java   # GetAds, RecordClick, GetAdStats handlers (+ metrics recording)
│   │   ├── AdCatalog.java       # loads and validates the ad JSON; matching, rotation, random fallback
│   │   ├── AdConfigException.java
│   │   ├── AdStats.java         # thread-safe in-memory impression/click counters
│   │   ├── AdsMap.java          # static access to the bundled default catalog (tests, one-arg AdServiceImpl)
│   │   ├── Metrics.java         # rpc_server_*, ads_*_total and build_info OTel metrics
│   │   ├── Telemetry.java       # OTel SDK + Pyroscope init, env contract, bounded shutdown
│   │   ├── LogConfig.java       # LOG_LEVEL / service name / version for log4j2.xml, java.util.logging bridge
│   │   ├── OtlpEnv.java         # OTEL_EXPORTER_OTLP_* headers and TLS files
│   │   ├── HealthCheck.java     # the image HEALTHCHECK: asks the local server for grpc.health.v1 status
│   │   └── BuildInfo.java       # version (SERVICE_VERSION, manifest, dev) and commit
│   ├── proto/ad.proto           # service contract
│   └── resources/
│       ├── ads.json             # bundled default ad config
│       ├── log4j2.xml           # appenders, levels
│       └── LogShape.json        # the JSON log shape
├── build.gradle                 # gRPC 1.79, OTel 1.49, Pyroscope agent, pinned Netty, dependency locking
├── gradle.lockfile              # locked dependency versions (SEC-01)
├── settings.gradle
├── gradlew / gradle/
└── Dockerfile                   # JDK builder -> JRE runtime
```
