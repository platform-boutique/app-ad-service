# ad-service

A gRPC service that serves contextual advertisements for the platform-demo e-commerce platform (demo store brand: Everly). It returns ads read from a JSON config, matched against the department ids, product categories and search words the frontend sends (at most two per call, rotating), falling back to two random ads when no context is given or nothing matches. It also counts impressions and clicks per ad. It has no downstream dependencies and is called only by `frontend`.

Part of the [platform-demo](https://github.com/platform-boutique) platform — microservices, observability (LGTM stack), GitOps (Argo CD), policy enforcement (Kyverno), infrastructure provisioning (Crossplane) and an internal developer portal (Backstage). See the master `README.md` in the platform-demo root for the whole-system view.

## At a glance

| | |
|---|---|
| **Language / runtime** | Java 21 (Eclipse Temurin), Gradle, gRPC-Java |
| **Image** | `mladenovskistefan/ad-service` |
| **Protocol / port** | gRPC (HTTP/2, plaintext) on `9555` |
| **Metrics** | Pushed over OTLP/gRPC to Alloy → Mimir every 15 s (no scrape endpoint). Dashboard names: `rpc_server_duration_seconds_{bucket,count,sum}`, `rpc_server_requests_total`, `rpc_server_active_requests` |
| **Health** | Standard `grpc.health.v1.Health/Check` on `9555` (always `SERVING` once the server has started) |
| **State** | Ads come from a JSON config (bundled `ads.json` or the file named by `ADS_CONFIG`). Impression/click counters live in memory, per process |
| **Depends on** | Nothing (optional: OTLP collector, Pyroscope) |
| **Called by** | `frontend` (with a 100 ms client-side deadline) |
| **gRPC reflection** | Not enabled — pass `-proto` to `grpcurl` |

## API

Package `hipstershop`, service `AdService` ([`src/main/proto/ad.proto`](src/main/proto/ad.proto)). All changes to the proto are additive; callers that send no keys and ignore the new fields behave as before.

| Method | Request | Response | Behaviour |
|---|---|---|---|
| `GetAds` | `AdRequest{ repeated string context_keys }` | `AdResponse{ repeated Ad ads }` (`Ad{ redirect_url, text, id }`) | Matches the keys against the configured ads (rules below) and returns **up to 2** of them, rotating so successive calls show different ads. If nothing matched (no keys, or only unknown keys), returns 2 **different** random ads from the whole catalog. Every returned ad counts as one impression. |
| `RecordClick` | `RecordClickRequest{ string ad_id }` | `RecordClickResponse{}` | Counts one click on the ad. `NOT_FOUND` for an id that is not in the config, `INVALID_ARGUMENT` for an empty id. |
| `GetAdStats` | `GetAdStatsRequest{}` | `GetAdStatsResponse{ repeated AdStat stats }` (`AdStat{ ad_id, impressions, clicks }`) | One entry per configured ad, in config order, including ads with zero counts. |

`Ad.id` (field 3) is new; it is what `RecordClick` takes. The counters are **per process, in memory**: they restart from zero with the process and are not shared between replicas (see Known limitations). They are also exported as the metrics `ads_impressions_total{ad_id}` and `ads_clicks_total{ad_id}` (OTLP, same pipeline as the `rpc_server_*` metrics).

### Ad config

The ads are a JSON array. The bundled default is [`src/main/resources/ads.json`](src/main/resources/ads.json); set `ADS_CONFIG` to the path of a file to use that file **instead** (not merged).

```json
[
  {
    "id": "women-1",
    "text": "New season dresses and tops at Everly. Fresh colours, easy fits, 20% off this week.",
    "redirect_url": "/?category=women",
    "departments": ["women"],
    "keywords": ["clothing", "footwear", "tops"]
  },
  {
    "id": "search-dresses",
    "text": "Dress up: new-season dresses and skirts at Everly, free returns on every order.",
    "redirect_url": "/?category=women",
    "keywords": ["dress", "dresses", "skirt"]
  }
]
```

| Field | Required | Rules |
|---|---|---|
| `id` | yes | unique in the file; lower-case letters, digits, `.`, `_`, `-` (starts with a letter or digit) |
| `text` | yes | non-blank string |
| `redirect_url` | yes | a path starting with `/` (not `//`) or an `http(s)://` URL; anything else (`javascript:` ...) is rejected |
| `departments` | no | array of department ids from `women, men, accessories, beauty, home, kitchen, sports`, no duplicates |
| `keywords` | no | array of lower-case words (`a-z`, `0-9`, `_`, `-`; no spaces), no duplicates |

An ad needs at least one department or keyword. Validation is strict: invalid JSON, a top-level value that is not a non-empty array, an unknown field, a missing or wrongly typed field, a duplicate id and the rules above all make the process **exit with status 1 at start**, printing the file and the ad (`ad #<index>`, id) that is wrong. It never serves a partial config. An `ADS_CONFIG` path that cannot be read fails the same way (there is no silent fallback to the bundled ads).

The bundled config has **two ads per department** (14, ids `<department>-1`, `<department>-2`, `redirect_url` `/?category=<department id>`) plus **three keyword ads** with no department (`search-dresses`, `search-gifts`, `search-running`, linking to the women, beauty and sports category pages). The 14 department ads carry as keywords the type words / source categories that the product catalog puts into a product's `categories`:

| Department id | Keywords of its ads (type words / source categories from the catalog seed data) |
|---|---|
| `women` | `tops`, `bags`, `womens-dresses`, `womens-shoes`, `womens-bags`, and the shared `clothing`, `footwear` |
| `men` | `mens-shirts`, `mens-shoes`, and the shared `clothing`, `footwear` |
| `accessories` | `jewellery`, `watches`, `sunglasses`, `mens-watches`, `womens-watches`, `womens-jewellery` |
| `beauty` | `fragrances`, `makeup`, `skin-care` |
| `home` | `decor`, `furniture`, `home-decoration` |
| `kitchen` | `kitchen-accessories` |
| `sports` | `sports-accessories` |

### Matching and rotation

- Keys are trimmed and compared case-insensitively; blank keys are ignored. Matching is on whole keys: `dress` does not match `dresses` or `dress shoes` (the frontend sends one search word per key).
- An ad matches a key if the key is one of its `departments` or one of its `keywords`.
- **A key that names a department takes precedence.** If at least one ad matches by department, only the ads matched by department are used. A product's `categories` starts with its department (e.g. `["men","footwear","mens-shoes"]`), so a men's shoe gets the `men` ads and not also the `women` ads through the shared word `footwear`. Only when no key names a department are the keyword matches used (search pages send plain words; `footwear` alone matches the women and men ads).
- The matches are taken in config order without duplicates. If there are more than two, the response contains two of them: the start position is a process-wide counter (one tick per `GetAds` call that matched something) modulo the number of matches, wrapping around, so successive calls cycle through all matches. With exactly two matches both are returned and only their order alternates; with one, that one.
- No match, no keys or only blank keys: two different random ads from the whole config (one if the config has a single ad).

This changes one earlier behaviour: a cart with several departments used to get the ads of all of them; it now gets two.

The keywords of the bundled ads were taken from the distinct `categories` values of the 117 products in `product-catalog-service` `data/seed/products.json` (as of 2026-10-27); `AdsMapTest` checks that each of those values yields an ad. New categories in the catalog need a keyword in the config, otherwise those products fall back to random ads.

## Configuration

All configuration is via environment variables. Nothing is required; every variable has a default.

| Variable | Default | Description |
|---|---|---|
| `PORT` | `9555` | gRPC listen port |
| `ADS_CONFIG` | unset (bundled `ads.json`) | Path of a JSON ad config that replaces the bundled one (schema above). Invalid or unreadable = the process exits 1 at start |
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

Ad counters (OTel counters, `ad_id` attribute): `ads_impressions_total` (one per ad returned by `GetAds`) and `ads_clicks_total` (one per successful `RecordClick`). They are updated next to the in-memory counters; the export to a collector was not run (the tests use a no-op SDK), only the in-process counters and RPCs were verified. `RecordClick` and `GetAdStats` also appear in the `rpc_server_*` metrics with their `rpc_method`.

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

Without a local `grpcurl`, the container image works against a running container (this is how the new RPCs were checked on 2026-11-01: see Verification):

```bash
docker run --rm --network container:<ad-service container> -v "$PWD/src/main/proto:/p:ro" \
  fullstorydev/grpcurl:v1.9.1 -plaintext -import-path /p -proto ad.proto \
  -d '{"context_keys": ["kitchen"]}' localhost:9555 hipstershop.AdService/GetAds
```

```bash
# click on an ad, then read the counters (zero counts are omitted from grpcurl's JSON)
grpcurl -plaintext -proto src/main/proto/ad.proto -d '{"ad_id": "men-1"}' \
  localhost:9555 hipstershop.AdService/RecordClick
grpcurl -plaintext -proto src/main/proto/ad.proto -d '{}' \
  localhost:9555 hipstershop.AdService/GetAdStats

# ads for search words (returns the keyword ad `search-dresses`)
grpcurl -plaintext -proto src/main/proto/ad.proto -d '{"context_keys": ["dress"]}' \
  localhost:9555 hipstershop.AdService/GetAds

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

## Verification

Run on 2026-11-01 (branch `feat/ads-config-clicks`):

- `./gradlew test --no-daemon` in `eclipse-temurin:21-jdk-alpine` (+ `libc6-compat`): 174 tests, 0 failures. Covers config loading (valid file, duplicate id, missing field per required field, bad path, invalid values), a matching table, rotation, `RecordClick`/`GetAdStats` through an in-process gRPC channel, concurrent counting, and the pre-existing `AdsMapTest`/`AdServiceImplTest`/`TelemetryTest` unchanged.
- `docker build -t ad-service:local .` succeeded. A container on a throwaway network, called with `grpcurl` (`-proto`, no reflection): `GetAds` with a department key, a search word and `footwear` twice (different pairs), with no keys; `RecordClick` for a known id (OK) and an unknown id (`NotFound`); `GetAdStats` showing the impressions and the click. With `ADS_CONFIG` pointing at a one-ad file the container served only that ad; with a duplicate-id file and a missing file it exited 1 with the message above. `docker stop` ran the shutdown hook (log line `Shutting down AdService...`, exit 143). The container started with no telemetry variables logged "Telemetry disabled" and "Profiling disabled".
- Not run: the `ads_*_total` export to a real collector, a Kubernetes deployment, the CI pipeline.

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
│   │   ├── Metrics.java         # rpc_server_* and ads_*_total OTel metrics
│   │   └── Telemetry.java       # OTel tracing + Pyroscope init
│   ├── proto/ad.proto           # service contract
│   └── resources/
│       ├── ads.json             # bundled default ad config
│       └── log4j2.xml           # ECS JSON logging
├── build.gradle                 # gRPC 1.79, OTel 1.49, Micrometer, Pyroscope agent, pinned Netty
├── settings.gradle
├── gradlew / gradle/
└── Dockerfile                   # JDK builder -> JRE runtime
```

## Known limitations

- **Counters are per process and in memory**: they reset on every restart and each replica counts on its own, so `GetAdStats` and `ads_*_total` only describe the replica that answered (the metrics are the way to aggregate across replicas). There is no persistence and no de-duplication of clicks.
- The rotation counter is also per process, and shared by all keys: with several different matching sets interleaving, the rotation of one set can skip positions. It is meant to vary the ads shown, not to guarantee an even share.
- `ADS_CONFIG` is read once at start; changing ads means restarting the process (mounting the file as a ConfigMap and restarting is enough, no new image).
- The keywords of the bundled ads are a copy of the catalog's category words; categories added to the catalog later fall back to random ads until they are added to the config.
- The callers (frontend, load generator) are changed in their own repositories; this repo only provides the server side. The proto change is additive, so old callers keep working.
- The `/?category=<id>` links only work once the frontend supports the `category` query parameter (not part of this repo).
- Ad copy is fictional demo text (discounts and offers are not backed by any pricing logic).
- With an endpoint set but unreachable, the exporters log errors periodically (set none, or OTEL_SDK_DISABLED=true, to turn telemetry off).
- Graceful shutdown calls `server.shutdown()` but does not await in-flight RPCs; set a short `terminationGracePeriodSeconds` (10–15 s is plenty).
