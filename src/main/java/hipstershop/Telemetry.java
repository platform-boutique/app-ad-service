package hipstershop;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.instrumentation.log4j.appender.v2_17.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.pyroscope.javaagent.EventType;
import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.config.Config;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Bootstraps all observability concerns:
 * - OpenTelemetry traces, metrics and logs, all pushed over OTLP/gRPC (→ Alloy → Tempo / Mimir / Loki)
 * - Pyroscope continuous profiling (SDK push protocol; PYROSCOPE_ADDR may point at Alloy)
 */
final class Telemetry {

    private static final Logger logger = LogManager.getLogger(Telemetry.class);

    private Telemetry() {}

    /**
     * Initialises the OTel SDK (traces + metrics + logs) and registers it globally. Must be called
     * before the gRPC server starts so that the OTel gRPC interceptor attaches correctly.
     *
     * <p>Endpoint: {@code OTEL_EXPORTER_OTLP_ENDPOINT} (scheme required, e.g. {@code http://alloy:4317});
     * {@code COLLECTOR_SERVICE_ADDR} (host:port) is a legacy fallback.
     */
    static OpenTelemetrySdk init() {
        if (!telemetryEnabled(System.getenv())) {
            // No exporter, no background threads, no connection attempts: the SDK object only exists so the
            // rest of the service (gRPC interceptor, metrics instruments) can run unchanged.
            OpenTelemetrySdk disabled = OpenTelemetrySdk.builder().build();
            OpenTelemetryAppender.install(disabled);
            logger.info("Telemetry disabled (no OTEL_EXPORTER_OTLP_ENDPOINT, or OTEL_SDK_DISABLED=true / ENABLE_TRACING=0)");
            return disabled;
        }
        String serviceName = System.getenv().getOrDefault("OTEL_SERVICE_NAME", "ad-service");
        String serviceVersion = System.getenv().getOrDefault("SERVICE_VERSION", "1.0.0");
        String endpoint = resolveEndpoint();
        long metricIntervalMs = Long.parseLong(System.getenv().getOrDefault("OTEL_METRIC_EXPORT_INTERVAL", "15000"));

        Resource resource = Resource.getDefault().toBuilder()
            .put(AttributeKey.stringKey("service.name"), serviceName)
            .put(AttributeKey.stringKey("service.version"), serviceVersion)
            // Distinguishes replicas: in Kubernetes the hostname is the pod name.
            .put("service.instance.id", instanceId())
            .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(BatchSpanProcessor.builder(
                OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).build()).build())
            .build();

        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(PeriodicMetricReader.builder(
                    OtlpGrpcMetricExporter.builder().setEndpoint(endpoint).build())
                .setInterval(Duration.ofMillis(metricIntervalMs))
                .build())
            .build();

        SdkLoggerProvider loggerProvider = SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(BatchLogRecordProcessor.builder(
                OtlpGrpcLogRecordExporter.builder().setEndpoint(endpoint).build()).build())
            .build();

        OpenTelemetrySdk otelSdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .setLoggerProvider(loggerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .buildAndRegisterGlobal();

        // Log4j2 records (configured in log4j2.xml via the OpenTelemetry appender) now flow to the SDK.
        OpenTelemetryAppender.install(otelSdk);

        logger.info("OTel traces, metrics and logs initialised → {} (OTLP/gRPC)", endpoint);
        return otelSdk;
    }

    /**
     * Starts Pyroscope continuous profiling (CPU profile).
     */
    static void initProfiling() {
        if (!profilingEnabled(System.getenv())) {
            logger.info("Profiling disabled (no PYROSCOPE_ADDR, or OTEL_SDK_DISABLED=true / ENABLE_PROFILING=0)");
            return;
        }
        String pyroscopeAddr = System.getenv("PYROSCOPE_ADDR");
        String serviceName   = System.getenv().getOrDefault("OTEL_SERVICE_NAME", "ad-service");

        PyroscopeAgent.start(
            new Config.Builder()
                .setApplicationName(serviceName)
                .setProfilingEvent(EventType.ITIMER)
                .setServerAddress(pyroscopeAddr)
                .build()
        );

        logger.info("Pyroscope profiling initialised → {}", pyroscopeAddr);
    }

    /**
     * Telemetry is on only when an OTLP endpoint is configured; {@code OTEL_SDK_DISABLED=true} (the
     * OpenTelemetry standard switch) and the legacy {@code ENABLE_TRACING=0} force it off.
     */
    static boolean telemetryEnabled(Map<String, String> env) {
        if (isTrue(env.get("OTEL_SDK_DISABLED")) || "0".equals(env.get("ENABLE_TRACING"))) {
            return false;
        }
        return resolveEndpoint(env) != null;
    }

    /** Profiling is on only when {@code PYROSCOPE_ADDR} is set; {@code ENABLE_PROFILING=0} and OTEL_SDK_DISABLED=true force it off. */
    static boolean profilingEnabled(Map<String, String> env) {
        if (isTrue(env.get("OTEL_SDK_DISABLED")) || "0".equals(env.get("ENABLE_PROFILING"))) {
            return false;
        }
        String addr = env.get("PYROSCOPE_ADDR");
        return addr != null && !addr.isBlank();
    }

    private static boolean isTrue(String v) {
        return v != null && v.trim().equalsIgnoreCase("true");
    }

    private static String resolveEndpoint() {
        return resolveEndpoint(System.getenv());
    }

    /** The OTLP endpoint, or null when none is configured. */
    static String resolveEndpoint(Map<String, String> env) {
        String endpoint = env.get("OTEL_EXPORTER_OTLP_ENDPOINT");
        if (endpoint != null && !endpoint.isBlank()) {
            return endpoint;
        }
        String legacy = env.get("COLLECTOR_SERVICE_ADDR");
        if (legacy != null && !legacy.isBlank()) {
            return "http://" + legacy;
        }
        return null;
    }

    private static String instanceId() {
        String host = System.getenv("HOSTNAME");
        if (host != null && !host.isBlank()) {
            return host;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
