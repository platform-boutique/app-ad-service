package hipstershop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory impression and click counters, one pair per configured ad.
 *
 * <p>Thread-safe and lock-free ({@link LongAdder}). Per process: counters start at zero on every
 * start and are not shared between replicas.
 */
final class AdStats {

    /** Counter snapshot of one ad. */
    record Stat(String adId, long impressions, long clicks) {}

    private final Map<String, LongAdder> impressions = new LinkedHashMap<>();
    private final Map<String, LongAdder> clicks = new LinkedHashMap<>();

    /** The maps are filled here once and only read afterwards, so no synchronisation is needed. */
    AdStats(List<Ad> ads) {
        for (Ad ad : ads) {
            impressions.put(ad.getId(), new LongAdder());
            clicks.put(ad.getId(), new LongAdder());
        }
    }

    /** Counts one impression; returns false (and counts nothing) for an unknown id. */
    boolean recordImpression(String adId) {
        LongAdder counter = impressions.get(adId);
        if (counter == null) {
            return false;
        }
        counter.increment();
        return true;
    }

    /** Counts one click; returns false (and counts nothing) for an unknown id. */
    boolean recordClick(String adId) {
        LongAdder counter = clicks.get(adId);
        if (counter == null) {
            return false;
        }
        counter.increment();
        return true;
    }

    /** Snapshot of every ad in config order, including ads without impressions or clicks. */
    List<Stat> snapshot() {
        List<Stat> result = new ArrayList<>(impressions.size());
        for (Map.Entry<String, LongAdder> e : impressions.entrySet()) {
            result.add(new Stat(e.getKey(), e.getValue().sum(), clicks.get(e.getKey()).sum()));
        }
        return result;
    }
}
