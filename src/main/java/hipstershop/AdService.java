package hipstershop;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.services.HealthStatusManager;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.io.IOException;
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

    private static final Logger logger = LogManager.getLogger(AdService.class);

    public static void main(String[] args) throws IOException, InterruptedException {
        // --- Ad configuration: a bad config must stop the process before anything is served ---
        AdCatalog catalog;
        try {
            catalog = AdCatalog.load(System.getenv("ADS_CONFIG"));
        } catch (AdConfigException e) {
            logger.fatal("Invalid ad configuration, refusing to start: {}", e.getMessage());
            System.err.println("ad-service: invalid ad configuration, refusing to start: " + e.getMessage());
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
        HealthStatusManager healthMgr = new HealthStatusManager();

        Server server = ServerBuilder.forPort(port)
            .addService(new AdServiceImpl(metrics, catalog))
            .addService(healthMgr.getHealthService())
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
            otelSdk.close();
        }));

        server.awaitTermination();
    }

    private static String configSource() {
        String path = System.getenv("ADS_CONFIG");
        return path == null || path.isBlank() ? "the bundled ads.json" : "ADS_CONFIG=" + path;
    }
}
