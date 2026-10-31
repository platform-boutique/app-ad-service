# Known limitations

- The ads are read once at start (bundled `ads.json` or `ADS_CONFIG`); changing them means restarting the process.
- Impression/click counters and the rotation counter are in memory and per process: they reset on restart and are not shared between replicas.
- Telemetry/profiling are always on (no kill switch); point the endpoints at something reachable or expect periodic exporter error logs.
- Graceful shutdown calls `server.shutdown()` but does not await in-flight RPCs; set a short `terminationGracePeriodSeconds` (10–15 s is plenty).
