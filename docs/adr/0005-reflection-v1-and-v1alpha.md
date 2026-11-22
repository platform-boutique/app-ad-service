# 5. Server reflection registers v1 and the deprecated v1alpha

Status: accepted (2026-11-22).

## Context

`grpcurl` and other tools need server reflection to work without the proto files (CODE-02). gRPC-Java offers `ProtoReflectionServiceV1` (reflection v1) and the older `ProtoReflectionService` (v1alpha), which is marked deprecated in gRPC 1.79. Older clients still ask for v1alpha.

## Decision

Register both. The deprecation warning of `ProtoReflectionService.newInstance()` is suppressed in the one method that registers the services (`AdService.addStandardServices`, `@SuppressWarnings("deprecation")`), because there is no other way to serve v1alpha. `ServerReflectionTest` checks both versions. `HealthStatusManager` was moved to its non-deprecated package `io.grpc.protobuf.services` (same `grpc-services` artifact).

## Consequences

- `javac -Xlint:all` reports no warnings (checked 2026-11-22), so the report-only lint job of the shared pipeline stays quiet.
- When gRPC removes v1alpha, the one line goes away with it.
