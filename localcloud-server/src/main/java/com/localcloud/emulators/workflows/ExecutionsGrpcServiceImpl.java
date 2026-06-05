package com.localcloud.emulators.workflows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.workflows.executions.v1.*;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * gRPC service implementation for Cloud Workflows Executions API.
 * Delegates to WorkflowsServiceImpl for business logic.
 */
public class ExecutionsGrpcServiceImpl extends ExecutionsGrpc.ExecutionsImplBase {
    private static final Logger logger = LoggerFactory.getLogger(ExecutionsGrpcServiceImpl.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final WorkflowsServiceImpl service;

    public ExecutionsGrpcServiceImpl(WorkflowsServiceImpl service) {
        this.service = service;
    }

    @Override
    public void createExecution(CreateExecutionRequest request,
                                StreamObserver<Execution> responseObserver) {
        try {
            String parent = request.getParent(); // projects/{p}/locations/{l}/workflows/{w}
            WorkflowsGrpcServiceImpl.validateExecutionParent(parent, "parent");
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

            String callLogLevel = request.hasExecution()
                    ? normalizeExecutionCallLogLevel(request.getExecution().getCallLogLevel())
                    : "LOG_NONE";
            String labelsJson = request.hasExecution()
                    ? toJsonObjectString(request.getExecution().getLabelsMap())
                    : "{}";

            Map<String, Object> result = service.createExecution(projectId, locationId, workflowId,
                    argument, callLogLevel, labelsJson);

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
            int pageSize = request.getPageSize() > 0 ? Math.min(request.getPageSize(), 1000) : 100;
            String pageToken = request.getPageToken().isEmpty() ? null : request.getPageToken();
            String filter = request.getFilter().isEmpty() ? null : request.getFilter();

            List<Map<String, Object>> executions = service.listExecutions(projectId, locationId, workflowId,
                    pageSize, pageToken, filter);

            ListExecutionsResponse.Builder responseBuilder = ListExecutionsResponse.newBuilder();
            for (Map<String, Object> exec : executions) {
                responseBuilder.addExecutions(mapToExecutionProto(exec));
            }

            // Compute nextPageToken
            if (executions.size() >= pageSize) {
                int offset = pageToken != null ? Integer.parseInt(pageToken.replaceAll("[^0-9]", "")) : 0;
                responseBuilder.setNextPageToken("cursor-" + (offset + pageSize));
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
        if (data.get("startTime") != null) builder.setStartTime(toTimestamp(data.get("startTime")));
        if (data.get("endTime") != null) builder.setEndTime(toTimestamp(data.get("endTime")));
        if (data.get("durationMs") instanceof Number durationMs) {
            builder.setDuration(Duration.newBuilder()
                    .setSeconds(durationMs.longValue() / 1000)
                    .setNanos((int) ((durationMs.longValue() % 1000) * 1_000_000))
                    .build());
        }
        if (data.get("error") != null) {
            builder.setError(Execution.Error.newBuilder()
                    .setPayload(String.valueOf(data.get("error")))
                    .setContext("Execution failed")
                    .build());
        }
        builder.setCallLogLevel(parseExecutionCallLogLevel(data.get("callLogLevel")));
        builder.putAllLabels(toStringMap(data.get("labels")));
        setStatus(builder, data.get("status"));

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

    private String normalizeExecutionCallLogLevel(Execution.CallLogLevel level) {
        if (level == null || level == Execution.CallLogLevel.CALL_LOG_LEVEL_UNSPECIFIED ||
                level == Execution.CallLogLevel.UNRECOGNIZED) {
            return "LOG_NONE";
        }
        return level.name();
    }

    private Execution.CallLogLevel parseExecutionCallLogLevel(Object value) {
        if (value == null) return Execution.CallLogLevel.LOG_NONE;
        try {
            Execution.CallLogLevel level = Execution.CallLogLevel.valueOf(String.valueOf(value));
            return level == Execution.CallLogLevel.CALL_LOG_LEVEL_UNSPECIFIED ? Execution.CallLogLevel.LOG_NONE : level;
        } catch (IllegalArgumentException e) {
            return Execution.CallLogLevel.LOG_NONE;
        }
    }

    @SuppressWarnings("unchecked")
    private void setStatus(Execution.Builder builder, Object value) {
        if (!(value instanceof Map<?, ?> statusMap)) return;
        Object steps = statusMap.get("currentSteps");
        if (!(steps instanceof List<?> list)) return;

        Execution.Status.Builder status = Execution.Status.newBuilder();
        for (Object item : list) {
            if (item instanceof Map<?, ?> stepMap) {
                Object routine = stepMap.get("routine");
                Object step = stepMap.get("step");
                status.addCurrentSteps(Execution.Status.Step.newBuilder()
                        .setRoutine(routine != null ? String.valueOf(routine) : "main")
                        .setStep(step != null ? String.valueOf(step) : "")
                        .build());
            }
        }
        builder.setStatus(status.build());
    }

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
            Instant instant = ts.toInstant();
            return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
        }
        String text = String.valueOf(value);
        try {
            Instant instant = Instant.parse(text);
            return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
        } catch (DateTimeParseException ignored) {
            try {
                java.sql.Timestamp ts = java.sql.Timestamp.valueOf(text);
                Instant instant = ts.toInstant();
                return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
            } catch (IllegalArgumentException e) {
                return Timestamp.getDefaultInstance();
            }
        }
    }
}
