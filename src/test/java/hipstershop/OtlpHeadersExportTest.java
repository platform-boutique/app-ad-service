package hipstershop;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerBuilder;
import io.grpc.stub.ServerCalls;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * FUT-02 end to end: a fake collector (a plain gRPC server answering the OTLP trace Export call) records the
 * metadata of the request that the real exporter built by {@link Telemetry#create} sends.
 */
class OtlpHeadersExportTest {

    /** Identity marshaller: the fake collector never parses the OTLP payload. */
    private static final MethodDescriptor.Marshaller<byte[]> BYTES = new MethodDescriptor.Marshaller<>() {
        @Override
        public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(InputStream stream) {
            try {
                return stream.readAllBytes();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    };

    @Test
    void exporterSendsTheHeadersFromTheEnvironment() throws Exception {
        CompletableFuture<Metadata> seen = new CompletableFuture<>();
        MethodDescriptor<byte[], byte[]> export = MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("opentelemetry.proto.collector.trace.v1.TraceService/Export")
            .setRequestMarshaller(BYTES)
            .setResponseMarshaller(BYTES)
            .build();
        ServerServiceDefinition service = ServerServiceDefinition
            .builder("opentelemetry.proto.collector.trace.v1.TraceService")
            .addMethod(export, ServerCalls.<byte[], byte[]>asyncUnaryCall((request, observer) -> {
                observer.onNext(new byte[0]);   // an empty ExportTraceServiceResponse
                observer.onCompleted();
            }))
            .build();
        ServerInterceptor capture = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                seen.complete(headers);
                return next.startCall(call, headers);
            }
        };
        Server collector = ServerBuilder.forPort(0).addService(ServerInterceptors.intercept(service, capture)).build().start();
        try {
            Map<String, String> env = new HashMap<>();
            env.put("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:" + collector.getPort());
            env.put("OTEL_EXPORTER_OTLP_HEADERS", "X-Scope-OrgID=tenant-1,Authorization=Bearer%20s3cret");
            OpenTelemetrySdk sdk = Telemetry.create(env, false);
            try {
                sdk.getTracer("t").spanBuilder("export-me").startSpan().end();
                sdk.getSdkTracerProvider().forceFlush().join(10, TimeUnit.SECONDS);
                Metadata headers = seen.get(10, TimeUnit.SECONDS);
                assertEquals("tenant-1", headers.get(Metadata.Key.of("x-scope-orgid", Metadata.ASCII_STRING_MARSHALLER)));
                assertEquals("Bearer s3cret", headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)));
            } finally {
                Telemetry.shutdown(sdk, Duration.ofSeconds(5));
            }
        } finally {
            collector.shutdownNow();
        }
    }
}
