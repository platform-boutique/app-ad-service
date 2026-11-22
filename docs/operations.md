# Operations

## Health

`grpc.health.v1.Health/Check` on the gRPC port (`9555`) is `SERVING` as soon as the server has started and is never changed. The image `HEALTHCHECK` runs `hipstershop.HealthCheck` with the JVM in exec form (no shell, `nc` or `curl` needed) and exits 0 only on `SERVING`.

```bash
grpcurl -plaintext -import-path ../email-service/proto -proto health.proto localhost:9555 grpc.health.v1.Health/Check
grpcurl -plaintext localhost:9555 list
```

## Cost of the image healthcheck

The image `HEALTHCHECK` starts a JVM every 10 s (heap capped at `-Xmx32m`, serial GC, C1 only). Measured on 2026-11-22 inside the running container (`--memory 512m`, 11 runs of `docker exec` with a small sampling script, while the service itself was idle at about 154 MiB and 0.4 % CPU): wall time 1.15 to 1.73 s per run (two clusters, about 1.2 s and about 1.7 s), peak resident memory 78.8 to 80.0 MB, CPU time about 1.65 to 1.75 s (all JVM threads, so more than one core is briefly busy). That is a duty cycle of roughly 12 to 17 % of one core and a short 80 MB memory spike every 10 s. The container reports `healthy` under the 512 MiB limit, so the spike fits. Only plain Docker and Compose pay this; Kubernetes uses its own gRPC probes. Accepted, reason in [ADR 3](adr/0003-healthcheck-runs-a-jvm-in-exec-form.md).

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
