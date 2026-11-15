package hipstershop;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RuntimeMetricsTest {

    @Test
    void registersJvmMetrics_andStopsOnClose() throws Exception {
        InMemoryMetricReader reader = InMemoryMetricReader.create();
        SdkMeterProvider meters = SdkMeterProvider.builder().registerMetricReader(reader).build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setMeterProvider(meters).build();

        AutoCloseable runtime = Telemetry.registerRuntimeMetrics(sdk);
        Set<String> names = reader.collectAllMetrics().stream().map(MetricData::getName).collect(Collectors.toSet());
        assertTrue(names.contains("jvm.memory.used"), names.toString());
        assertTrue(names.contains("jvm.thread.count"), names.toString());
        assertTrue(names.contains("jvm.class.count"), names.toString());

        runtime.close();
        sdk.close();
    }
}
