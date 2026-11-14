package hipstershop;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.concurrent.CopyOnWriteArrayList;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.OutputStreamAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.junit.jupiter.api.Test;

/** The request path: a real gRPC call through the OTel server interceptor logs lines that carry that call's ids. */
class LogTraceCorrelationTest {

    @Test
    void logsWrittenInAHandler_carryTheServerSpansIds() throws Exception {
        List<SpanData> finished = new CopyOnWriteArrayList<>();   // in-memory span "exporter", no extra dependency
        SpanExporter spans = new SpanExporter() {
            @Override public CompletableResultCode export(Collection<SpanData> batch) {
                finished.addAll(batch);
                return CompletableResultCode.ofSuccess();
            }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
            .build();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        Appender capture = OutputStreamAppender.newBuilder().setName("corr-" + System.nanoTime()).setTarget(out)
            .setLayout(((AbstractAppender) config.getAppender("STDOUT")).getLayout()).setImmediateFlush(true).build();
        capture.start();
        config.getRootLogger().addAppender(capture, Level.ALL, null);
        ctx.updateLoggers();

        String name = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(name)
            .addService(new AdServiceImpl(new Metrics(sdk)))
            .intercept(GrpcTelemetry.create(sdk).newServerInterceptor())
            .executor(Executors.newFixedThreadPool(2))
            .build().start();
        ManagedChannel channel = InProcessChannelBuilder.forName(name).build();
        try {
            AdServiceGrpc.newBlockingStub(channel).getAds(AdRequest.newBuilder().addContextKeys("kitchen").build());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            config.getRootLogger().removeAppender(capture.getName());
            capture.stop();
        }

        // the server span ends just after the client has its answer: wait (monotonic deadline) for it
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (finished.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, finished.size());
        SpanData server1 = finished.get(0);

        List<JsonNode> lines = new ArrayList<>();
        for (String l : new String(out.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
            if (!l.isEmpty()) {
                lines.add(new ObjectMapper().readTree(l));
            }
        }
        assertTrue(lines.size() >= 2, "Received/Served lines expected: " + lines);
        for (JsonNode n : lines) {
            assertEquals(server1.getTraceId(), n.get("trace_id").asText(), n.toString());
            assertEquals(server1.getSpanId(), n.get("span_id").asText(), n.toString());
        }
    }
}
