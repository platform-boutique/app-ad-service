package hipstershop;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporterBuilder;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporterBuilder;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporterBuilder;
import io.opentelemetry.instrumentation.log4j.appender.v2_17.OpenTelemetryAppender;
import io.opentelemetry.instrumentation.runtimemetrics.java17.RuntimeMetrics;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.OpenTelemetrySdkBuilder;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

    /** Export timeout of every OTLP exporter: a blackholed collector costs at most this long per attempt. */
    static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(3);

    /**
     * Initialises the OTel SDK (traces + metrics + logs) and registers it globally. Must be called
     * before the gRPC server starts so that the OTel gRPC interceptor attaches correctly.
     *
     * <p>Endpoint: {@code OTEL_EXPORTER_OTLP_ENDPOINT}, a valid absolute http(s) URL (e.g. {@code http://alloy:4317}).
     */
    static OpenTelemetrySdk init() {
        return create(System.getenv(), true);
    }

    /** Builds the SDK from {@code env}; {@code register} makes it the global OpenTelemetry (once per JVM). */
    static OpenTelemetrySdk create(Map<String, String> env, boolean register) {
        if (!telemetryEnabled(env)) {
            // No exporter, no background threads, no connection attempts: the SDK object only exists so the
            // rest of the service (gRPC interceptor, metrics instruments) can run unchanged.
            OpenTelemetrySdk disabled = OpenTelemetrySdk.builder().build();
            OpenTelemetryAppender.install(disabled);
            logger.info("Telemetry disabled (no valid OTEL_EXPORTER_OTLP_ENDPOINT, or OTEL_SDK_DISABLED=true)");
            return disabled;
        }
        String endpoint = resolveEndpoint(env);
        long metricIntervalMs = metricIntervalMs(env);
        Resource resource = resource(env);

        // Queues are bounded (BatchSpanProcessor / BatchLogRecordProcessor default 2048 records, drop when full);
        // every export call has a 3 s deadline, so neither startup nor shutdown waits for a dead collector.
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(BatchSpanProcessor.builder(spanExporter(env, endpoint))
                .setExporterTimeout(EXPORT_TIMEOUT).build())
            .build();

        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(PeriodicMetricReader.builder(metricExporter(env, endpoint))
                .setInterval(Duration.ofMillis(metricIntervalMs))
                .build())
            .build();

        SdkLoggerProvider loggerProvider = SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(BatchLogRecordProcessor.builder(logExporter(env, endpoint))
                .setExporterTimeout(EXPORT_TIMEOUT).build())
            .build();

        OpenTelemetrySdkBuilder builder = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .setLoggerProvider(loggerProvider)
            .setPropagators(ContextPropagators.create(
                TextMapPropagator.composite(W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance())));
        OpenTelemetrySdk otelSdk = register ? builder.buildAndRegisterGlobal() : builder.build();

        // Log4j2 records (configured in log4j2.xml via the OpenTelemetry appender) now flow to the SDK.
        OpenTelemetryAppender.install(otelSdk);

        logger.info("OTel traces, metrics and logs initialised -> {} (OTLP/gRPC)", endpoint);
        return otelSdk;
    }

    // Exporters honour the standard OTEL_EXPORTER_OTLP_HEADERS / _CERTIFICATE / _CLIENT_* variables (FUT-02, see OtlpEnv).

    static OtlpGrpcSpanExporter spanExporter(Map<String, String> env, String endpoint) {
        OtlpGrpcSpanExporterBuilder b = OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).setTimeout(EXPORT_TIMEOUT);
        OtlpEnv.apply(env, "TRACES", b::addHeader, b::setTrustedCertificates, b::setClientTls);
        return b.build();
    }

    static OtlpGrpcMetricExporter metricExporter(Map<String, String> env, String endpoint) {
        OtlpGrpcMetricExporterBuilder b = OtlpGrpcMetricExporter.builder().setEndpoint(endpoint).setTimeout(EXPORT_TIMEOUT);
        OtlpEnv.apply(env, "METRICS", b::addHeader, b::setTrustedCertificates, b::setClientTls);
        return b.build();
    }

    static OtlpGrpcLogRecordExporter logExporter(Map<String, String> env, String endpoint) {
        OtlpGrpcLogRecordExporterBuilder b = OtlpGrpcLogRecordExporter.builder().setEndpoint(endpoint).setTimeout(EXPORT_TIMEOUT);
        OtlpEnv.apply(env, "LOGS", b::addHeader, b::setTrustedCertificates, b::setClientTls);
        return b.build();
    }

    /**
     * JVM runtime metrics (OBS-20): memory, GC, threads, classes, CPU as {@code jvm.*} instruments (Prometheus
     * {@code jvm_memory_used_bytes} and the like). Close the result on shutdown. With telemetry off the SDK has no
     * reader, so the callbacks are never invoked.
     */
    static AutoCloseable registerRuntimeMetrics(OpenTelemetry openTelemetry) {
        return RuntimeMetrics.builder(openTelemetry).build();
    }

    /** Resource attributes on every signal (OBS-02). */
    static Resource resource(Map<String, String> env) {
        return Resource.getDefault().toBuilder()
            .put(AttributeKey.stringKey("service.name"), LogConfig.serviceName(env))
            .put(AttributeKey.stringKey("service.version"), BuildInfo.version(env))
            .put(AttributeKey.stringKey("service.namespace"), "everly")
            .put(AttributeKey.stringKey("deployment.environment"), environment(env))
            // Distinguishes replicas: in Kubernetes the hostname is the pod name.
            .put(AttributeKey.stringKey("service.instance.id"), instanceId(env))
            .build();
    }

    static String environment(Map<String, String> env) {
        String v = env.get("DEPLOYMENT_ENVIRONMENT");
        return v == null || v.isBlank() ? "local" : v.trim();
    }

    /** Pyroscope tags (OBS-40): {@code service_name}, {@code version}, {@code environment}. */
    static Map<String, String> profileLabels(Map<String, String> env) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("service_name", LogConfig.serviceName(env));
        labels.put("version", BuildInfo.version(env));
        labels.put("environment", environment(env));
        return labels;
    }

    private static long metricIntervalMs(Map<String, String> env) {
        try {
            long v = Long.parseLong(env.getOrDefault("OTEL_METRIC_EXPORT_INTERVAL", "15000").trim());
            return v > 0 ? v : 15000;
        } catch (NumberFormatException e) {
            return 15000;
        }
    }

    /**
     * Flushes and stops the SDK, waiting at most {@code max}. Exporter calls have a 3 s deadline, so a blackholed
     * collector delays the exit by a few seconds, never indefinitely.
     */
    static void shutdown(OpenTelemetrySdk sdk, Duration max) {
        sdk.shutdown().join(max.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Starts Pyroscope continuous profiling (CPU profile). Only with {@code PYROSCOPE_ADDR}; no default address.
     */
    static void initProfiling() {
        Map<String, String> env = System.getenv();
        if (!profilingEnabled(env)) {
            logger.info("Profiling disabled (no PYROSCOPE_ADDR, or OTEL_SDK_DISABLED=true / ENABLE_PROFILING=0)");
            return;
        }
        String pyroscopeAddr = env.get("PYROSCOPE_ADDR").trim();

        PyroscopeAgent.start(
            new Config.Builder()
                .setApplicationName(LogConfig.serviceName(env))
                .setProfilingEvent(EventType.ITIMER)
                .setServerAddress(pyroscopeAddr)
                .setLabels(profileLabels(env))
                .build()
        );

        logger.info("Pyroscope profiling initialised -> {}", pyroscopeAddr);
    }

    /**
     * Telemetry is on only when {@code OTEL_EXPORTER_OTLP_ENDPOINT} is a valid absolute http(s) URL;
     * {@code OTEL_SDK_DISABLED=true} (trimmed, case-insensitive) forces it off. Empty or malformed means off.
     */
    static boolean telemetryEnabled(Map<String, String> env) {
        if (isTrue(env.get("OTEL_SDK_DISABLED"))) {
            return false;
        }
        return resolveEndpoint(env) != null;
    }

    /** Profiling is on only when {@code PYROSCOPE_ADDR} is set; {@code ENABLE_PROFILING=0} and OTEL_SDK_DISABLED=true force it off. */
    static boolean profilingEnabled(Map<String, String> env) {
        if (isTrue(env.get("OTEL_SDK_DISABLED"))) {
            return false;
        }
        String flag = env.get("ENABLE_PROFILING");
        if (flag != null && flag.trim().equals("0")) {
            return false;
        }
        String addr = env.get("PYROSCOPE_ADDR");
        return addr != null && !addr.isBlank();
    }

    private static boolean isTrue(String v) {
        return v != null && v.trim().equalsIgnoreCase("true");
    }

    /** The OTLP endpoint, or null when it is unset, empty or not an absolute http(s) URL. */
    static String resolveEndpoint(Map<String, String> env) {
        String endpoint = env.get("OTEL_EXPORTER_OTLP_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        endpoint = endpoint.trim();
        try {
            URI uri = new URI(endpoint);
            String scheme = uri.getScheme();
            boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if (!http || uri.getHost() == null || uri.getHost().isEmpty()) {
                logger.warn("Ignoring OTEL_EXPORTER_OTLP_ENDPOINT: not an absolute http(s) URL, telemetry stays off");
                return null;
            }
            return endpoint;
        } catch (URISyntaxException e) {
            logger.warn("Ignoring OTEL_EXPORTER_OTLP_ENDPOINT: not a valid URL, telemetry stays off");
            return null;
        }
    }

    private static String instanceId(Map<String, String> env) {
        String host = env.get("HOSTNAME");
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
