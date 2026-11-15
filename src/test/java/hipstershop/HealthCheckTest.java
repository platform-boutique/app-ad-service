package hipstershop;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.services.HealthStatusManager;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HealthCheckTest {

    private static final Duration DEADLINE = Duration.ofSeconds(10); // generous: only a hung call hits it

    @Test
    void serving_isHealthy() throws IOException {
        HealthStatusManager health = new HealthStatusManager();
        health.setStatus("", ServingStatus.SERVING);
        Server server = ServerBuilder.forPort(0).addService(health.getHealthService()).build().start();
        try {
            assertTrue(HealthCheck.check("localhost", server.getPort(), DEADLINE));
            assertEquals(0, HealthCheck.run(Map.of("PORT", String.valueOf(server.getPort()))));
        } finally {
            server.shutdownNow();
        }
    }

    @Test
    void notServing_isUnhealthy() throws IOException {
        HealthStatusManager health = new HealthStatusManager();
        health.setStatus("", ServingStatus.NOT_SERVING);
        Server server = ServerBuilder.forPort(0).addService(health.getHealthService()).build().start();
        try {
            assertFalse(HealthCheck.check("localhost", server.getPort(), DEADLINE));
            assertEquals(1, HealthCheck.run(Map.of("PORT", String.valueOf(server.getPort()))));
        } finally {
            server.shutdownNow();
        }
    }

    @Test
    void nothingListening_isUnhealthy() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        assertFalse(HealthCheck.check("localhost", closedPort, DEADLINE));
    }

    @Test
    void port_followsTheServerDefaultAndRejectsGarbage() {
        assertEquals(9555, HealthCheck.port(Map.of()));
        assertEquals(9555, HealthCheck.port(Map.of("PORT", " ")));
        assertEquals(7000, HealthCheck.port(Map.of("PORT", " 7000 ")));
        assertEquals(-1, HealthCheck.port(Map.of("PORT", "http")));
        assertEquals(-1, HealthCheck.port(Map.of("PORT", "0")));
        assertEquals(-1, HealthCheck.port(Map.of("PORT", "70000")));
        assertEquals(1, HealthCheck.run(Map.of("PORT", "nope")));
    }
}
