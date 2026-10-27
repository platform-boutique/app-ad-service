# ad-service

A gRPC service that serves contextual advertisements for the platform-demo e-commerce platform (demo store brand: Everly). It returns ads for the store's departments, keyed by the product categories the frontend sends, falling back to two random ads when no context is given or nothing matches. It is stateless, has no downstream dependencies, and is called only by `frontend`.

Part of the [platform-demo](https://github.com/platform-boutique) platform — microservices, observability (LGTM stack), GitOps (Argo CD), policy enforcement (Kyverno), infrastructure provisioning (Crossplane) and an internal developer portal (Backstage). See the master `README.md` in the platform-demo root for the whole-system view.

## At a glance

| | |
|---|---|
| **Language / runtime** | Java 21 (Eclipse Temurin), Gradle, gRPC-Java |
| **Image** | `mladenovskistefan/ad-service` |
| **Protocol / port** | gRPC (HTTP/2, plaintext) on `9555` |
| **Metrics** | Pushed over OTLP/gRPC to Alloy → Mimir every 15 s (no scrape endpoint). Dashboard names: `rpc_server_duration_seconds_{bucket,count,sum}`, `rpc_server_requests_total`, `rpc_server_active_requests` |
| **Health** | Standard `grpc.health.v1.Health/Check` on `9555` (always `SERVING` once the server has started) |
| **State** | None. Ad catalog is hard-coded in `AdsMap.java` |
| **Depends on** | Nothing (optional: OTLP collector, Pyroscope) |
| **Called by** | `frontend` (with a 100 ms client-side deadline) |
| **gRPC reflection** | Not enabled — pass `-proto` to `grpcurl` |

## API

Package `hipstershop`, service `AdService` ([`src/main/proto/ad.proto`](src/main/proto/ad.proto)).

| Method | Request | Response | Behaviour |
|---|---|---|---|
| `GetAds` | `AdRequest{ repeated string context_keys }` | `AdResponse{ repeated Ad ads }` (`Ad{ redirect_url, text }`) | Resolves the keys to departments (rules below) and returns both ads of every matched department, without duplicates. If nothing matched (no keys, or only unknown keys), returns 2 **different** random ads from the whole catalog. |

### Ad catalog

Hard-coded in [`AdsMap.java`](src/main/java/hipstershop/AdsMap.java): **two ads per department**, 14 in total. Every ad's `redirect_url` is a category link `/?category=<department id>` (relative; the frontend is expected to filter its home page by that query parameter).

| Department id | Also reached through (type words / source categories from the catalog seed data) |
|---|---|
| `women` | `tops`, `bags`, `womens-dresses`, `womens-shoes`, `womens-bags`, and the shared `clothing`, `footwear` |
| `men` | `mens-shirts`, `mens-shoes`, and the shared `clothing`, `footwear` |
| `accessories` | `jewellery`, `watches`, `sunglasses`, `mens-watches`, `womens-watches`, `womens-jewellery` |
| `beauty` | `fragrances`, `makeup`, `skin-care` |
| `home` | `decor`, `furniture`, `home-decoration` |
| `kitchen` | `kitchen-accessories` |
| `sports` | `sports-accessories` |

Key resolution:

- Keys are trimmed and lower-cased.
- If at least one key is a department id, **only the department ids** are used. A product's `categories` always starts with its department (e.g. `["men","footwear","mens-shoes"]`), so a men's shoe gets the two `men` ads and not also the `women` ads through the shared word `footwear`.
- Otherwise every key is resolved through the alias table (`footwear` alone yields the `women` and `men` ads).
- A cart with items from several departments gets the ads of all of them (the frontend then shows one at random).

The alias list was taken from the distinct `categories` values of the 117 products in `product-catalog-service` `data/seed/products.json` (as of 2026-10-27); `AdsMapTest` checks that each of those values yields an ad. New categories in the catalog need a new alias here, otherwise those products fall back to random ads.

## Configuration

All configuration is via environment variables. Nothing is required; every variable has a default.

| Variable | Default | Description |
|---|---|---|
| `PORT` | `9555` | gRPC listen port |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | unset (telemetry off) | OTLP/**gRPC** endpoint for **traces, metrics and logs** (scheme required), e.g. `http://alloy.monitoring.svc.cluster.local:4317`. Legacy fallback: `COLLECTOR_SERVICE_ADDR` (`host:port`). Telemetry is on iff an endpoint is set |
| `OTEL_SDK_DISABLED` | unset | `true` forces all telemetry and profiling off even if endpoints are set (the standard OpenTelemetry switch) |
| `ENABLE_TRACING` | unset | Legacy switch: `0` forces telemetry off |
| `ENABLE_PROFILING` | unset | `0` forces profiling off |
| `OTEL_METRIC_EXPORT_INTERVAL` | `15000` | Milliseconds between metric pushes |
| `OTEL_SERVICE_NAME` | `ad-service` | `service.name` — becomes the Prometheus `job` and the Loki `service_name`; keep the default, dashboards depend on it |
| `SERVICE_VERSION` | `1.0.0` | `service.version` resource attribute |
| `PYROSCOPE_ADDR` | unset (profiling off) | Profiles use the Pyroscope SDK push protocol (**not** OTLP). Point it at Alloy (`http://alloy.monitoring.svc.cluster.local:4040`), which forwards to Pyroscope, or directly at Pyroscope |

Notes:

- Telemetry is **pushed**, not scraped: traces, metrics and logs share one OTLP/gRPC endpoint (Alloy); profiles go to Pyroscope (via Alloy). `service.instance.id` is the pod hostname, so replicas stay distinct in Mimir.
- The former Prometheus endpoint (`METRICS_PORT`) no longer exists. Logs keep going to stdout and are additionally exported over OTLP with trace ids.
- With no endpoint set, telemetry and profiling are off: no exporters, no background connections, no error logs (verified by starting the image with none of these variables set and reading its log). With an endpoint that is not reachable the exporters still log errors and the service keeps serving.

## Container image

Multi-stage [`Dockerfile`](Dockerfile):

| Stage | Base | What happens |
|---|---|---|
| builder | `eclipse-temurin:21-jdk-alpine` | installs `libc6-compat` (needed by `protoc-gen-grpc-java` on musl), resolves deps, `./gradlew installDist` |
| runtime | `eclipse-temurin:21-jre-alpine` | copies `build/install/hipstershop`, runs as non-root |

- **Entrypoint:** `bin/AdService` (working dir `/app`). Honours `JAVA_OPTS` / `JAVA_TOOL_OPTIONS`.
- **User:** `appuser` — UID `100`, GID `101` (verified on the built image). Not a numeric `USER` in the Dockerfile, so Kubernetes `runAsNonRoot` needs `runAsUser: 100` set explicitly.
- **EXPOSE:** `9555`.
- **Docker `HEALTHCHECK`:** none (the old one hit the removed `/metrics` endpoint). Kubernetes uses gRPC probes (below).
- **Image size:** ~107 MB.
- **`RUN apk upgrade --no-cache`** in the runtime stage pulls Alpine security fixes published after the `eclipse-temurin:21-jre-alpine` base was built (the image scan failed on a fixable HIGH finding in `brotli-libs`). This makes builds non-reproducible over time by design; the scan is the reason it is there.
- **Build context:** the service root. No `.dockerignore` is present, so `build/`, `.gradle/` and `.git` are sent to the daemon — add one if builds feel slow.

## Kubernetes deployment notes

Everything a Helm chart for this service needs to know.

**Workload:** stateless `Deployment`. Safe to run multiple replicas (no shared state). Note that gRPC connections from `frontend` are long-lived HTTP/2 and a plain `ClusterIP` Service balances per *connection*, not per request — see "gRPC load balancing" in the master README.

**Ports to declare**

| Name | Port | Protocol | Expose via Service? |
|---|---|---|---|
| `grpc` | `9555` | TCP (HTTP/2) | Yes — `frontend` dials `ad-service:9555` |

**Service DNS name expected by callers:** `ad-service` (`frontend` is configured with `AD_SERVICE_ADDR=ad-service:9555`; use the FQDN `ad-service.<namespace>.svc.cluster.local:9555` if frontend lives in another namespace).

**Probes** (JVM start takes a few seconds)

```yaml
startupProbe:        # generous: JVM + Pyroscope agent init
  grpc: { port: 9555 }
  periodSeconds: 3
  failureThreshold: 30
readinessProbe:
  grpc: { port: 9555 }
  periodSeconds: 5
livenessProbe:
  grpc: { port: 9555 }
  periodSeconds: 10
  failureThreshold: 3
```

`grpc:` probes work because the standard health service is registered (empty service name `""`). 

**Security context** (recommended)

```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 100
  runAsGroup: 101
  allowPrivilegeEscalation: false
  capabilities: { drop: ["ALL"] }
  readOnlyRootFilesystem: true   # verified — needs an emptyDir at /tmp that allows exec (Pyroscope native library)
```

**Resources** — requests sized from measured idle usage in the test stack (see the chart's `values.yaml` for limits):

```yaml
resources:
  requests: { cpu: 100m, memory: 192Mi }
  limits:   { cpu: "1", memory: 512Mi }
```

The JVM sizes its heap from the container limit (default 25 %); the chart sets `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError`.

**Config / secrets:** no secrets. All env vars are plain `ConfigMap`/`env` values.

**Telemetry:** pushed, not scraped — OTLP/gRPC to Alloy (`global.monitoring.otlpEndpoint`), profiles to Alloy `:4040`. Metrics carry `job="ad-service"` and `instance=<pod>`; no scrape annotations or `ServiceMonitor` are needed.

**Helm chart:** [`platform-demo-helm/ad-service`](https://github.com/platform-boutique/platform-demo-helm/tree/main/ad-service) (built on the shared `platform-common` library chart). It ships with HA defaults — 2 replicas,
HPA, PodDisruptionBudget, topology spread, NetworkPolicy, restricted pod security context, probes and the full env contract (see Configuration) —
and was verified under the `restricted` Pod Security Standard on the earlier local 3-node kind cluster (since retired). The platform now targets AWS EKS, where this chart has not been deployed or verified yet.

**Deployment and admission policies:** Argo CD deploys the chart from `platform-demo-helm` (`envs/<env>/values/ad-service.yaml` pins the image digest; promotion is a pull request). In the cluster Kyverno enforces, for this service: pods named `app.kubernetes.io/name: ad-service` that run under the ServiceAccount of the same name (Cilium derives the network identity from that label), an image from this repository pinned by digest and signed by our release pipeline, a read-only root filesystem, resource requests and a memory limit, readiness and liveness probes and no API token. The Helm CI evaluates the rendered chart against these policies before merge; see [`platform-demo-helm/policies`](https://github.com/platform-boutique/platform-demo-helm/tree/main/policies).

## Observability

| Signal | Implementation | Destination |
|---|---|---|
| **Traces** | OpenTelemetry SDK, OTLP/gRPC span exporter (`BatchSpanProcessor`); inbound spans from the `opentelemetry-grpc-1.6` server interceptor; W3C `traceparent` propagation; parent-based always-on sampling | `OTEL_EXPORTER_OTLP_ENDPOINT` → Alloy → Tempo |
| **Metrics** | OTel SDK instruments in `Metrics.java`, pushed by a `PeriodicMetricReader` over OTLP/gRPC every 15 s (replaces the old Micrometer/Prometheus endpoint) | Alloy → Mimir |
| **Logs** | Log4j2: ECS JSON to stdout **and** the OpenTelemetry appender (OTLP/gRPC, with `trace_id`/`span_id`) | Alloy → Loki |
| **Profiles** | Pyroscope Java agent (`ITIMER`), pushed to `PYROSCOPE_ADDR` | Alloy `:4040` → Pyroscope |

Metric names: `rpc_server_duration_seconds_{bucket,count,sum}` (explicit buckets 1 ms … 10 s), `rpc_server_requests_total`, `rpc_server_active_requests`, labelled `rpc_system`, `rpc_service`, `rpc_method`, `rpc_grpc_status_code`. The gRPC instrumentation library additionally emits `rpc_server_duration_milliseconds_*` (unused by the dashboard).

Every series carries `job="ad-service"` (from `service.name`) and `instance=<pod name>` (`service.instance.id`), which is what the Grafana dashboards select on. Logs land in Loki with `service_name="ad-service"` and trace/span ids, so a log line links to its trace.

The Pyroscope agent extracts a native library into `/tmp` and loads it, so `/tmp` must be writable **and executable** (an `emptyDir` is; a `noexec` tmpfs is not).

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

No reflection, so the proto is required:

Without a local `grpcurl`, the container image works against a running container (this is how the ad catalog was checked on 2026-10-27: department key, product categories, no key and an unknown key, with no telemetry variables set):

```bash
docker run --rm --network container:<ad-service container> -v "$PWD/src/main/proto:/p:ro" \
  fullstorydev/grpcurl:v1.9.1 -plaintext -import-path /p -proto ad.proto \
  -d '{"context_keys": ["kitchen"]}' localhost:9555 hipstershop.AdService/GetAds
```

```bash
# ads for a product's categories (returns the two `men` ads)
grpcurl -plaintext -proto src/main/proto/ad.proto \
  -d '{"context_keys": ["men", "footwear", "mens-shoes"]}' \
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
│   │   ├── AdService.java       # entrypoint: telemetry bootstrap, gRPC server, metrics HTTP server, shutdown hook
│   │   ├── AdServiceImpl.java   # GetAds handler (+ metrics recording)
│   │   ├── AdsMap.java          # hard-coded ad catalog: departments, aliases, category links
│   │   ├── Metrics.java         # rpc_server_* Micrometer metrics
│   │   └── Telemetry.java       # OTel tracing + Pyroscope init
│   ├── proto/ad.proto           # service contract
│   └── resources/log4j2.xml     # ECS JSON logging
├── build.gradle                 # gRPC 1.79, OTel 1.49, Micrometer, Pyroscope agent, pinned Netty
├── settings.gradle
├── gradlew / gradle/
└── Dockerfile                   # JDK builder -> JRE runtime
```

## Known limitations

- The ad catalog is compiled in; changing ads means a new image.
- The alias table is a copy of the catalog's category words; categories added to the catalog later fall back to random ads until they are added here.
- The frontend currently asks for ads only on the home page (no keys) and the product page (the product's categories); the cart page does not call the ad service in the frontend code as of 2026-10-27.
- The `/?category=<id>` links only work once the frontend supports the `category` query parameter (not part of this repo).
- Ad copy is fictional demo text (discounts and offers are not backed by any pricing logic).
- With an endpoint set but unreachable, the exporters log errors periodically (set none, or OTEL_SDK_DISABLED=true, to turn telemetry off).
- Graceful shutdown calls `server.shutdown()` but does not await in-flight RPCs; set a short `terminationGracePeriodSeconds` (10–15 s is plenty).
