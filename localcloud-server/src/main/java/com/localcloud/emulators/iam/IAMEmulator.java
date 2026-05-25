package com.localcloud.emulators.iam;

import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.iam.v1.TestIamPermissionsResponse;
import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.persistence.PostgresDataSource;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public class IAMEmulator extends AbstractEmulator {
    private final IAMRepository repository;
    private final IAMService service = new IAMService();

    public IAMEmulator(PostgresDataSource dataSource) {
        super("cloudiam", "Cloud IAM", 8080, "grpc", "IAM_EMULATOR_HOST");
        this.repository = new IAMRepository(dataSource);
    }

    public IAMService getServiceImpl() {
        return service;
    }

    @Override protected void doStart() {
        logger.info("Cloud IAM permissive emulator initialized");
    }

    @Override protected void doStop() {}

    @Override protected void doReset() {}

    public class IAMService extends IAMPolicyGrpc.IAMPolicyImplBase {
        @Override public void testIamPermissions(TestIamPermissionsRequest request,
                                                 StreamObserver<TestIamPermissionsResponse> responseObserver) {
            incrementRequestCount();
            responseObserver.onNext(TestIamPermissionsResponse.newBuilder()
                    .addAllPermissions(request.getPermissionsList())
                    .build());
            responseObserver.onCompleted();
        }

        @Override public void getIamPolicy(GetIamPolicyRequest request, StreamObserver<Policy> responseObserver) {
            incrementRequestCount();
            try {
                responseObserver.onNext(repository.get(request.getResource()));
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void setIamPolicy(SetIamPolicyRequest request, StreamObserver<Policy> responseObserver) {
            incrementRequestCount();
            try {
                responseObserver.onNext(repository.set(request.getResource(), request.getPolicy()));
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }
    }
}
