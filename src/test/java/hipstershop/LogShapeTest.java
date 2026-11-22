package hipstershop;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.logging.log4j.CloseableThreadContext;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.OutputStreamAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Golden-shape test of the stdout log lines: the real log4j2.xml layout (the one of the STDOUT appender) writes
 * into a buffer, real loggers produce the events, a real (in-memory) OpenTelemetry span is active or not.
 */
class LogShapeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern RFC3339 = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$");
    private static final Logger log = LogManager.getLogger(LogShapeTest.class);

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private Appender capture;
    private Configuration config;

    @BeforeEach
    void attachCapture() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        config = ctx.getConfiguration();
        AbstractAppender stdout = (AbstractAppender) config.getAppender("STDOUT");
        assertNotNull(stdout, "log4j2.xml must define the STDOUT appender");
        capture = OutputStreamAppender.newBuilder()
            .setName("capture-" + System.nanoTime()).setTarget(out).setLayout(stdout.getLayout()).setImmediateFlush(true).build();
        capture.start();
        config.getRootLogger().addAppender(capture, Level.ALL, null);
        ctx.updateLoggers();
    }

    @AfterEach
    void detachCapture() {
        config.getRootLogger().removeAppender(capture.getName());
        capture.stop();
    }

    private List<JsonNode> lines() throws Exception {
        List<JsonNode> nodes = new ArrayList<>();
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            if (!line.isEmpty()) {
                nodes.add(JSON.readTree(line));     // every line must be exactly one JSON object
            }
        }
        return nodes;
    }

    private JsonNode only() throws Exception {
        List<JsonNode> l = lines();
        assertEquals(1, l.size(), "expected exactly one line, got: " + out);
        return l.get(0);
    }

    @Test
    void line_outsideASpan_hasTheStandardFieldsAndNoTraceIds() throws Exception {
        log.info("hello {}", "world");
        JsonNode n = only();
        assertTrue(RFC3339.matcher(n.get("timestamp").asText()).matches(), n.get("timestamp").asText());
        assertEquals("info", n.get("severity").asText());
        assertEquals("hello world", n.get("message").asText());
        assertEquals("ad-service", n.get("service").asText());
        assertEquals("dev", n.get("version").asText());
        assertFalse(n.has("trace_id"), "no span, no trace_id: " + n);
        assertFalse(n.has("span_id"), "no span, no span_id: " + n);
        assertFalse(n.has("error"));
        assertFalse(n.has("@timestamp"), "ECS field names are gone");
        assertFalse(n.has("log.level"), "ECS field names are gone");
    }

    @Test
    @SuppressWarnings("try")
    void line_insideASpan_carriesTheTraceAndSpanId() throws Exception {
        SdkTracerProvider provider = SdkTracerProvider.builder().build();   // in-memory, no exporter
        Tracer tracer = provider.get("test");
        Span span = tracer.spanBuilder("GetAds").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            log.info("inside");
        } finally {
            span.end();
        }
        JsonNode n = only();
        assertEquals(span.getSpanContext().getTraceId(), n.get("trace_id").asText());
        assertEquals(span.getSpanContext().getSpanId(), n.get("span_id").asText());
        assertTrue(n.get("trace_id").asText().matches("[0-9a-f]{32}") && !n.get("trace_id").asText().matches("0+"));
        assertTrue(n.get("span_id").asText().matches("[0-9a-f]{16}") && !n.get("span_id").asText().matches("0+"));
        provider.close();
    }

    @Test
    @SuppressWarnings("try")
    void line_afterTheSpanScopeEnds_hasNoIdsAgain() throws Exception {
        SdkTracerProvider provider = SdkTracerProvider.builder().build();
        Span span = provider.get("test").spanBuilder("op").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            log.info("in");
        }
        log.info("out");
        span.end();
        List<JsonNode> l = lines();
        assertTrue(l.get(0).has("trace_id"));
        assertFalse(l.get(1).has("trace_id"));
        assertFalse(l.get(1).has("span_id"));
        provider.close();
    }

    @Test
    void severity_isLowerCase_andWarnIsWarning() throws Exception {
        log.debug("d");           // below the default level, not written
        log.info("i");
        log.warn("w");
        log.error("e");
        List<JsonNode> l = lines();
        assertEquals(3, l.size(), out.toString());
        assertEquals("info", l.get(0).get("severity").asText());
        assertEquals("warning", l.get(1).get("severity").asText());
        assertEquals("error", l.get(2).get("severity").asText());
    }

    @Test
    void error_withAnException_hasAnErrorStringAndNoStackTrace() throws Exception {
        log.error("GetAds failed", new IllegalStateException("boom"));
        JsonNode n = only();
        assertEquals("error", n.get("severity").asText());
        assertEquals("GetAds failed", n.get("message").asText());
        assertEquals("boom", n.get("error").asText());
        assertFalse(out.toString().contains("\tat "), "no stack trace in the line");
        assertFalse(n.has("stack_trace") || n.has("exception"));
    }

    @Test
    @SuppressWarnings("try")
    void commitField_appearsOnlyWhenPutInTheContext() throws Exception {
        try (CloseableThreadContext.Instance ignored = CloseableThreadContext.put("commit", "abc1234")) {
            log.info("starting");
        }
        log.info("later");
        List<JsonNode> l = lines();
        assertEquals("starting", l.get(0).get("message").asText());
        assertEquals("abc1234", l.get(0).get("commit").asText());
        assertFalse(l.get(1).has("commit"));
    }

    @Test
    void multiLineMessages_stayOneJsonLine() throws Exception {
        log.info("first\nsecond");
        assertEquals("first\nsecond", only().get("message").asText());
    }

    @Test
    void javaUtilLogging_isBridgedIntoTheSameShape() throws Exception {
        LogConfig.installJulBridge();
        java.util.logging.Logger.getLogger("some.library")
            .log(java.util.logging.Level.SEVERE, "Failed to export {0}", "logs");
        JsonNode n = only();
        assertEquals("error", n.get("severity").asText());
        assertEquals("Failed to export logs", n.get("message").asText());
        assertEquals("ad-service", n.get("service").asText());
    }
}
