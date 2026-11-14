package hipstershop;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.opentelemetry.api.OpenTelemetry;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** GetAds with keys, RecordClick and GetAdStats through a real (in-process) gRPC channel. */
class AdServiceClicksTest {

    private Server server;
    private ManagedChannel channel;
    private AdServiceGrpc.AdServiceBlockingStub stub;

    @BeforeEach
    void setUp() throws IOException {
        String name = InProcessServerBuilder.generateName();
        // real executor (not direct) so the concurrency test runs server-side calls in parallel
        server = InProcessServerBuilder.forName(name)
            .addService(new AdServiceImpl(new Metrics(OpenTelemetry.noop())))
            .executor(Executors.newFixedThreadPool(8))
            .build().start();
        channel = InProcessChannelBuilder.forName(name).build();
        stub = AdServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private Map<String, AdStat> stats() {
        return stub.getAdStats(GetAdStatsRequest.getDefaultInstance()).getStatsList().stream()
            .collect(Collectors.toMap(AdStat::getAdId, s -> s));
    }

    private static AdRequest keys(String... keys) {
        return AdRequest.newBuilder().addAllContextKeys(List.of(keys)).build();
    }

    @Test
    void getAds_returnsAdsWithIds() {
        for (Ad ad : stub.getAds(keys("kitchen")).getAdsList()) {
            assertFalse(ad.getId().isBlank());
            assertTrue(ad.getId().startsWith("kitchen-"), ad.getId());
        }
    }

    @Test
    void getAds_searchWords_returnKeywordAds() {
        AdResponse r = stub.getAds(keys("dress"));
        assertEquals(1, r.getAdsCount());
        assertEquals("search-dresses", r.getAds(0).getId());
    }

    @Test
    void getAds_rotatesWhenMoreThanTwoMatch() {
        // "footwear" matches the 4 women/men ads; successive calls must show different pairs
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 4; i++) {
            AdResponse r = stub.getAds(keys("footwear"));
            assertEquals(2, r.getAdsCount());
            r.getAdsList().forEach(a -> seen.add(a.getId()));
        }
        assertEquals(4, seen.size());
    }

    @Test
    void getAds_countsAnImpressionForEveryReturnedAd() {
        stub.getAds(keys("sports"));   // 2 ads
        stub.getAds(keys("sports"));
        stub.getAds(AdRequest.getDefaultInstance()); // 2 random ads, counted too
        long total = stats().values().stream().mapToLong(AdStat::getImpressions).sum();
        assertEquals(6, total);
        assertTrue(stats().get("sports-1").getImpressions() >= 2);
    }

    @Test
    void recordClick_incrementsOnlyThatAd_andStatsListEveryAd() {
        Map<String, AdStat> before = stats();
        assertEquals(AdsMap.bundled().ads().size(), before.size());
        assertEquals(0, before.get("women-1").getClicks());

        stub.recordClick(RecordClickRequest.newBuilder().setAdId("women-1").build());
        stub.recordClick(RecordClickRequest.newBuilder().setAdId("women-1").build());

        Map<String, AdStat> after = stats();
        assertEquals(2, after.get("women-1").getClicks());
        assertEquals(0, after.get("women-2").getClicks());
    }

    @Test
    void recordClick_unknownId_isNotFound_andCountsNothing() {
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
            () -> stub.recordClick(RecordClickRequest.newBuilder().setAdId("does-not-exist").build()));
        assertEquals(Status.Code.NOT_FOUND, e.getStatus().getCode());
        assertEquals(0, stats().values().stream().mapToLong(AdStat::getClicks).sum());
    }

    @Test
    void recordClick_emptyId_isInvalidArgument() {
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class,
            () -> stub.recordClick(RecordClickRequest.getDefaultInstance()));
        assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());
    }

    @Test
    void concurrentGetAdsAndClicks_areAllCounted() throws Exception {
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                go.await();
                for (int i = 0; i < perThread; i++) {
                    stub.getAds(keys("men"));
                    stub.recordClick(RecordClickRequest.newBuilder().setAdId("men-2").build());
                    stub.getAdStats(GetAdStatsRequest.getDefaultInstance());
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS));

        Map<String, AdStat> s = stats();
        long calls = (long) threads * perThread;
        // "men" has 2 ads and both are returned on every call (rotation only reorders them)
        assertEquals(calls, s.get("men-1").getImpressions());
        assertEquals(calls, s.get("men-2").getImpressions());
        assertEquals(calls, s.get("men-2").getClicks());
        assertEquals(0, s.get("men-1").getClicks());
    }
}
