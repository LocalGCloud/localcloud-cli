package com.localcloud.emulators.iam;

import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.iam.v1.TestIamPermissionsResponse;
import com.google.protobuf.ByteString;
import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.persistence.PostgresDataSource;

import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public class IAMEmulator extends AbstractEmulator {

    /** Response header injected into all IAM gRPC responses. */
    public static final String WARNING_HEADER_NAME = "X-LocalCloud-IAM-Warning";
    public static final String WARNING_HEADER_VALUE =
            "IAM policies are stored but NOT enforced in LocalCloud";

    /** The running IAM emulator instance, set during construction for runtime config access. */
    private static volatile IAMEmulator runningInstance;

    private final IAMRepository repository;
    private volatile boolean logWarnings;
    private final IAMService service = new IAMService();

    public IAMEmulator(PostgresDataSource dataSource) {
        this(dataSource, true);
    }

    public IAMEmulator(PostgresDataSource dataSource, boolean logWarnings) {
        super("cloudiam", "Cloud IAM", 8080, "grpc", "IAM_EMULATOR_HOST");
        this.repository = new IAMRepository(dataSource);
        this.logWarnings = logWarnings;
        runningInstance = this;
    }

    public IAMService getServiceImpl() {
        return service;
    }

    /** Returns the running IAM emulator instance, or null if not started. */
    public static IAMEmulator getRunningInstance() {
        return runningInstance;
    }

    /** Enable or disable per-operation IAM warning logging at runtime. */
    public void setLogWarnings(boolean enabled) {
        this.logWarnings = enabled;
        logger.info("IAM warning logging {}", enabled ? "enabled" : "disabled");
    }

    public boolean isLogWarningsEnabled() {
        return logWarnings;
    }

    /** Returns a gRPC interceptor that injects the IAM warning header. */
    public static ServerInterceptor warningInterceptor() {
        Metadata.Key<String> key = Metadata.Key.of(WARNING_HEADER_NAME, Metadata.ASCII_STRING_MARSHALLER);
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                ServerCall<ReqT, RespT> wrappedCall = new ServerCall<>() {
                    @Override public void request(int numMessages) { call.request(numMessages); }
                    @Override public void sendHeaders(Metadata responseHeaders) {
                        responseHeaders.put(key, WARNING_HEADER_VALUE);
                        call.sendHeaders(responseHeaders);
                    }
                    @Override public void sendMessage(RespT message) { call.sendMessage(message); }
                    @Override public void close(Status status, Metadata trailers) { call.close(status, trailers); }
                    @Override public boolean isCancelled() { return call.isCancelled(); }
                    @Override public MethodDescriptor<ReqT, RespT> getMethodDescriptor() { return call.getMethodDescriptor(); }
                    @Override public void setMessageCompression(boolean enabled) { call.setMessageCompression(enabled); }
                    @Override public void setCompression(String compressor) { call.setCompression(compressor); }
                    @Override public Attributes getAttributes() { return call.getAttributes(); }
                    @Override public String getAuthority() { return call.getAuthority(); }
                    @Override public boolean isReady() { return call.isReady(); }
                };
                return next.startCall(wrappedCall, headers);
            }
        };
    }

    @Override protected void doStart() {
        logger.info("Cloud IAM permissive emulator initialized (warning logging: {})", logWarnings);
    }

    @Override protected void doStop() {}

    @Override protected void doReset() {}

    public class IAMService extends IAMPolicyGrpc.IAMPolicyImplBase {
        @Override public void testIamPermissions(TestIamPermissionsRequest request,
                                                 StreamObserver<TestIamPermissionsResponse> responseObserver) {
            incrementRequestCount();
            if (logWarnings) {
                logger.warn("IAM testIamPermissions: resource={} — all permissions granted (NOT enforced in LocalCloud)",
                        request.getResource());
            }
            responseObserver.onNext(TestIamPermissionsResponse.newBuilder()
                    .addAllPermissions(request.getPermissionsList())
                    .build());
            responseObserver.onCompleted();
        }

        @Override public void getIamPolicy(GetIamPolicyRequest request, StreamObserver<Policy> responseObserver) {
            incrementRequestCount();
            try {
                if (logWarnings) {
                    logger.warn("IAM getIamPolicy: resource={} — policy stored but NOT enforced in LocalCloud",
                            request.getResource());
                }
                Policy policy = repository.get(request.getResource());
                // Enrich with dummy etag to signal non-enforcement
                policy = policy.toBuilder()
                        .setEtag(ByteString.copyFromUtf8("dummy-localcloud"))
                        .build();
                responseObserver.onNext(policy);
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void setIamPolicy(SetIamPolicyRequest request, StreamObserver<Policy> responseObserver) {
            incrementRequestCount();
            try {
                if (logWarnings) {
                    logger.warn("IAM setIamPolicy: resource={} — stored but NOT enforced in LocalCloud",
                            request.getResource());
                }
                responseObserver.onNext(repository.set(request.getResource(), request.getPolicy()));
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }
    }
}
