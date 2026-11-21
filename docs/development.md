# Develop & test

## Checks and commands

Requirements: JDK 21 (the host has Java 1.8, so run Gradle in the `eclipse-temurin:21-jdk-alpine` container), Docker for the image, Python 3.12 for the docs check. The commands below are the ones the shared pipeline (`platform-demo-workflows`, `service-ci-java`) runs, so a failure can be reproduced without pushing. `Running locally` above has the run commands. There is no dependency audit step for Gradle in the pipeline or here: not run.

| Step | Command | Result on 2026-11-21 |
|---|---|---|
| Compile and tests | `./gradlew test --no-daemon` (run in `eclipse-temurin:21-jdk-alpine` with `apk add libc6-compat`, `--memory 1g`, a Gradle home outside the repository, as JDK 21 is not on the host); the shared pipeline's lint is `./gradlew compileJava --warning-mode all` (a compile, no checkstyle or spotless) | run: BUILD SUCCESSFUL, 241 tests, 0 failures, 0 errors, 0 skipped |
| Build | `./gradlew build -x test --no-daemon` | not run (`test` compiles the code) |
| Image | `docker build -t platform-demo/ad-service:local .` | not run today |
| Image scan | `trivy image --severity CRITICAL,HIGH --exit-code 1 platform-demo/ad-service:local` | not run today |
| Secret scan | `pre-commit run --all-files` (`.pre-commit-config.yaml`, gitleaks) or `gitleaks git --redact` | run with the gitleaks binary of the pre-commit cache (`gitleaks git --redact`, full history, 47 commits): no leaks found |
| Workflow lint | `actionlint` | run (actionlint 1.7.12), no findings |
| Docs | `mkdocs build --strict` (mkdocs 1.6.1 + mkdocs-techdocs-core 1.7.1, Python 3.12 venv) and the link check `python3 -I tools/check-md-links.py .` from `platform-demo-workflows` (relative links in `README.md` and `docs/`) | run, both passed |

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
│   │   └── BuildInfo.java       # version (SERVICE_VERSION, manifest, dev) and commit
│   ├── proto/ad.proto           # service contract
│   └── resources/
│       ├── ads.json             # bundled default ad config
│       ├── log4j2.xml           # appenders, levels
│       └── LogShape.json        # the JSON log shape
├── build.gradle                 # gRPC 1.79, OTel 1.49, Pyroscope agent, pinned Netty
├── settings.gradle
├── gradlew / gradle/
└── Dockerfile                   # JDK builder -> JRE runtime
```
