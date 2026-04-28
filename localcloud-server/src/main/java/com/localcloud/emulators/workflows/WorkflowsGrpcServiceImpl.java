package com.localcloud.emulators.workflows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.workflows.v1.*;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gRPC service implementation for Cloud Workflows management API.
 * Delegates to WorkflowsServiceImpl for business logic.
 */
public class WorkflowsGrpcServiceImpl extends WorkflowsGrpc.WorkflowsImplBase {
    private static final Logger logger = LoggerFactory.getLogger(WorkflowsGrpcServiceImpl.class);
    private static final ObjectMapper mapper = new ObjectMapper();
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

            String labelsJson = toJsonObjectString(wfProto.getLabelsMap());
            String userEnvVarsJson = toJsonObjectString(wfProto.getUserEnvVarsMap());
            String tagsJson = toJsonObjectString(wfProto.getTagsMap());
            String callLogLevel = normalizeWorkflowCallLogLevel(wfProto.getCallLogLevel());
            String executionHistoryLevel = normalizeExecutionHistoryLevel(wfProto.getExecutionHistoryLevel());

            Map<String, Object> result = service.createWorkflow(projectId, locationId, workflowId,
                    sourceContents, labelsJson, serviceAccount, wfProto.getDescription(), callLogLevel,
                    executionHistoryLevel, wfProto.getCryptoKeyName(), userEnvVarsJson, tagsJson);

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
    public void listWorkflowRevisions(ListWorkflowRevisionsRequest request,
                                      StreamObserver<ListWorkflowRevisionsResponse> responseObserver) {
        try {
            String name = request.getName(); // projects/{p}/locations/{l}/workflows/{w}
            String[] parts = name.split("/");
            String projectId = parts[1];
            String locationId = parts[3];
            String workflowId = parts[5];

            List<Map<String, Object>> revisions = service.listWorkflowRevisions(projectId, locationId, workflowId);
            ListWorkflowRevisionsResponse.Builder responseBuilder = ListWorkflowRevisionsResponse.newBuilder();
            for (Map<String, Object> revision : revisions) {
                responseBuilder.addWorkflows(mapToWorkflowProto(revision));
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (SQLException e) {
            logger.error("Failed to list workflow revisions", e);
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
        if (data.get("description") != null) builder.setDescription(String.valueOf(data.get("description")));
        if (data.get("sourceContents") != null) builder.setSourceContents(String.valueOf(data.get("sourceContents")));
        if (data.get("serviceAccount") != null) builder.setServiceAccount(String.valueOf(data.get("serviceAccount")));
        if (data.get("revisionId") != null) builder.setRevisionId(String.valueOf(data.get("revisionId")));
        if (data.get("cryptoKeyName") != null) builder.setCryptoKeyName(String.valueOf(data.get("cryptoKeyName")));
        if (data.get("createTime") != null) builder.setCreateTime(toTimestamp(data.get("createTime")));
        if (data.get("updateTime") != null) builder.setUpdateTime(toTimestamp(data.get("updateTime")));
        if (data.get("updateTime") != null) builder.setRevisionCreateTime(toTimestamp(data.get("updateTime")));
        builder.putAllLabels(toStringMap(data.get("labels")));
        builder.putAllUserEnvVars(toStringMap(data.get("userEnvVars")));
        builder.putAllTags(toStringMap(data.get("tags")));
        builder.setCallLogLevel(parseWorkflowCallLogLevel(data.get("callLogLevel")));
        builder.setExecutionHistoryLevel(parseExecutionHistoryLevel(data.get("executionHistoryLevel")));

        // Map state string to enum
        String state = data.get("state") != null ? String.valueOf(data.get("state")) : "ACTIVE";
        switch (state) {
            case "ACTIVE" -> builder.setState(Workflow.State.ACTIVE);
            case "UNAVAILABLE" -> builder.setState(Workflow.State.UNAVAILABLE);
            default -> builder.setState(Workflow.State.STATE_UNSPECIFIED);
        }

        return builder.build();
    }

    private String normalizeWorkflowCallLogLevel(Workflow.CallLogLevel level) {
        if (level == null || level == Workflow.CallLogLevel.CALL_LOG_LEVEL_UNSPECIFIED ||
                level == Workflow.CallLogLevel.UNRECOGNIZED) {
            return "LOG_NONE";
        }
        return level.name();
    }

    private String normalizeExecutionHistoryLevel(ExecutionHistoryLevel level) {
        if (level == null || level == ExecutionHistoryLevel.EXECUTION_HISTORY_LEVEL_UNSPECIFIED ||
                level == ExecutionHistoryLevel.UNRECOGNIZED) {
            return "EXECUTION_HISTORY_BASIC";
        }
        return level.name();
    }

    private Workflow.CallLogLevel parseWorkflowCallLogLevel(Object value) {
        if (value == null) return Workflow.CallLogLevel.LOG_NONE;
        try {
            Workflow.CallLogLevel level = Workflow.CallLogLevel.valueOf(String.valueOf(value));
            return level == Workflow.CallLogLevel.CALL_LOG_LEVEL_UNSPECIFIED ? Workflow.CallLogLevel.LOG_NONE : level;
        } catch (IllegalArgumentException e) {
            return Workflow.CallLogLevel.LOG_NONE;
        }
    }

    private ExecutionHistoryLevel parseExecutionHistoryLevel(Object value) {
        if (value == null) return ExecutionHistoryLevel.EXECUTION_HISTORY_BASIC;
        try {
            ExecutionHistoryLevel level = ExecutionHistoryLevel.valueOf(String.valueOf(value));
            return level == ExecutionHistoryLevel.EXECUTION_HISTORY_LEVEL_UNSPECIFIED
                    ? ExecutionHistoryLevel.EXECUTION_HISTORY_BASIC : level;
        } catch (IllegalArgumentException e) {
            return ExecutionHistoryLevel.EXECUTION_HISTORY_BASIC;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> toStringMap(Object value) {
        if (value == null) return Map.of();
        try {
            Object parsed = value instanceof Map<?, ?> ? value : mapper.readValue(String.valueOf(value), Object.class);
            if (!(parsed instanceof Map<?, ?> map)) return Map.of();
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String toJsonObjectString(Map<String, String> value) {
        try {
            return mapper.writeValueAsString(value != null ? value : Map.of());
        } catch (Exception e) {
            return "{}";
        }
    }

    private Timestamp toTimestamp(Object value) {
        if (value instanceof java.sql.Timestamp ts) {
            return Timestamp.newBuilder()
                    .setSeconds(ts.toInstant().getEpochSecond())
                    .setNanos(ts.toInstant().getNano())
                    .build();
        }
        String text = String.valueOf(value);
        try {
            Instant instant = Instant.parse(text);
            return Timestamp.newBuilder()
                    .setSeconds(instant.getEpochSecond())
                    .setNanos(instant.getNano())
                    .build();
        } catch (DateTimeParseException ignored) {
            try {
                java.sql.Timestamp ts = java.sql.Timestamp.valueOf(text);
                Instant instant = ts.toInstant();
                return Timestamp.newBuilder()
                        .setSeconds(instant.getEpochSecond())
                        .setNanos(instant.getNano())
                        .build();
            } catch (IllegalArgumentException e) {
                return Timestamp.getDefaultInstance();
            }
        }
    }
}
