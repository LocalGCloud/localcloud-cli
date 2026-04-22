package com.localcloud.emulators.workflows;

import com.google.cloud.workflows.executions.v1.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * gRPC service implementation for Cloud Workflows Executions API.
 * Delegates to WorkflowsServiceImpl for business logic.
 */
public class ExecutionsGrpcServiceImpl extends ExecutionsGrpc.ExecutionsImplBase {
    private static final Logger logger = LoggerFactory.getLogger(ExecutionsGrpcServiceImpl.class);
    private final WorkflowsServiceImpl service;

    public ExecutionsGrpcServiceImpl(WorkflowsServiceImpl service) {
        this.service = service;
    }

    @Override
    public void createExecution(CreateExecutionRequest request,
                                StreamObserver<Execution> responseObserver) {
        try {
            String parent = request.getParent(); // projects/{p}/locations/{l}/workflows/{w}
            String[] parts = parent.split("/");
            String projectId = parts[1];
            String locationId = parts[3];
            String workflowId = parts[5];

            String argument = null;
            if (request.hasExecution()) {
                String arg = request.getExecution().getArgument();
                if (arg != null && !arg.isEmpty()) {
                    argument = arg;
                }
            }

            Map<String, Object> result = service.createExecution(projectId, locationId, workflowId, argument);

            responseObserver.onNext(mapToExecutionProto(result));
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (SQLException e) {
            logger.error("Failed to create execution", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Database error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void getExecution(GetExecutionRequest request,
                             StreamObserver<Execution> responseObserver) {
        try {
            String name = request.getName(); // projects/{p}/locations/{l}/workflows/{w}/executions/{e}
            String[] parts = name.split("/");
            String projectId = parts[1];
            String locationId = parts[3];
            String workflowId = parts[5];
            String executionId = parts[7];

            Map<String, Object> result = service.getExecution(projectId, locationId, workflowId, executionId);
            if (result == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Execution not found: " + name)
                        .asRuntimeException());
                return;
            }

            responseObserver.onNext(mapToExecutionProto(result));
            responseObserver.onCompleted();
        } catch (SQLException e) {
            logger.error("Failed to get execution", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Database error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void listExecutions(ListExecutionsRequest request,
                               StreamObserver<ListExecutionsResponse> responseObserver) {
        try {
            String parent = request.getParent(); // projects/{p}/locations/{l}/workflows/{w}
            String[] parts = parent.split("/");
            String projectId = parts[1];
            String locationId = parts[3];
            String workflowId = parts[5];
            int pageSize = request.getPageSize() > 0 ? request.getPageSize() : 100;

            List<Map<String, Object>> executions = service.listExecutions(projectId, locationId, workflowId, pageSize);

            ListExecutionsResponse.Builder responseBuilder = ListExecutionsResponse.newBuilder();
            for (Map<String, Object> exec : executions) {
                responseBuilder.addExecutions(mapToExecutionProto(exec));
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException e) {
            logger.error("Failed to list executions", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Database error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void cancelExecution(CancelExecutionRequest request,
                                StreamObserver<Execution> responseObserver) {
        try {
            String name = request.getName(); // projects/{p}/locations/{l}/workflows/{w}/executions/{e}
            String[] parts = name.split("/");
            String projectId = parts[1];
            String locationId = parts[3];
            String workflowId = parts[5];
            String executionId = parts[7];

            Map<String, Object> result = service.cancelExecution(projectId, locationId, workflowId, executionId);

            responseObserver.onNext(mapToExecutionProto(result));
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (IllegalStateException e) {
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (SQLException e) {
            logger.error("Failed to cancel execution", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Database error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Convert a service-layer Map to an Execution proto.
     */
    private Execution mapToExecutionProto(Map<String, Object> data) {
        Execution.Builder builder = Execution.newBuilder();
        if (data.get("name") != null) builder.setName(String.valueOf(data.get("name")));
        if (data.get("argument") != null) builder.setArgument(String.valueOf(data.get("argument")));
        if (data.get("result") != null) builder.setResult(String.valueOf(data.get("result")));
        if (data.get("workflowRevisionId") != null) builder.setWorkflowRevisionId(String.valueOf(data.get("workflowRevisionId")));

        // Map state string to enum
        String state = data.get("state") != null ? String.valueOf(data.get("state")) : "QUEUED";
        switch (state) {
            case "ACTIVE" -> builder.setState(Execution.State.ACTIVE);
            case "SUCCEEDED" -> builder.setState(Execution.State.SUCCEEDED);
            case "FAILED" -> builder.setState(Execution.State.FAILED);
            case "CANCELLED" -> builder.setState(Execution.State.CANCELLED);
            case "UNAVAILABLE" -> builder.setState(Execution.State.UNAVAILABLE);
            case "QUEUED" -> builder.setState(Execution.State.QUEUED);
            default -> builder.setState(Execution.State.STATE_UNSPECIFIED);
        }

        return builder.build();
    }
}
