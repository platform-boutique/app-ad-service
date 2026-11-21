# 1. Ads from a JSON config, counters in memory

Status: accepted (reconstructed from the code and the earlier README).

## Context

The shop shows two ads per page and counts impressions and clicks. The ads are demo data, they change rarely, and the service has no database.

## Decision

Load the ads once at start from a JSON file (the bundled `ads.json`, or the file named by `ADS_CONFIG`; invalid means exit 1). Match the context keys against the configured ads and rotate the result; with no match return two different random ads. Keep impression and click counters in memory (`AdStats`) and export them as metrics (`ads_impressions_total`, `ads_clicks_total`).

## Consequences

- No state to deploy: changing the ads means a restart, and a mounted ConfigMap is enough.
- The counters reset with the process and each replica counts on its own; the metrics are the way to aggregate. There is no de-duplication of clicks.
- The keywords of the bundled ads are a copy of the catalog's category words and can drift from it.
- The ad copy promises nothing the shop does not do (owner decision 2026-11-13); a test fails if the bundled copy contains a price, percentage, "free", "off", "for 2" or "gift wrap".
