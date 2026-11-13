package hipstershop;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TelemetryTest {

    private static Map<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---- OBS-01: telemetry on/off matrix ----

    static Stream<Arguments> telemetryMatrix() {
        return Stream.of(
            Arguments.of("nothing set", env(), false),
            Arguments.of("empty endpoint", env("OTEL_EXPORTER_OTLP_ENDPOINT", ""), false),
            Arguments.of("blank endpoint", env("OTEL_EXPORTER_OTLP_ENDPOINT", "   "), false),
            Arguments.of("host:port without scheme", env("OTEL_EXPORTER_OTLP_ENDPOINT", "alloy:4317"), false),
            Arguments.of("bare host", env("OTEL_EXPORTER_OTLP_ENDPOINT", "alloy"), false),
            Arguments.of("garbage", env("OTEL_EXPORTER_OTLP_ENDPOINT", "ht!tp://%%"), false),
            Arguments.of("wrong scheme", env("OTEL_EXPORTER_OTLP_ENDPOINT", "ftp://alloy:4317"), false),
            Arguments.of("scheme without host", env("OTEL_EXPORTER_OTLP_ENDPOINT", "http://"), false),
            Arguments.of("valid http", env("OTEL_EXPORTER_OTLP_ENDPOINT", "http://alloy:4317"), true),
            Arguments.of("valid https", env("OTEL_EXPORTER_OTLP_ENDPOINT", "https://collector.example:4317"), true),
            Arguments.of("valid with spaces around", env("OTEL_EXPORTER_OTLP_ENDPOINT", " http://alloy:4317 "), true),
            Arguments.of("legacy COLLECTOR_SERVICE_ADDR is ignored", env("COLLECTOR_SERVICE_ADDR", "alloy:4317"), false),
            Arguments.of("legacy ENABLE_TRACING=1 alone does not start anything", env("ENABLE_TRACING", "1"), false),
            Arguments.of("ENABLE_TRACING=0 no longer matters", env("OTEL_EXPORTER_OTLP_ENDPOINT", "http://alloy:4317", "ENABLE_TRACING", "0"), true),
            Arguments.of("OTEL_SDK_DISABLED=true", env("OTEL_EXPORTER_OTLP_ENDPOINT", "http://alloy:4317", "OTEL_SDK_DISABLED", "true"), false),
            Arguments.of("OTEL_SDK_DISABLED=TRUE padded", env("OTEL_EXPORTER_OTLP_ENDPOINT", "http://alloy:4317", "OTEL_SDK_DISABLED", " TRUE "), false),
            Arguments.of("OTEL_SDK_DISABLED=false", env("OTEL_EXPORTER_OTLP_ENDPOINT", "http://alloy:4317", "OTEL_SDK_DISABLED", "false"), true)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("telemetryMatrix")
    void telemetryEnabled_matrix(String name, Map<String, String> env, boolean expected) {
        assertEquals(expected, Telemetry.telemetryEnabled(env), name);
    }

    @Test
    void create_withEmptyOrMalformedEndpoint_doesNotCrashAndStaysOff() {
        for (String bad : new String[] {"", "  ", "alloy:4317", "ht!tp://%%", "http://"}) {
            OpenTelemetrySdk sdk = Telemetry.create(env("OTEL_EXPORTER_OTLP_ENDPOINT", bad), false);
            assertNotNull(sdk, bad);
            Telemetry.shutdown(sdk, Duration.ofSeconds(1));
        }
    }

    static Stream<Arguments> profilingMatrix() {
        String addr = "http://pyroscope:4040";
        return Stream.of(
            Arguments.of("nothing set", env(), false),
            Arguments.of("ENABLE_PROFILING=1 alone starts nothing", env("ENABLE_PROFILING", "1"), false),
            Arguments.of("blank address", env("PYROSCOPE_ADDR", " "), false),
            Arguments.of("address set", env("PYROSCOPE_ADDR", addr), true),
            Arguments.of("address + ENABLE_PROFILING=1", env("PYROSCOPE_ADDR", addr, "ENABLE_PROFILING", "1"), true),
            Arguments.of("address + ENABLE_PROFILING=0", env("PYROSCOPE_ADDR", addr, "ENABLE_PROFILING", "0"), false),
            Arguments.of("address + OTEL_SDK_DISABLED=true", env("PYROSCOPE_ADDR", addr, "OTEL_SDK_DISABLED", "true"), false),
            Arguments.of("address + OTEL_SDK_DISABLED=True padded", env("PYROSCOPE_ADDR", addr, "OTEL_SDK_DISABLED", " True"), false),
            Arguments.of("OTLP endpoint alone does not start profiling", env("OTEL_EXPORTER_OTLP_ENDPOINT", "http://alloy:4317"), false)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profilingMatrix")
    void profilingEnabled_matrix(String name, Map<String, String> env, boolean expected) {
        assertEquals(expected, Telemetry.profilingEnabled(env), name);
    }

    // ---- OBS-02: resource attributes ----

    private static String attr(Resource r, String key) {
        return r.getAttribute(AttributeKey.stringKey(key));
    }

    @Test
    void resource_defaults() {
        Resource r = Telemetry.resource(env("HOSTNAME", "ad-7f9c"));
        assertEquals("ad-service", attr(r, "service.name"));
        assertEquals("dev", attr(r, "service.version"));
        assertEquals("everly", attr(r, "service.namespace"));
        assertEquals("local", attr(r, "deployment.environment"));
        assertEquals("ad-7f9c", attr(r, "service.instance.id"));
    }

    @Test
    void resource_fromEnvironment() {
        Resource r = Telemetry.resource(env(
            "OTEL_SERVICE_NAME", "ad-service-canary", "SERVICE_VERSION", "2.4.1",
            "DEPLOYMENT_ENVIRONMENT", "staging", "HOSTNAME", "pod-1"));
        assertEquals("ad-service-canary", attr(r, "service.name"));
        assertEquals("2.4.1", attr(r, "service.version"));
        assertEquals("everly", attr(r, "service.namespace"));
        assertEquals("staging", attr(r, "deployment.environment"));
        assertEquals("pod-1", attr(r, "service.instance.id"));
    }

    @Test
    void resource_blankValuesFallBackToDefaults() {
        Resource r = Telemetry.resource(env("OTEL_SERVICE_NAME", " ", "SERVICE_VERSION", "", "DEPLOYMENT_ENVIRONMENT", " ", "HOSTNAME", "h"));
        assertEquals("ad-service", attr(r, "service.name"));
        assertEquals("dev", attr(r, "service.version"));
        assertEquals("local", attr(r, "deployment.environment"));
    }

    @Test
    void profileLabels_areServiceNameVersionEnvironment() {
        Map<String, String> labels = Telemetry.profileLabels(env("SERVICE_VERSION", "3.0.0", "DEPLOYMENT_ENVIRONMENT", "prod"));
        assertEquals(Map.of("service_name", "ad-service", "version", "3.0.0", "environment", "prod"), labels);
    }

    // ---- OBS-04 / CODE-04: a blackholed collector never blocks startup or shutdown ----

    @Test
    void blackholedCollector_neverBlocksStartupOrShutdown() throws IOException {
        // accepts TCP connections, never answers: what a firewall that drops replies looks like
        try (ServerSocket blackhole = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        blackhole.accept();
                    }
                } catch (IOException ignored) {
                    // closed at the end of the test
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            long t0 = System.nanoTime();
            OpenTelemetrySdk sdk = Telemetry.create(
                env("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:" + blackhole.getLocalPort()), false);
            double startupSeconds = (System.nanoTime() - t0) / 1e9;
            assertTrue(startupSeconds < 5, "startup took " + startupSeconds + " s");

            sdk.getTracer("t").spanBuilder("pending").startSpan().end();

            long t1 = System.nanoTime();
            Telemetry.shutdown(sdk, Duration.ofSeconds(5));
            double shutdownSeconds = (System.nanoTime() - t1) / 1e9;   // monotonic clock, generous deadline
            assertTrue(shutdownSeconds < 9, "shutdown took " + shutdownSeconds + " s");
        }
    }
}
