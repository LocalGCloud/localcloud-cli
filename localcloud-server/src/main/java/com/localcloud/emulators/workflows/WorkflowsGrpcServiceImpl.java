package com.localcloud.emulators.workflows;

import com.google.cloud.workflows.v1.*;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * gRPC service implementation for Cloud Workflows management API.
 * Delegates to WorkflowsServiceImpl for business logic.
 */
public class WorkflowsGrpcServiceImpl extends WorkflowsGrpc.WorkflowsImplBase {
    private static final Logger logger = LoggerFactory.getLogger(WorkflowsGrpcServiceImpl.class);
    private final WorkflowsServiceImpl service;

    public WorkflowsGrpcServiceImpl(WorkflowsServiceImpl service) {
        this.service = service;
    }

    @Override
    public void createWorkflow(CreateWorkflowRequest request,
                               StreamObserver<Operation> responseObserver) {
        try {
            String parent = request.getParent(); // projects/{p}/locations/{l}
            String[] parts = parent.split("/");
            String projectId = parts[1];
            String locationId = parts[3];
            String workflowId = request.getWorkflowId();

            Workflow wfProto = request.getWorkflow();
            String sourceContents = wfProto.getSourceContents();
            String serviceAccount = wfProto.getServiceAccount();

            // Extract labels
            String labelsJson = "{}";
            if (wfProto.getLabelsCount() > 0) {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<String, String> entry : wfProto.getLabelsMap().entrySet()) {
                    if (!first) sb.append(",");
                    sb.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
                    first = false;
                }
                sb.append("}");
                labelsJson = sb.toString();
            }

            Map<String, Object> result = service.createWorkflow(projectId, locationId, workflowId,
                    sourceContents, labelsJson, serviceAccount);

            // Build the Workflow proto from the response
            @SuppressWarnings("unchecked")
            Map<String, Object> wfData = (Map<String, Object>) result.get("response");
            Workflow responseWf = mapToWorkflowProto(wfData);

            Operation op = Operation.newBuilder()
                    .setName(String.valueOf(result.get("name")))
                    .setDone(true)
                    .setResponse(Any.pack(responseWf))
                    .build();

            responseObserver.onNext(op);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (SQLException e) {
            logger.error("Failed to create workflow", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Database error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void getWorkflow(GetWorkflowRequest request,
                            StreamObserver<Workflow> responseObserver) {
        try {
            String name = request.getName(); // projects/{p}/locations/{l}/workflows/{w}
            String[] parts = name.split("/");
            String projectId = parts[1];
            String locationId = parts[3];
            String workflowId = parts[5];

            Map<String, Object> result = service.getWorkflow(projectId, locationId, workflowId);
            if (result == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Workflow not found: " + name)
                        .asRuntimeException());
                return;
            }

            responseObserver.onNext(mapToWorkflowProto(result));
            responseObserver.onCompleted();
        } catch (SQLException e) {
            logger.error("Failed to get workflow", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Database error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void deleteWorkflow(DeleteWorkflowRequest request,
                               StreamObserver<Operation> responseObserver) {
        try {
            String name = request.getName(); // projects/{p}/locations/{l}/workflows/{w}
            String[] parts = name.split("/");
            String projectId = parts[1];
            String locationId = parts[3];
            String workflowId = parts[5];

            Map<String, Object> result = service.deleteWorkflow(projectId, locationId, workflowId);

            Operation op = Operation.newBuilder()
                    .setName(String.valueOf(result.get("name")))
                    .setDone(true)
                    .setResponse(Any.pack(Empty.getDefaultInstance()))
                    .build();

            responseObserver.onNext(op);
            responseObserver.onCompleted();
        } catch (SQLException e) {
            logger.error("Failed to delete workflow", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Database error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void listWorkflows(ListWorkflowsRequest request,
                              StreamObserver<ListWorkflowsResponse> responseObserver) {
        try {
            String parent = request.getParent(); // projects/{p}/locations/{l}
            String[] parts = parent.split("/");
            String projectId = parts[1];
            String locationId = parts[3];
            int pageSize = request.getPageSize() > 0 ? request.getPageSize() : 100;

            List<Map<String, Object>> workflows = service.listWorkflows(projectId, locationId, pageSize);

            ListWorkflowsResponse.Builder responseBuilder = ListWorkflowsResponse.newBuilder();
            for (Map<String, Object> wf : workflows) {
                responseBuilder.addWorkflows(mapToWorkflowProto(wf));
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException e) {
            logger.error("Failed to list workflows", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Database error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void updateWorkflow(UpdateWorkflowRequest request,
                               StreamObserver<Operation> responseObserver) {
        try {
            Workflow wfProto = request.getWorkflow();
            String name = wfProto.getName(); // projects/{p}/locations/{l}/workflows/{w}
            String[] parts = name.split("/");
            String projectId = parts[1];
            String locationId = parts[3];
            String workflowId = parts[5];

            String sourceContents = wfProto.getSourceContents();

            Map<String, Object> result = service.updateWorkflow(projectId, locationId, workflowId,
                    sourceContents.isEmpty() ? null : sourceContents);

            @SuppressWarnings("unchecked")
            Map<String, Object> wfData = (Map<String, Object>) result.get("response");
            Workflow responseWf = mapToWorkflowProto(wfData);

            Operation op = Operation.newBuilder()
                    .setName(String.valueOf(result.get("name")))
                    .setDone(true)
                    .setResponse(Any.pack(responseWf))
                    .build();

            responseObserver.onNext(op);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (SQLException e) {
            logger.error("Failed to update workflow", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Database error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    /**
     * Convert a service-layer Map to a Workflow proto.
     */
    private Workflow mapToWorkflowProto(Map<String, Object> data) {
        Workflow.Builder builder = Workflow.newBuilder();
        if (data.get("name") != null) builder.setName(String.valueOf(data.get("name")));
        if (data.get("sourceContents") != null) builder.setSourceContents(String.valueOf(data.get("sourceContents")));
        if (data.get("serviceAccount") != null) builder.setServiceAccount(String.valueOf(data.get("serviceAccount")));
        if (data.get("revisionId") != null) builder.setRevisionId(String.valueOf(data.get("revisionId")));

        // Map state string to enum
        String state = data.get("state") != null ? String.valueOf(data.get("state")) : "ACTIVE";
        switch (state) {
            case "ACTIVE" -> builder.setState(Workflow.State.ACTIVE);
            case "UNAVAILABLE" -> builder.setState(Workflow.State.UNAVAILABLE);
            default -> builder.setState(Workflow.State.STATE_UNSPECIFIED);
        }

        return builder.build();
    }
}
