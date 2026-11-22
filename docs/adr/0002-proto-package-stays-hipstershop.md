# 2. The proto package stays `hipstershop`

Status: accepted exception (owner informed 2026-11-22; application standards section 3, contracts).

## Context

`ad.proto` declares `package hipstershop`, the package of the shop's original services. The caller `frontend` keeps its own copy of the file with the same package, so every call uses the gRPC method names `/hipstershop.AdService/...`. The package name is part of that name on the wire.

## Decision

Keep `package hipstershop` (also the Java package of the code). Contract changes stay additive (new fields or RPCs, no renumbering, no removals), server first; `RecordClick` and `GetAdStats` were added that way. Renaming the package would change the name of every method, so old clients and a new server would no longer find each other (`UNIMPLEMENTED`), and the `buf breaking` check of the pipeline (rule TEST-03) would report it as a breaking change.

## Consequences

- The package does not follow the `hipstershop.<name>` pattern of newer services. This is a recorded exception, not an oversight; the `rpc_method` and `rpc_service` labels carry it.
- A rename would need ad-service and the frontend to change in one coordinated release with a deprecation window.
