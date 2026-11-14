# Configuration

All configuration is via environment variables. Nothing is required; every variable has a default.

| Variable | Default | Description |
|---|---|---|
| `PORT` | `9555` | gRPC listen port |
| `ADS_CONFIG` | unset (bundled `ads.json`) | Path of a JSON ad config that replaces the bundled one (schema in the README). Invalid or unreadable: the process exits 1 at start |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | unset (off) | OTLP/**gRPC** endpoint for **traces, metrics and logs**: an absolute http(s) URL, e.g. `http://alloy.monitoring.svc.cluster.local:4317`. Telemetry is on iff this is a valid URL; empty or malformed (`alloy:4317`, `ftp://...`) means off with one warning, never a crash. The legacy `COLLECTOR_SERVICE_ADDR` is **removed** |
| `OTEL_SDK_DISABLED` | unset | `true` (trimmed, case-insensitive) forces all telemetry and profiling off even if endpoints are set |
| `ENABLE_PROFILING` | unset | `0` forces profiling off; `1` alone starts nothing (profiling needs `PYROSCOPE_ADDR`). The legacy `ENABLE_TRACING` is **removed** (the compose files still set it; it is ignored here) |
| `OTEL_METRIC_EXPORT_INTERVAL` | `15000` | Milliseconds between metric pushes |
| `OTEL_SERVICE_NAME` | `ad-service` | `service.name`, the `service` log field and the Pyroscope `service_name`; becomes the Prometheus `job` and the Loki `service_name`; keep the default, dashboards depend on it |
| `SERVICE_VERSION` | jar manifest `Implementation-Version` (build property `-PappVersion`, default `dev`) | `service.version`, the `version` log field, the `build_info` metric and the Pyroscope `version` tag |
| `SERVICE_COMMIT` | `unknown` | `commit` in the `starting` log line and in the `build_info` metric  |
| `DEPLOYMENT_ENVIRONMENT` | `local` | `deployment.environment` resource attribute and the Pyroscope `environment` tag |
| `LOG_LEVEL` | `info` | `trace`, `debug`, `info`, `warn`/`warning`, `error`, `fatal`; anything else = `info` |
| `OTEL_EXPORTER_OTLP_PROTOCOL`, `OTEL_RESOURCE_ATTRIBUTES`, `OTEL_TRACES_SAMPLER`(`_ARG`) | not read | OTLP/gRPC and parent-based always-on sampling are fixed in `Telemetry.java`; `service.namespace` is always `everly` |
| `PYROSCOPE_ADDR` | unset (profiling off) | Profiles use the Pyroscope SDK push protocol (**not** OTLP). Point it at Alloy (`http://alloy.monitoring.svc.cluster.local:4040`), which forwards to Pyroscope, or directly at Pyroscope. There is no default address |

Notes:

- Telemetry is **pushed**, not scraped: traces, metrics and logs share one OTLP/gRPC endpoint (Alloy); profiles go to Pyroscope (via Alloy). `service.instance.id` is the pod hostname, so replicas stay distinct in Mimir.
- The former Prometheus endpoint (`METRICS_PORT`) no longer exists. Logs go to stdout as JSON and are additionally exported over OTLP with trace ids.
- `OTEL_SDK_DISABLED=true` switches telemetry and profiling off; with an unreachable collector the service starts and serves, exporters drop data when their bounded queues are full and log throttled error lines.
