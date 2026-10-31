# API

Package `hipstershop`, service `AdService` ([`src/main/proto/ad.proto`](https://github.com/platform-boutique/app-ad-service/blob/main/src/main/proto/ad.proto)). Proto changes are additive only.

| Method | Request | Response | Behaviour |
|---|---|---|---|
| `GetAds` | `AdRequest{ repeated string context_keys }` | `AdResponse{ repeated Ad ads }` (`Ad{ redirect_url, text, id }`) | Matches the keys against the configured ads (department ids, keywords; a key naming a department takes precedence) and returns up to 2, rotating. No match or no keys: 2 different random ads. Every returned ad counts as an impression. |
| `RecordClick` | `RecordClickRequest{ ad_id }` | `RecordClickResponse{}` | Counts a click. `NOT_FOUND` for an unknown id, `INVALID_ARGUMENT` for an empty id. |
| `GetAdStats` | `GetAdStatsRequest{}` | `GetAdStatsResponse{ repeated AdStat{ ad_id, impressions, clicks } }` | Every configured ad, in config order, zero counts included. |

Counters are in memory and per process. The ads, their keys and the config schema are described in the README ("Ad config", "Matching and rotation").
