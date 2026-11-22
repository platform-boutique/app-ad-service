# 4. Metrics are pushed over OTLP, there is no scrape endpoint

Status: accepted exception to the port 9090 rule of the application standards (OBS-20); the standard allows OTLP push for a service that cannot serve a scrape endpoint cheaply. Decided with the OpenTelemetry rewrite of the service (2026-11-13).

## Context

The Go services serve Prometheus metrics on `:9090`. This service already used the OpenTelemetry SDK for traces and logs, and the JVM runtime metrics (`opentelemetry-runtime-telemetry-java17`) come from the same SDK.

## Decision

All metrics go through the OpenTelemetry SDK and a `PeriodicMetricReader` (every 15 s, `OTEL_METRIC_EXPORT_INTERVAL`) to the same OTLP/gRPC endpoint as traces and logs (Alloy, then Mimir). The earlier Micrometer/Prometheus endpoint (`METRICS_PORT`) was removed. With no endpoint, nothing is exported and nothing listens.

## Consequences

- One pipeline and one port for the three signals; `job="ad-service"` and `instance=<pod>` come from the resource attributes.
- `docker exec ... wget :9090/metrics` does not work for this service; to see the series locally use the observability profile of the local stack, or an in-memory reader as in `RuntimeMetricsTest`.
- Metrics are lost while the collector is unreachable (bounded queue, 3 s export deadline).
