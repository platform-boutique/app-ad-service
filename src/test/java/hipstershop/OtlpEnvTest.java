package hipstershop;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OtlpEnvTest {

    private static Map<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void headers_none() {
        assertTrue(OtlpEnv.headers(env(), "TRACES").isEmpty());
        assertTrue(OtlpEnv.headers(env("OTEL_EXPORTER_OTLP_HEADERS", "  "), "TRACES").isEmpty());
    }

    @Test
    void headers_generalAppliesToEverySignal() {
        Map<String, String> e = env("OTEL_EXPORTER_OTLP_HEADERS", "X-Scope-OrgID=tenant-1, Authorization = Bearer%20abc%3D%3D");
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("X-Scope-OrgID", "tenant-1");
        expected.put("Authorization", "Bearer abc==");
        for (String signal : new String[] {"TRACES", "METRICS", "LOGS"}) {
            assertEquals(expected, OtlpEnv.headers(e, signal), signal);
        }
    }

    @Test
    void headers_signalSpecificReplacesGeneral() {
        Map<String, String> e = env(
            "OTEL_EXPORTER_OTLP_HEADERS", "a=general",
            "OTEL_EXPORTER_OTLP_METRICS_HEADERS", "a=metrics");
        assertEquals(Map.of("a", "general"), OtlpEnv.headers(e, "TRACES"));
        assertEquals(Map.of("a", "metrics"), OtlpEnv.headers(e, "METRICS"));
    }

    @Test
    void headers_plusStaysPlus_andMalformedPairsAreSkipped() {
        Map<String, String> e = env("OTEL_EXPORTER_OTLP_HEADERS", "k=a+b,novalue,=nokey, ,ok=1,bad=%zz");
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("k", "a+b");
        expected.put("ok", "1");
        expected.put("bad", "%zz");
        assertEquals(expected, OtlpEnv.headers(e, "LOGS"));
    }

    @Test
    void apply_passesHeadersAndCertificates(@TempDir Path dir) throws IOException {
        Path ca = Files.writeString(dir.resolve("ca.pem"), "CA");
        Path cert = Files.writeString(dir.resolve("client.pem"), "CERT");
        Path key = Files.writeString(dir.resolve("client.key"), "KEY");
        Map<String, String> e = env(
            "OTEL_EXPORTER_OTLP_HEADERS", "h=v",
            "OTEL_EXPORTER_OTLP_CERTIFICATE", ca.toString(),
            "OTEL_EXPORTER_OTLP_CLIENT_CERTIFICATE", cert.toString(),
            "OTEL_EXPORTER_OTLP_CLIENT_KEY", key.toString());
        Map<String, String> headers = new HashMap<>();
        byte[][] captured = new byte[3][];
        OtlpEnv.apply(e, "TRACES", headers::put, b -> captured[0] = b, (k, c) -> {
            captured[1] = k;
            captured[2] = c;
        });
        assertEquals(Map.of("h", "v"), headers);
        assertEquals("CA", new String(captured[0], StandardCharsets.UTF_8));
        assertEquals("KEY", new String(captured[1], StandardCharsets.UTF_8));
        assertEquals("CERT", new String(captured[2], StandardCharsets.UTF_8));
    }

    @Test
    void apply_signalSpecificCertificateWins(@TempDir Path dir) throws IOException {
        Path general = Files.writeString(dir.resolve("g.pem"), "GENERAL");
        Path logs = Files.writeString(dir.resolve("l.pem"), "LOGS");
        Map<String, String> e = env(
            "OTEL_EXPORTER_OTLP_CERTIFICATE", general.toString(),
            "OTEL_EXPORTER_OTLP_LOGS_CERTIFICATE", logs.toString());
        byte[][] captured = new byte[1][];
        OtlpEnv.apply(e, "LOGS", (k, v) -> {}, b -> captured[0] = b, (k, c) -> fail("no client TLS configured"));
        assertEquals("LOGS", new String(captured[0], StandardCharsets.UTF_8));
    }

    @Test
    void apply_unreadableCertificateIsIgnored_notFatal(@TempDir Path dir) {
        Map<String, String> e = env(
            "OTEL_EXPORTER_OTLP_CERTIFICATE", dir.resolve("missing.pem").toString());
        OtlpEnv.apply(e, "TRACES", (k, v) -> fail("no headers"),
            b -> fail("an unreadable CA must not be passed on"), (k, c) -> fail("no client TLS"));
    }

    @Test
    void apply_clientTlsNeedsBothFiles(@TempDir Path dir) throws IOException {
        Path cert = Files.writeString(dir.resolve("client.pem"), "CERT");
        Map<String, String> e = env("OTEL_EXPORTER_OTLP_CLIENT_CERTIFICATE", cert.toString());
        OtlpEnv.apply(e, "TRACES", (k, v) -> {}, b -> fail("no CA"), (k, c) -> fail("a lone certificate is not enough"));
    }
}
