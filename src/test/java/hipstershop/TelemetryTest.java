package hipstershop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TelemetryTest {

    @Test
    void telemetry_isOffWithoutEndpoint() {
        assertFalse(Telemetry.telemetryEnabled(Map.of()));
        assertFalse(Telemetry.telemetryEnabled(Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "  ")));
    }

    @Test
    void telemetry_isOnWithEndpointOrLegacyAddress() {
        assertTrue(Telemetry.telemetryEnabled(Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "http://alloy:4317")));
        assertTrue(Telemetry.telemetryEnabled(Map.of("COLLECTOR_SERVICE_ADDR", "alloy:4317")));
        assertEquals("http://alloy:4317", Telemetry.resolveEndpoint(Map.of("COLLECTOR_SERVICE_ADDR", "alloy:4317")));
    }

    @Test
    void telemetry_switchesForceItOff() {
        Map<String, String> base = Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "http://alloy:4317");
        assertFalse(Telemetry.telemetryEnabled(Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "http://alloy:4317", "OTEL_SDK_DISABLED", "true")));
        assertFalse(Telemetry.telemetryEnabled(Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "http://alloy:4317", "ENABLE_TRACING", "0")));
        assertTrue(Telemetry.telemetryEnabled(base));
    }

    @Test
    void profiling_needsAddressAndNoSwitch() {
        assertFalse(Telemetry.profilingEnabled(Map.of()));
        assertTrue(Telemetry.profilingEnabled(Map.of("PYROSCOPE_ADDR", "http://alloy:4040")));
        assertFalse(Telemetry.profilingEnabled(Map.of("PYROSCOPE_ADDR", "http://alloy:4040", "ENABLE_PROFILING", "0")));
        assertFalse(Telemetry.profilingEnabled(Map.of("PYROSCOPE_ADDR", "http://alloy:4040", "OTEL_SDK_DISABLED", "true")));
    }
}
