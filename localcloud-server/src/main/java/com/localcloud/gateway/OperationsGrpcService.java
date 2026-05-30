package com.localcloud.gateway;

import com.google.longrunning.DeleteOperationRequest;
import com.google.longrunning.GetOperationRequest;
import com.google.longrunning.Operation;
import com.google.longrunning.OperationsGrpc;
import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Permissive Operations gRPC service that intercepts operations calls.
 * Returns done = true for getOperation to satisfy long-running operation polling.
 */
public class OperationsGrpcService extends OperationsGrpc.OperationsImplBase {
    private static final Logger logger = LoggerFactory.getLogger(OperationsGrpcService.class);

    @Override
    public void getOperation(GetOperationRequest request, StreamObserver<Operation> responseObserver) {
        logger.info("getOperation called for: {}", request.getName());
        Operation operation = Operation.newBuilder()
                .setName(request.getName())
                .setDone(true)
                .build();
        responseObserver.onNext(operation);
        responseObserver.onCompleted();
    }

    @Override
    public void deleteOperation(DeleteOperationRequest request, StreamObserver<Empty> responseObserver) {
        logger.info("deleteOperation called for: {}", request.getName());
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();
    }
}
