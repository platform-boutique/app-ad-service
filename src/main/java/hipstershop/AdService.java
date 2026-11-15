package hipstershop;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.grpc.services.HealthStatusManager;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.CloseableThreadContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Ad Service entrypoint.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Load and validate the ad config ({@code ADS_CONFIG} or the bundled {@code ads.json}); exit 1 if invalid
 *   <li>Bootstrap observability (traces, metrics, logs over OTLP/gRPC; profiling)
 *   <li>Start the gRPC server with OTel interceptor
 *   <li>Register health status
 * </ul>
 */
public final class AdService {

    // Must stay above the logger field: static initialisers run in textual order and the Log4j configuration
    // reads the level, service name and version from these system properties when the first logger is created.
    static {
        LogConfig.init();
    }

    private static final Logger logger = LogManager.getLogger(AdService.class);

    public static void main(String[] args) throws IOException, InterruptedException {
        // Anything that escapes main (or a thread) is logged as one JSON line, not a bare stack trace on stderr.
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            logger.fatal("uncaught exception in thread {}", t.getName(), e));
        try (CloseableThreadContext.Instance ignored = CloseableThreadContext.put("commit", BuildInfo.commit(System.getenv()))) {
            logger.info("starting");
        }

        // --- Ad configuration: a bad config must stop the process before anything is served ---
        AdCatalog catalog;
        try {
            catalog = AdCatalog.load(System.getenv("ADS_CONFIG"));
        } catch (AdConfigException e) {
            logger.fatal("Invalid ad configuration, refusing to start: {}", e.getMessage());
            System.exit(1);
            return;
        }
        logger.info("Loaded {} ads from {}", catalog.ads().size(), configSource());

        // --- Observability bootstrap ---
        OpenTelemetrySdk otelSdk = Telemetry.init();
        Telemetry.initProfiling();

        // --- gRPC server ---
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "9555"));

        GrpcTelemetry grpcTelemetry = GrpcTelemetry.create(otelSdk);
        Metrics metrics = new Metrics(otelSdk);
        AutoCloseable runtimeMetrics = Telemetry.registerRuntimeMetrics(otelSdk);
        HealthStatusManager healthMgr = new HealthStatusManager();

        Server server = addStandardServices(ServerBuilder.forPort(port), healthMgr)
            .addService(new AdServiceImpl(metrics, catalog))
            .intercept(grpcTelemetry.newServerInterceptor())
            .build()
            .start();

        healthMgr.setStatus("", ServingStatus.SERVING);
        logger.info("AdService gRPC server started on port {}", port);

        // --- Shutdown hook ---
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down AdService...");
            healthMgr.clearStatus("");
            server.shutdown();
            try {
                // in-flight calls get 3 s, then the server is cut; telemetry gets at most 5 s: well inside 10 s
                if (!server.awaitTermination(3, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                server.shutdownNow();
            }
            try {
                runtimeMetrics.close();
            } catch (Exception e) {
                logger.warn("closing runtime metrics failed: {}", e.getClass().getSimpleName());
            }
            Telemetry.shutdown(otelSdk, Duration.ofSeconds(5));
        }));

        server.awaitTermination();
    }

    /** Health and server reflection (CODE-02): reflection v1 and the older v1alpha that grpcurl versions still ask for. */
    static ServerBuilder<?> addStandardServices(ServerBuilder<?> builder, HealthStatusManager health) {
        return builder
            .addService(health.getHealthService())
            .addService(ProtoReflectionServiceV1.newInstance())
            .addService(ProtoReflectionService.newInstance());
    }

    private static String configSource() {
        String path = System.getenv("ADS_CONFIG");
        return path == null || path.isBlank() ? "the bundled ads.json" : "ADS_CONFIG=" + path;
    }
}
