package hipstershop;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import java.util.List;

/**
 * Custom gRPC server metrics matching the OTel RPC semantic conventions
 * used across the platform (rpc_server_duration_seconds, rpc_server_requests_total,
 * rpc_server_active_requests) so all Grafana dashboards work consistently.
 *
 * <p>Instruments are exported over OTLP/gRPC by the SDK configured in {@link Telemetry}.
 */
final class Metrics {

    private static final String RPC_SYSTEM  = "grpc";
    private static final String RPC_SERVICE = "hipstershop.AdService";

    private static final AttributeKey<String> RPC_SYSTEM_KEY  = AttributeKey.stringKey("rpc_system");
    private static final AttributeKey<String> RPC_SERVICE_KEY = AttributeKey.stringKey("rpc_service");
    private static final AttributeKey<String> RPC_METHOD_KEY  = AttributeKey.stringKey("rpc_method");
    private static final AttributeKey<String> RPC_STATUS_KEY  = AttributeKey.stringKey("rpc_grpc_status_code");

    private static final AttributeKey<String> AD_ID_KEY = AttributeKey.stringKey("ad_id");

    private final DoubleHistogram duration;
    private final LongCounter requests;
    private final LongUpDownCounter active;
    private final LongCounter impressions;
    private final LongCounter clicks;

    Metrics(OpenTelemetry openTelemetry) {
        Meter meter = openTelemetry.getMeter("ad-service-grpc");

        // Exposed in Prometheus as rpc_server_duration_seconds_{bucket,count,sum}
        duration = meter.histogramBuilder("rpc_server_duration")
            .setDescription("Duration of inbound gRPC calls")
            .setUnit("s")
            .setExplicitBucketBoundariesAdvice(List.of(
                0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0))
            .build();
        requests = meter.counterBuilder("rpc_server_requests_total")
            .setDescription("Total number of inbound gRPC calls")
            .build();
        active = meter.upDownCounterBuilder("rpc_server_active_requests")
            .setDescription("Number of in-flight gRPC calls")
            .build();
        // Exposed in Prometheus as ads_impressions_total / ads_clicks_total{ad_id}
        // build_info{service,version,commit} = 1 (CODE-06), delivered over OTLP like the other metrics
        java.util.Map<String, String> env = System.getenv();
        Attributes build = Attributes.builder()
            .put("service", LogConfig.serviceName(env))
            .put("version", BuildInfo.version(env))
            .put("commit", BuildInfo.commit(env))
            .build();
        meter.gaugeBuilder("build_info")
            .setDescription("Build information of the running service, always 1")
            .ofLongs()
            .buildWithCallback(m -> m.record(1, build));
        impressions = meter.counterBuilder("ads_impressions_total")
            .setDescription("Ads returned by GetAds, per ad id")
            .build();
        clicks = meter.counterBuilder("ads_clicks_total")
            .setDescription("Clicks recorded by RecordClick, per ad id")
            .build();
    }

    void recordImpression(String adId) {
        impressions.add(1, Attributes.of(AD_ID_KEY, adId));
    }

    void recordClick(String adId) {
        clicks.add(1, Attributes.of(AD_ID_KEY, adId));
    }

    void incrementActive(String method) {
        active.add(1, baseAttributes(method));
    }

    void recordCall(String method, String statusCode, double elapsedSeconds) {
        Attributes withStatus = Attributes.builder()
            .put(RPC_SYSTEM_KEY, RPC_SYSTEM)
            .put(RPC_SERVICE_KEY, RPC_SERVICE)
            .put(RPC_METHOD_KEY, method)
            .put(RPC_STATUS_KEY, statusCode)
            .build();

        duration.record(elapsedSeconds, withStatus);
        requests.add(1, withStatus);
        active.add(-1, baseAttributes(method));
    }

    private static Attributes baseAttributes(String method) {
        return Attributes.builder()
            .put(RPC_SYSTEM_KEY, RPC_SYSTEM)
            .put(RPC_SERVICE_KEY, RPC_SERVICE)
            .put(RPC_METHOD_KEY, method)
            .build();
    }
}
