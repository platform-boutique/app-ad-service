package hipstershop;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.reflection.v1.ServerReflectionGrpc;
import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;
import io.grpc.services.HealthStatusManager;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** CODE-02: the services AdService.main registers through addStandardServices include health and reflection. */
class ServerReflectionTest {

    @Test
    void listsHealthAndReflection_andServesTheAdServiceDescriptor() throws Exception {
        String name = InProcessServerBuilder.generateName();
        Server server = AdService.addStandardServices(InProcessServerBuilder.forName(name).directExecutor(), new HealthStatusManager())
            .addService(new AdServiceImpl(new Metrics(io.opentelemetry.api.OpenTelemetry.noop()), AdCatalog.loadBundled()))
            .build().start();
        ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            List<String> services = ask(channel, ServerReflectionRequest.newBuilder().setListServices("*").build())
                .getListServicesResponse().getServiceList().stream()
                .map(s -> s.getName()).collect(Collectors.toList());
            assertTrue(services.contains("hipstershop.AdService"), services.toString());
            assertTrue(services.contains("grpc.health.v1.Health"), services.toString());
            assertTrue(services.contains("grpc.reflection.v1.ServerReflection"), services.toString());

            ServerReflectionResponse file = ask(channel,
                ServerReflectionRequest.newBuilder().setFileContainingSymbol("hipstershop.AdService").build());
            assertTrue(file.getFileDescriptorResponse().getFileDescriptorProtoCount() > 0);
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
        }
    }

    private static ServerReflectionResponse ask(ManagedChannel channel, ServerReflectionRequest request) throws Exception {
        CompletableFuture<ServerReflectionResponse> answer = new CompletableFuture<>();
        StreamObserver<ServerReflectionRequest> out = ServerReflectionGrpc.newStub(channel)
            .serverReflectionInfo(new StreamObserver<ServerReflectionResponse>() {
                @Override
                public void onNext(ServerReflectionResponse value) {
                    answer.complete(value);
                }

                @Override
                public void onError(Throwable t) {
                    answer.completeExceptionally(t);
                }

                @Override
                public void onCompleted() {}
            });
        out.onNext(request);
        out.onCompleted();
        return answer.get(10, TimeUnit.SECONDS);
    }
}
