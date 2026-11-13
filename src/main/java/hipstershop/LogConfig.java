package hipstershop;

import java.util.Locale;
import java.util.Map;

/**
 * Values the Log4j2 configuration needs before the first logger is created: level from {@code LOG_LEVEL},
 * service name and version for the {@code service} / {@code version} fields. They are handed to log4j2.xml as
 * system properties, so {@link #init()} must run before any {@code LogManager.getLogger} call in the process.
 */
final class LogConfig {

    private LogConfig() {}

    static final String DEFAULT_SERVICE = "ad-service";

    /** {@code LOG_LEVEL} (debug, info, warn/warning, error, ...) mapped to a Log4j level name; unknown or empty = info. */
    static String level(Map<String, String> env) {
        String v = env.get("LOG_LEVEL");
        if (v == null) {
            return "info";
        }
        switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "trace": return "trace";
            case "debug": return "debug";
            case "warn":
            case "warning": return "warn";
            case "error": return "error";
            case "fatal": return "fatal";
            default: return "info";
        }
    }

    static String serviceName(Map<String, String> env) {
        String v = env.get("OTEL_SERVICE_NAME");
        return v == null || v.isBlank() ? DEFAULT_SERVICE : v.trim();
    }

    static void init() {
        Map<String, String> env = System.getenv();
        System.setProperty("ad.log.level", level(env));
        System.setProperty("ad.service.name", serviceName(env));
        System.setProperty("ad.service.version", BuildInfo.version(env));
        installJulBridge();
    }

    private static volatile boolean julBridged;

    /**
     * The OpenTelemetry SDK and a few libraries log through java.util.logging, whose default handler prints
     * multi-line text to stderr. Replace it with a handler that hands the records to Log4j2, so every line the
     * process writes is the standard JSON shape.
     */
    static synchronized void installJulBridge() {
        if (julBridged) {
            return;
        }
        java.util.logging.Logger root = java.util.logging.LogManager.getLogManager().getLogger("");
        for (java.util.logging.Handler h : root.getHandlers()) {
            root.removeHandler(h);
        }
        root.addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord r) {
                org.apache.logging.log4j.Logger l = org.apache.logging.log4j.LogManager.getLogger(
                    r.getLoggerName() == null ? "jul" : r.getLoggerName());
                org.apache.logging.log4j.Level level = julLevel(r.getLevel());
                if (l.isEnabled(level)) {
                    l.log(level, new java.util.logging.SimpleFormatter().formatMessage(r), r.getThrown());
                }
            }

            @Override public void flush() {}

            @Override public void close() {}
        });
        root.setLevel(java.util.logging.Level.INFO);
        julBridged = true;
    }

    static org.apache.logging.log4j.Level julLevel(java.util.logging.Level l) {
        int v = l.intValue();
        if (v >= java.util.logging.Level.SEVERE.intValue()) {
            return org.apache.logging.log4j.Level.ERROR;
        }
        if (v >= java.util.logging.Level.WARNING.intValue()) {
            return org.apache.logging.log4j.Level.WARN;
        }
        if (v >= java.util.logging.Level.INFO.intValue()) {
            return org.apache.logging.log4j.Level.INFO;
        }
        return v >= java.util.logging.Level.FINE.intValue() ? org.apache.logging.log4j.Level.DEBUG : org.apache.logging.log4j.Level.TRACE;
    }
}
