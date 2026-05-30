package com.localcloud.emulators.iam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.IAMPolicyGrpc;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.iam.v1.TestIamPermissionsResponse;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * Shared gRPC implementation for IAM policy operations.
 * Can be extended or composed into service implementations.
 * <p>
 * This is a permissive stub: testIamPermissions returns all requested permissions,
 * and setIamPolicy stores the policy but does NOT enforce it.
 */
public class IAMPolicyGrpcHelper {
    private static final Logger logger = LoggerFactory.getLogger(IAMPolicyGrpcHelper.class);
    private final IAMRepository repository;

    public IAMPolicyGrpcHelper(IAMRepository repository) {
        this.repository = repository;
    }

    public void getIamPolicy(GetIamPolicyRequest request, StreamObserver<Policy> responseObserver) {
        try {
            Policy policy = repository.get(request.getResource());
            responseObserver.onNext(policy);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Failed to get IAM policy for {}", request.getResource(), e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    public void setIamPolicy(SetIamPolicyRequest request, StreamObserver<Policy> responseObserver) {
        logger.warn("IAM policy set for {} but NOT enforced in LocalCloud. " +
                "This is a permissive emulator — all requests are allowed.", request.getResource());
        try {
            Policy policy = repository.set(request.getResource(), request.getPolicy());
            responseObserver.onNext(policy);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Failed to set IAM policy for {}", request.getResource(), e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    public void testIamPermissions(TestIamPermissionsRequest request,
                                  StreamObserver<TestIamPermissionsResponse> responseObserver) {
        try {
            TestIamPermissionsResponse.Builder builder = TestIamPermissionsResponse.newBuilder();
            builder.addAllPermissions(request.getPermissionsList());
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Failed to test IAM permissions for {}", request.getResource(), e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }
}
