package hipstershop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AdStatsTest {

    private static AdStats stats() {
        return new AdStats(AdsMap.bundled().ads());
    }

    @Test
    void snapshot_listsEveryAdInConfigOrderWithZeroes() {
        List<AdStats.Stat> snapshot = stats().snapshot();
        assertEquals(AdsMap.bundled().ads().size(), snapshot.size());
        assertEquals("women-1", snapshot.get(0).adId());
        for (AdStats.Stat s : snapshot) {
            assertEquals(0, s.impressions());
            assertEquals(0, s.clicks());
        }
    }

    @Test
    void unknownId_isNotCounted() {
        AdStats s = stats();
        assertFalse(s.recordClick("nope"));
        assertFalse(s.recordImpression("nope"));
        assertFalse(s.recordClick(""));
        assertEquals(0, s.snapshot().stream().mapToLong(AdStats.Stat::clicks).sum());
    }

    @Test
    void concurrentUpdates_loseNothing() throws Exception {
        AdStats s = stats();
        int threads = 16;
        int perThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                go.await();
                for (int i = 0; i < perThread; i++) {
                    s.recordImpression("men-1");
                    if (i % 2 == 0) {
                        s.recordClick("men-1");
                    }
                    s.snapshot();
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        AdStats.Stat men1 = s.snapshot().stream().filter(x -> x.adId().equals("men-1")).findFirst().orElseThrow();
        assertEquals((long) threads * perThread, men1.impressions());
        assertEquals((long) threads * perThread / 2, men1.clicks());
    }
}
