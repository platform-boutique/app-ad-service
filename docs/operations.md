# Operations

## Health

`grpc.health.v1.Health/Check` on the gRPC port (`9555`) is `SERVING` as soon as the server has started and is never changed. The image `HEALTHCHECK` runs `hipstershop.HealthCheck` with the JVM in exec form (no shell, `nc` or `curl` needed) and exits 0 only on `SERVING`.

```bash
grpcurl -plaintext -import-path ../email-service/proto -proto health.proto localhost:9555 grpc.health.v1.Health/Check
grpcurl -plaintext localhost:9555 list
```

## Shutdown

The shutdown hook (`AdService.java`) calls `server.shutdown()`, waits up to 3 s for in-flight RPCs (then `shutdownNow()`), and flushes telemetry for up to 5 s. A termination grace period of 10 to 15 s is enough. The Pyroscope agent is not stopped, so the last seconds of CPU samples are dropped. A blackholed collector was tested by the author (SIGTERM ended the process in 4 s, exit 143); not re-run for this page.

## Scaling and replicas

Stateless except for the counters, which are per process: each replica counts on its own, so `GetAdStats` and `ads_*_total` only describe the replica that answered (use the metrics to aggregate). The rotation counter is per process too.

## Failure behaviour

| Failure | Behaviour |
|---|---|
| `ADS_CONFIG` unreadable or invalid | the process exits 1 at start with the reason (duplicate id, missing field, bad path) |
| `RecordClick` with an unknown id | `NOT_FOUND` |
| `RecordClick` with an empty id | `INVALID_ARGUMENT` |
| Collector unreachable | the service starts and serves; the exporters have bounded queues (2048 records, dropped when full) and a 3 s deadline per export, and log errors periodically |

## Runbook

| Symptom | Check |
|---|---|
| Container exits at start | the log line: invalid `ADS_CONFIG` |
| Random ads instead of matching ones | the keys sent by the frontend are not in the config; a category added to the catalog later falls back to random until it is added |
| Counters reset | by design: they are per process, in memory |
| Changed ads not shown | `ADS_CONFIG` is read once at start; restart the process (a mounted ConfigMap plus a restart is enough) |
| Last profile samples missing | the Pyroscope agent is not stopped on SIGTERM |

There is no dashboard or alert rule for this service in the repository yet (the dashboards are an open task).
