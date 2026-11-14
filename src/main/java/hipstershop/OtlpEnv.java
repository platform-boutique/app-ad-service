package hipstershop;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The standard OTLP exporter environment variables (FUT-02) for the hand-built exporters in {@link Telemetry}:
 * extra headers (tenant or auth) and TLS material. Signal-specific variables ({@code OTEL_EXPORTER_OTLP_TRACES_*},
 * {@code _METRICS_*}, {@code _LOGS_*}) win over the general {@code OTEL_EXPORTER_OTLP_*} ones, as in the spec.
 *
 * <ul>
 *   <li>{@code OTEL_EXPORTER_OTLP_HEADERS}: {@code key1=value1,key2=value2}, values percent-decoded
 *   <li>{@code OTEL_EXPORTER_OTLP_CERTIFICATE}: PEM file with the CA(s) that sign the collector certificate
 *   <li>{@code OTEL_EXPORTER_OTLP_CLIENT_CERTIFICATE} + {@code OTEL_EXPORTER_OTLP_CLIENT_KEY}: PEM files for mutual TLS
 *       (the key in PKCS#8 PEM, as the OTLP exporter requires)
 * </ul>
 * Header values are never logged. An unreadable certificate file is logged (path only) and ignored, which leaves
 * the default trust store in place: it never lowers the security of the connection.
 */
final class OtlpEnv {

    private static final Logger logger = LogManager.getLogger(OtlpEnv.class);

    private OtlpEnv() {}

    /** Applies headers and TLS settings of {@code signal} ("TRACES", "METRICS" or "LOGS") to an exporter builder. */
    static void apply(
            Map<String, String> env,
            String signal,
            BiConsumer<String, String> addHeader,
            Consumer<byte[]> trustedCertificates,
            BiConsumer<byte[], byte[]> clientTls) {
        headers(env, signal).forEach(addHeader);

        byte[] ca = readPem(env, signal, "CERTIFICATE");
        if (ca != null) {
            trustedCertificates.accept(ca);
        }
        byte[] clientCert = readPem(env, signal, "CLIENT_CERTIFICATE");
        byte[] clientKey = readPem(env, signal, "CLIENT_KEY");
        if (clientCert != null && clientKey != null) {
            clientTls.accept(clientKey, clientCert);
        } else if (clientCert != null || clientKey != null) {
            logger.warn("OTLP client TLS ignored: OTEL_EXPORTER_OTLP_CLIENT_CERTIFICATE and _CLIENT_KEY must both be set and readable");
        }
    }

    /** Header map for one signal; malformed pairs (no '=', empty key) are skipped. */
    static Map<String, String> headers(Map<String, String> env, String signal) {
        String raw = value(env, signal, "HEADERS");
        Map<String, String> headers = new LinkedHashMap<>();
        if (raw == null) {
            return headers;
        }
        for (String pair : raw.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = pair.substring(0, eq).trim();
            if (key.isEmpty()) {
                continue;
            }
            headers.put(key, decode(pair.substring(eq + 1).trim()));
        }
        return headers;
    }

    private static String decode(String value) {
        try {
            // URLDecoder turns '+' into a space; OTLP values are percent-encoded, '+' stays '+'
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    /** Signal-specific value if set and not blank, else the general one, else null. */
    private static String value(Map<String, String> env, String signal, String name) {
        String specific = env.get("OTEL_EXPORTER_OTLP_" + signal + "_" + name);
        if (specific != null && !specific.isBlank()) {
            return specific.trim();
        }
        String general = env.get("OTEL_EXPORTER_OTLP_" + name);
        return general == null || general.isBlank() ? null : general.trim();
    }

    private static byte[] readPem(Map<String, String> env, String signal, String name) {
        String path = value(env, signal, name);
        if (path == null) {
            return null;
        }
        try {
            return Files.readAllBytes(Path.of(path));
        } catch (IOException | RuntimeException e) {
            logger.warn("Cannot read OTEL_EXPORTER_OTLP_{} file {}: {}", name, path, e.getClass().getSimpleName());
            return null;
        }
    }
}
