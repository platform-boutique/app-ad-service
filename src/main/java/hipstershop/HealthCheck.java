package hipstershop;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Container HEALTHCHECK probe (CODE-03): asks the local server for its standard {@code grpc.health.v1} status and
 * exits 0 only for SERVING. Run directly with the JVM in exec form, so the image needs no shell, nc or curl:
 * {@code java -cp "/app/lib/*" hipstershop.HealthCheck}. It does not touch the logging or telemetry setup.
 */
public final class HealthCheck {

    static final int DEFAULT_PORT = 9555;
    static final Duration TIMEOUT = Duration.ofSeconds(3);

    private HealthCheck() {}

    public static void main(String[] args) {
        System.exit(run(System.getenv()));
    }

    /** Exit code for the probe: 0 healthy, 1 anything else. */
    static int run(Map<String, String> env) {
        int port = port(env);
        if (port < 0) {
            System.err.println("healthcheck: PORT is not a valid port number");
            return 1;
        }
        return check("localhost", port, TIMEOUT) ? 0 : 1;
    }

    /** PORT as the server reads it (default 9555), or -1 when it is not a port number. */
    static int port(Map<String, String> env) {
        String raw = env.get("PORT");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            int port = Integer.parseInt(raw.trim());
            return port >= 1 && port <= 65535 ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static boolean check(String host, int port, Duration timeout) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        try {
            return HealthGrpc.newBlockingStub(channel)
                .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .check(HealthCheckRequest.getDefaultInstance())
                .getStatus() == ServingStatus.SERVING;
        } catch (RuntimeException e) {
            return false;
        } finally {
            channel.shutdownNow();
        }
    }
}
