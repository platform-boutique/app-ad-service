package hipstershop;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * gRPC service implementation for the Ad Service.
 * Extracted from AdService to follow single-responsibility principle.
 */
final class AdServiceImpl extends AdServiceGrpc.AdServiceImplBase {

    private static final Logger logger = LogManager.getLogger(AdServiceImpl.class);

    private final Metrics metrics;
    private final AdCatalog catalog;
    private final AdStats stats;
    /** Rotation counter, one tick per GetAds call that matched something. */
    private final AtomicLong rotation = new AtomicLong();

    /** Serves the bundled default catalog. */
    AdServiceImpl(Metrics metrics) {
        this(metrics, AdsMap.bundled());
    }

    AdServiceImpl(Metrics metrics, AdCatalog catalog) {
        this.metrics = metrics;
        this.catalog = catalog;
        this.stats = new AdStats(catalog.ads());
    }

    @Override
    public void getAds(AdRequest req, StreamObserver<AdResponse> responseObserver) {
        metrics.incrementActive("GetAds");
        long startNanos = System.nanoTime();
        String statusCode = "0"; // OK

        try {
            logger.info("Received ad request (context_keys={})", req.getContextKeysList());

            List<Ad> matches = catalog.match(req.getContextKeysList());
            List<Ad> ads = matches.isEmpty()
                ? catalog.random()
                : AdCatalog.rotate(matches, rotation.getAndIncrement());

            for (Ad ad : ads) {
                stats.recordImpression(ad.getId());
                metrics.recordImpression(ad.getId());
            }

            AdResponse response = AdResponse.newBuilder().addAllAds(ads).build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

            logger.info("Served {} ads for context_keys={}", ads.size(), req.getContextKeysList());

        } catch (StatusRuntimeException e) {
            logger.warn("GetAds failed with status {}", e.getStatus());
            statusCode = String.valueOf(e.getStatus().getCode().value());
            responseObserver.onError(e);
        } catch (Exception e) {
            logger.error("GetAds failed with unexpected error", e);
            statusCode = "13"; // INTERNAL
            responseObserver.onError(
                Status.INTERNAL
                    .withDescription("Unexpected error serving ads")
                    .withCause(e)
                    .asRuntimeException()
            );
        } finally {
            double elapsedSeconds = (System.nanoTime() - startNanos) / 1e9;
            metrics.recordCall("GetAds", statusCode, elapsedSeconds);
        }
    }

    @Override
    public void recordClick(RecordClickRequest req, StreamObserver<RecordClickResponse> responseObserver) {
        metrics.incrementActive("RecordClick");
        long startNanos = System.nanoTime();
        Status status = Status.OK;

        try {
            String adId = req.getAdId();
            if (adId.isBlank()) {
                status = Status.INVALID_ARGUMENT.withDescription("ad_id is required");
            } else if (!stats.recordClick(adId)) {
                status = Status.NOT_FOUND.withDescription("unknown ad id: " + adId);
            } else {
                metrics.recordClick(adId);
                logger.info("Recorded click on ad {}", adId);
            }
            if (status.isOk()) {
                responseObserver.onNext(RecordClickResponse.getDefaultInstance());
                responseObserver.onCompleted();
            } else {
                responseObserver.onError(status.asRuntimeException());
            }
        } catch (Exception e) {
            logger.error("RecordClick failed with unexpected error", e);
            status = Status.INTERNAL.withDescription("Unexpected error recording click");
            responseObserver.onError(status.withCause(e).asRuntimeException());
        } finally {
            double elapsedSeconds = (System.nanoTime() - startNanos) / 1e9;
            metrics.recordCall("RecordClick", String.valueOf(status.getCode().value()), elapsedSeconds);
        }
    }

    @Override
    public void getAdStats(GetAdStatsRequest req, StreamObserver<GetAdStatsResponse> responseObserver) {
        metrics.incrementActive("GetAdStats");
        long startNanos = System.nanoTime();
        String statusCode = "0"; // OK

        try {
            GetAdStatsResponse.Builder response = GetAdStatsResponse.newBuilder();
            for (AdStats.Stat s : stats.snapshot()) {
                response.addStats(AdStat.newBuilder()
                    .setAdId(s.adId())
                    .setImpressions(s.impressions())
                    .setClicks(s.clicks()));
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("GetAdStats failed with unexpected error", e);
            statusCode = "13"; // INTERNAL
            responseObserver.onError(
                Status.INTERNAL
                    .withDescription("Unexpected error reading ad stats")
                    .withCause(e)
                    .asRuntimeException()
            );
        } finally {
            double elapsedSeconds = (System.nanoTime() - startNanos) / 1e9;
            metrics.recordCall("GetAdStats", statusCode, elapsedSeconds);
        }
    }
}
