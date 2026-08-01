package com.localcloud.emulators.cloudtasks;

import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.cloud.tasks.v2.CloudTasksGrpc;
import com.google.cloud.tasks.v2.CreateQueueRequest;
import com.google.cloud.tasks.v2.CreateTaskRequest;
import com.google.cloud.tasks.v2.DeleteQueueRequest;
import com.google.cloud.tasks.v2.DeleteTaskRequest;
import com.google.cloud.tasks.v2.GetQueueRequest;
import com.google.cloud.tasks.v2.GetTaskRequest;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.HttpRequest;
import com.google.cloud.tasks.v2.ListQueuesRequest;
import com.google.cloud.tasks.v2.ListQueuesResponse;
import com.google.cloud.tasks.v2.ListTasksRequest;
import com.google.cloud.tasks.v2.ListTasksResponse;
import com.google.cloud.tasks.v2.PauseQueueRequest;
import com.google.cloud.tasks.v2.PurgeQueueRequest;
import com.google.cloud.tasks.v2.Queue;
import com.google.cloud.tasks.v2.RateLimits;
import com.google.cloud.tasks.v2.ResumeQueueRequest;
import com.google.cloud.tasks.v2.RetryConfig;
import com.google.cloud.tasks.v2.RunTaskRequest;
import com.google.cloud.tasks.v2.Task;
import com.google.cloud.tasks.v2.UpdateQueueRequest;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.iam.v1.TestIamPermissionsResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.Empty;
import com.google.protobuf.FieldMask;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.emulators.iam.IAMPolicyGrpcHelper;
import com.localcloud.emulators.iam.IAMRepository;
import com.localcloud.persistence.PostgresDataSource;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * Cloud Tasks gRPC emulator.
 * Implements the CloudTasks gRPC API with PostgreSQL-backed queues and persisted tasks.
 */
public class CloudTasksEmulator extends AbstractEmulator {

    private final CloudTasksStore store;
    private final TaskDispatcher dispatcher;
    private final CloudTasksServiceImpl serviceImpl;
    private final IAMPolicyGrpcHelper iamHelper;

    public CloudTasksEmulator(PostgresDataSource dataSource) {
        super("cloudtasks", "Cloud Tasks", 24080, "grpc", "CLOUD_TASKS_EMULATOR_HOST");
        this.store = new CloudTasksStore(dataSource);
        this.dispatcher = new TaskDispatcher(store);
        this.iamHelper = new IAMPolicyGrpcHelper(new IAMRepository(dataSource));
        this.serviceImpl = new CloudTasksServiceImpl();
    }

    @Override
    protected void doStart() throws Exception {
        // Reload persisted tasks from database
        store.reloadTasks();
        dispatcher.start();
        logger.info("Cloud Tasks emulator gRPC services ready (tasks reloaded from DB)");
    }

    @Override
    protected void doStop() {
        dispatcher.stop();
    }

    @Override
    protected void doReset() {
        store.clearAll();
        logger.info("Cloud Tasks emulator state reset");
    }

    /**
     * Returns the gRPC BindableService for registration with the server.
     */
    public CloudTasksServiceImpl getServiceImpl() {
        return serviceImpl;
    }

    public CloudTasksStore getStore() {
        return store;
    }

    // --- gRPC Service Implementation ---

    public class CloudTasksServiceImpl extends CloudTasksGrpc.CloudTasksImplBase {

        @Override
        public void createQueue(CreateQueueRequest request, StreamObserver<Queue> responseObserver) {
            incrementRequestCount();
            try {
                String parent = request.getParent(); // projects/{project}/locations/{location}
                String[] locParts = CloudTasksStore.parseLocationName(parent);
                Queue queueProto = request.getQueue();
                String queueName = queueProto.getName();

                // Extract queue ID from full name
                String queueId;
                if (queueName != null && !queueName.isEmpty()) {
                    String[] parts = CloudTasksStore.parseQueueName(queueName);
                    queueId = parts[2];
                } else {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Queue name is required")
                            .asRuntimeException());
                    return;
                }

                if (store.queueExists(locParts[0], locParts[1], queueId)) {
                    responseObserver.onError(Status.ALREADY_EXISTS
                            .withDescription("Queue already exists: " + queueName)
                            .asRuntimeException());
                    return;
                }

                CloudTasksStore.QueueConfig config = protoToQueueConfig(queueProto);
                store.createQueue(locParts[0], locParts[1], queueId, config);

                CloudTasksStore.QueueConfig created = store.getQueueConfig(locParts[0], locParts[1], queueId);
                String fullName = parent + "/queues/" + queueId;
                responseObserver.onNext(buildQueue(fullName, created));
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (SQLException e) {
                logger.error("Failed to create queue", e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void updateQueue(UpdateQueueRequest request, StreamObserver<Queue> responseObserver) {
            incrementRequestCount();
            try {
                Queue queueProto = request.getQueue();
                String queueName = queueProto.getName();
                if (queueName == null || queueName.isEmpty()) {
                    responseObserver.onError(Status.INVALID_ARGUMENT
                            .withDescription("Queue name is required for update")
                            .asRuntimeException());
                    return;
                }

                String[] parts = CloudTasksStore.parseQueueName(queueName);
                if (!store.queueExists(parts[0], parts[1], parts[2])) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Queue not found: " + queueName)
                            .asRuntimeException());
                    return;
                }

                FieldMask updateMask = request.getUpdateMask();
                CloudTasksStore.QueueConfig config = protoToQueueConfig(queueProto);

                // If update mask is empty, update all fields; otherwise merge only specified paths
                if (updateMask == null || updateMask.getPathsCount() == 0) {
                    store.updateQueue(parts[0], parts[1], parts[2], config);
                } else {
                    // Start with current config from DB, then apply only masked fields
                    CloudTasksStore.QueueConfig partial = store.getQueueConfig(parts[0], parts[1], parts[2]);
                    if (partial == null) {
                        responseObserver.onError(Status.NOT_FOUND
                                .withDescription("Queue not found: " + queueName)
                                .asRuntimeException());
                        return;
                    }
                    for (String path : updateMask.getPathsList()) {
                        switch (path) {
                            case "rate_limits.max_dispatches_per_second":
                                partial.maxDispatchesPerSecond = config.maxDispatchesPerSecond;
                                break;
                            case "rate_limits.max_concurrent_dispatches":
                                partial.maxConcurrentDispatches = config.maxConcurrentDispatches;
                                break;
                            case "rate_limits.max_burst_size":
                                partial.maxBurstSize = config.maxBurstSize;
                                break;
                            case "retry_config.max_attempts":
                                partial.maxAttempts = config.maxAttempts;
                                break;
                            case "retry_config.min_backoff":
                                partial.minBackoff = config.minBackoff;
                                break;
                            case "retry_config.max_backoff":
                                partial.maxBackoff = config.maxBackoff;
                                break;
                            case "retry_config.max_doublings":
                                partial.maxDoublings = config.maxDoublings;
                                break;
                            case "retry_config.max_retry_duration":
                                partial.maxRetryDuration = config.maxRetryDuration;
                                break;
                            case "http_target.uri_override":
                            case "http_target":
                                partial.httpTargetUri = config.httpTargetUri;
                                partial.httpTargetMethod = config.httpTargetMethod;
                                break;
                            default:
                                logger.debug("Ignoring unknown FieldMask path: {}", path);
                                break;
                        }
                    }
                    store.updateQueue(parts[0], parts[1], parts[2], partial);
                }

                CloudTasksStore.QueueConfig updated = store.getQueueConfig(parts[0], parts[1], parts[2]);
                responseObserver.onNext(buildQueue(queueName, updated));
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (SQLException e) {
                logger.error("Failed to update queue", e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void purgeQueue(PurgeQueueRequest request, StreamObserver<Queue> responseObserver) {
            incrementRequestCount();
            try {
                String fullName = request.getName();
                String[] parts = CloudTasksStore.parseQueueName(fullName);

                if (!store.queueExists(parts[0], parts[1], parts[2])) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Queue not found: " + fullName)
                            .asRuntimeException());
                    return;
                }

                store.purgeQueue(parts[0], parts[1], parts[2]);

                CloudTasksStore.QueueConfig config = store.getQueueConfig(parts[0], parts[1], parts[2]);
                responseObserver.onNext(buildQueue(fullName, config));
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (SQLException e) {
                logger.error("Failed to purge queue", e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void getQueue(GetQueueRequest request, StreamObserver<Queue> responseObserver) {
            incrementRequestCount();
            try {
                String fullName = request.getName();
                String[] parts = CloudTasksStore.parseQueueName(fullName);

                CloudTasksStore.QueueConfig config = store.getQueueConfig(parts[0], parts[1], parts[2]);
                if (config == null) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Queue not found: " + fullName)
                            .asRuntimeException());
                    return;
                }

                responseObserver.onNext(buildQueue(fullName, config));
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (SQLException e) {
                logger.error("Failed to get queue", e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void listQueues(ListQueuesRequest request, StreamObserver<ListQueuesResponse> responseObserver) {
            incrementRequestCount();
            try {
                String parent = request.getParent();
                String[] locParts = CloudTasksStore.parseLocationName(parent);

                List<Map<String, Object>> queues = store.listQueues(locParts[0], locParts[1]);

                ListQueuesResponse.Builder builder = ListQueuesResponse.newBuilder();
                for (Map<String, Object> data : queues) {
                    String fullName = "projects/" + data.get("project_id") +
                                      "/locations/" + data.get("location_id") +
                                      "/queues/" + data.get("queue_id");
                    builder.addQueues(buildQueue(fullName, data));
                }

                responseObserver.onNext(builder.build());
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (SQLException e) {
                logger.error("Failed to list queues", e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void deleteQueue(DeleteQueueRequest request, StreamObserver<Empty> responseObserver) {
            incrementRequestCount();
            try {
                String fullName = request.getName();
                String[] parts = CloudTasksStore.parseQueueName(fullName);

                if (!store.deleteQueue(parts[0], parts[1], parts[2])) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Queue not found: " + fullName)
                            .asRuntimeException());
                    return;
                }

                // Clean up rate limiter for this queue
                dispatcher.removeRateLimiter(fullName);

                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (SQLException e) {
                logger.error("Failed to delete queue", e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void pauseQueue(PauseQueueRequest request, StreamObserver<Queue> responseObserver) {
            incrementRequestCount();
            try {
                String fullName = request.getName();
                String[] parts = CloudTasksStore.parseQueueName(fullName);

                if (!store.pauseQueue(parts[0], parts[1], parts[2])) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Queue not found: " + fullName)
                            .asRuntimeException());
                    return;
                }

                CloudTasksStore.QueueConfig config = store.getQueueConfig(parts[0], parts[1], parts[2]);
                responseObserver.onNext(buildQueue(fullName, config));
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (SQLException e) {
                logger.error("Failed to pause queue", e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void resumeQueue(ResumeQueueRequest request, StreamObserver<Queue> responseObserver) {
            incrementRequestCount();
            try {
                String fullName = request.getName();
                String[] parts = CloudTasksStore.parseQueueName(fullName);

                if (!store.resumeQueue(parts[0], parts[1], parts[2])) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Queue not found: " + fullName)
                            .asRuntimeException());
                    return;
                }

                CloudTasksStore.QueueConfig config = store.getQueueConfig(parts[0], parts[1], parts[2]);
                responseObserver.onNext(buildQueue(fullName, config));
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (SQLException e) {
                logger.error("Failed to resume queue", e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void createTask(CreateTaskRequest request, StreamObserver<Task> responseObserver) {
            incrementRequestCount();
            try {
                String parent = request.getParent(); // projects/{p}/locations/{l}/queues/{q}
                String[] qParts = CloudTasksStore.parseQueueName(parent);

                if (!store.queueExists(qParts[0], qParts[1], qParts[2])) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Queue not found: " + parent)
                            .asRuntimeException());
                    return;
                }

                Task taskProto = request.getTask();

                // Extract task ID from name if provided
                String taskId = null;
                if (taskProto.getName() != null && !taskProto.getName().isEmpty()) {
                    String[] taskParts = CloudTasksStore.parseTaskName(taskProto.getName());
                    taskId = taskParts[3];
                }

                // Extract HTTP target — fall back to queue-level target
                String httpMethod = "POST";
                String httpUrl = "";
                Map<String, String> httpHeaders = new HashMap<>();
                byte[] httpBody = null;

                if (taskProto.hasHttpRequest()) {
                    HttpRequest httpReq = taskProto.getHttpRequest();
                    httpUrl = httpReq.getUrl();
                    if (httpReq.getHttpMethod() != HttpMethod.HTTP_METHOD_UNSPECIFIED) {
                        httpMethod = httpReq.getHttpMethod().name();
                    }
                    httpHeaders.putAll(httpReq.getHeadersMap());
                    if (!httpReq.getBody().isEmpty()) {
                        httpBody = httpReq.getBody().toByteArray();
                    }
                }

                // Fall back to queue-level HTTP target
                if ((httpUrl == null || httpUrl.isEmpty())) {
                    String[] queueTarget = store.getQueueHttpTarget(parent);
                    if (queueTarget != null && queueTarget[0] != null && !queueTarget[0].isEmpty()) {
                        httpUrl = queueTarget[0];
                        if (queueTarget[1] != null && !queueTarget[1].isEmpty()) {
                            httpMethod = queueTarget[1];
                        }
                    } else {
                        responseObserver.onError(Status.INVALID_ARGUMENT
                                .withDescription("No URL specified in task or queue HTTP target")
                                .asRuntimeException());
                        return;
                    }
                }

                // Extract schedule time
                Instant scheduleTime = null;
                if (taskProto.hasScheduleTime()) {
                    scheduleTime = Instant.ofEpochSecond(
                            taskProto.getScheduleTime().getSeconds(),
                            taskProto.getScheduleTime().getNanos());
                }

                // Extract dispatch deadline
                Instant deadline = null;
                if (taskProto.hasDispatchDeadline()) {
                    deadline = Instant.ofEpochSecond(
                            taskProto.getDispatchDeadline().getSeconds(),
                            taskProto.getDispatchDeadline().getNanos());
                }

                CloudTasksStore.TaskEntry entry = store.createTask(
                        parent, taskId, httpMethod, httpUrl, httpHeaders, httpBody, scheduleTime, deadline);

                responseObserver.onNext(buildTask(parent, entry));
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            } catch (SQLException e) {
                logger.error("Failed to create task", e);
                responseObserver.onError(Status.INTERNAL
                        .withDescription("Database error: " + e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void getTask(GetTaskRequest request, StreamObserver<Task> responseObserver) {
            incrementRequestCount();
            try {
                String fullName = request.getName();
                String[] parts = CloudTasksStore.parseTaskName(fullName);
                String queueFullName = "projects/" + parts[0] + "/locations/" + parts[1] + "/queues/" + parts[2];

                CloudTasksStore.TaskEntry entry = store.getTask(queueFullName, parts[3]);
                if (entry == null) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Task not found: " + fullName)
                            .asRuntimeException());
                    return;
                }

                responseObserver.onNext(buildTask(queueFullName, entry));
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void listTasks(ListTasksRequest request, StreamObserver<ListTasksResponse> responseObserver) {
            incrementRequestCount();
            try {
                String parent = request.getParent();
                String[] parts = CloudTasksStore.parseQueueName(parent);

                List<CloudTasksStore.TaskEntry> tasks = store.listTasks(parent);

                ListTasksResponse.Builder builder = ListTasksResponse.newBuilder();
                for (CloudTasksStore.TaskEntry entry : tasks) {
                    builder.addTasks(buildTask(parent, entry));
                }

                responseObserver.onNext(builder.build());
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void deleteTask(DeleteTaskRequest request, StreamObserver<Empty> responseObserver) {
            incrementRequestCount();
            try {
                String fullName = request.getName();
                String[] parts = CloudTasksStore.parseTaskName(fullName);
                String queueFullName = "projects/" + parts[0] + "/locations/" + parts[1] + "/queues/" + parts[2];

                if (!store.deleteTask(queueFullName, parts[3])) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Task not found: " + fullName)
                            .asRuntimeException());
                    return;
                }

                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            }
        }

        @Override
        public void runTask(RunTaskRequest request, StreamObserver<Task> responseObserver) {
            incrementRequestCount();
            try {
                String fullName = request.getName();
                String[] parts = CloudTasksStore.parseTaskName(fullName);
                String queueFullName = "projects/" + parts[0] + "/locations/" + parts[1] + "/queues/" + parts[2];

                CloudTasksStore.TaskEntry entry = store.getTask(queueFullName, parts[3]);
                if (entry == null) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Task not found: " + fullName)
                            .asRuntimeException());
                    return;
                }

                // Force task to be dispatchable immediately
                entry.scheduleTime = Instant.now();
                entry.state = "PENDING";

                responseObserver.onNext(buildTask(queueFullName, entry));
                responseObserver.onCompleted();
            } catch (IllegalArgumentException e) {
                responseObserver.onError(Status.INVALID_ARGUMENT
                        .withDescription(e.getMessage())
                        .asRuntimeException());
            }
        }

        // --- Helpers ---

        private Queue buildQueue(String fullName, Map<String, Object> data) {
            CloudTasksStore.QueueConfig config = new CloudTasksStore.QueueConfig();
            config.state = (String) data.get("state");
            config.maxDispatchesPerSecond = getDouble(data, "max_dispatches_per_second", 500);
            config.maxConcurrentDispatches = getInt(data, "max_concurrent_dispatches", 1000);
            config.maxBurstSize = getInt(data, "max_burst_size", 0);
            config.maxAttempts = getInt(data, "max_attempts", 100);
            config.minBackoff = getString(data, "min_backoff", "0.100s");
            config.maxBackoff = getString(data, "max_backoff", "3600s");
            config.maxDoublings = getInt(data, "max_doublings", 16);
            config.maxRetryDuration = getString(data, "max_retry_duration", "0s");
            config.httpTargetUri = (String) data.get("http_target_uri");
            config.httpTargetMethod = (String) data.get("http_target_method");
            return buildQueue(fullName, config);
        }

        private Queue buildQueue(String fullName, CloudTasksStore.QueueConfig config) {
            Queue.Builder builder = Queue.newBuilder().setName(fullName);

            if (config == null) {
                return builder.build();
            }

            // State
            if (config.state != null) {
                builder.setState(mapQueueState(config.state));
            }

            // Rate limits
            builder.setRateLimits(RateLimits.newBuilder()
                    .setMaxDispatchesPerSecond(config.maxDispatchesPerSecond)
                    .setMaxBurstSize(config.maxBurstSize)
                    .setMaxConcurrentDispatches(config.maxConcurrentDispatches)
                    .build());

            // Retry config
            RetryConfig.Builder retryBuilder = RetryConfig.newBuilder()
                    .setMaxAttempts(config.maxAttempts)
                    .setMaxDoublings(config.maxDoublings);

            if (config.minBackoff != null && !config.minBackoff.isEmpty()) {
                retryBuilder.setMinBackoff(parseDuration(config.minBackoff));
            }
            if (config.maxBackoff != null && !config.maxBackoff.isEmpty()) {
                retryBuilder.setMaxBackoff(parseDuration(config.maxBackoff));
            }
            if (config.maxRetryDuration != null && !config.maxRetryDuration.isEmpty()
                    && !"0s".equals(config.maxRetryDuration)) {
                retryBuilder.setMaxRetryDuration(parseDuration(config.maxRetryDuration));
            }
            builder.setRetryConfig(retryBuilder.build());

            // HTTP target (queue-level) — stored in DB, used by dispatcher, but
            // not exposed via v2 Queue proto (only v2beta3 has httpTarget on Queue).
            // The REST API JSON response includes it for developer visibility.

            return builder.build();
        }

        private Task buildTask(String queueFullName, CloudTasksStore.TaskEntry entry) {
            String taskFullName = queueFullName + "/tasks/" + entry.taskId;

            Task.Builder builder = Task.newBuilder()
                    .setName(taskFullName)
                    .setDispatchCount(entry.dispatchCount)
                    .setResponseCount(entry.responseCount);

            // Schedule time
            if (entry.scheduleTime != null) {
                builder.setScheduleTime(Timestamp.newBuilder()
                        .setSeconds(entry.scheduleTime.getEpochSecond())
                        .setNanos(entry.scheduleTime.getNano())
                        .build());
            }

            // Create time
            if (entry.createTime != null) {
                builder.setCreateTime(Timestamp.newBuilder()
                        .setSeconds(entry.createTime.getEpochSecond())
                        .setNanos(entry.createTime.getNano())
                        .build());
            }

            // Dispatch deadline
            if (entry.dispatchDeadline != null) {
                builder.setDispatchDeadline(Duration.newBuilder()
                        .setSeconds(entry.dispatchDeadline.getEpochSecond() - entry.scheduleTime.getEpochSecond())
                        .build());
            }

            // First attempt
            if (entry.firstAttemptTime != null) {
                builder.setFirstAttempt(
                        com.google.cloud.tasks.v2.Attempt.newBuilder()
                                .setScheduleTime(Timestamp.newBuilder()
                                        .setSeconds(entry.firstAttemptTime.getEpochSecond())
                                        .setNanos(entry.firstAttemptTime.getNano())
                                        .build())
                                .setDispatchTime(Timestamp.newBuilder()
                                        .setSeconds(entry.firstAttemptTime.getEpochSecond())
                                        .setNanos(entry.firstAttemptTime.getNano())
                                        .build())
                                .build());
            }

            // Last attempt
            if (entry.lastAttemptTime != null) {
                builder.setLastAttempt(
                        com.google.cloud.tasks.v2.Attempt.newBuilder()
                                .setScheduleTime(Timestamp.newBuilder()
                                        .setSeconds(entry.lastAttemptTime.getEpochSecond())
                                        .setNanos(entry.lastAttemptTime.getNano())
                                        .build())
                                .setDispatchTime(Timestamp.newBuilder()
                                        .setSeconds(entry.lastAttemptTime.getEpochSecond())
                                        .setNanos(entry.lastAttemptTime.getNano())
                                        .build())
                                .build());
            }

            // HTTP request details
            if (entry.httpUrl != null && !entry.httpUrl.isEmpty()) {
                HttpRequest.Builder httpBuilder = HttpRequest.newBuilder()
                        .setUrl(entry.httpUrl);

                if (entry.httpMethod != null) {
                    try {
                        httpBuilder.setHttpMethod(HttpMethod.valueOf(entry.httpMethod));
                    } catch (IllegalArgumentException e) {
                        httpBuilder.setHttpMethod(HttpMethod.POST);
                    }
                }

                if (entry.httpHeaders != null) {
                    httpBuilder.putAllHeaders(entry.httpHeaders);
                }

                if (entry.httpBody != null) {
                    httpBuilder.setBody(ByteString.copyFrom(entry.httpBody));
                }

                builder.setHttpRequest(httpBuilder.build());
            }

            return builder.build();
        }

        private Queue.State mapQueueState(String state) {
            if (state == null) return Queue.State.STATE_UNSPECIFIED;
            return switch (state) {
                case "RUNNING" -> Queue.State.RUNNING;
                case "PAUSED" -> Queue.State.PAUSED;
                case "DISABLED" -> Queue.State.DISABLED;
                default -> Queue.State.STATE_UNSPECIFIED;
            };
        }

        private Duration parseDuration(String s) {
            if (s == null || s.isEmpty() || "0s".equals(s)) {
                return Duration.getDefaultInstance();
            }
            try {
                // Parse "X.XXXs" format
                String numericPart = s.replace("s", "");
                if (numericPart.contains(".")) {
                    String[] parts = numericPart.split("\\.");
                    long seconds = Long.parseLong(parts[0]);
                    int nanos = Integer.parseInt(parts[1].length() == 1
                            ? parts[1] + "00000000"
                            : parts[1].length() < 9
                                ? parts[1] + "0".repeat(9 - parts[1].length())
                                : parts[1].substring(0, 9));
                    return Duration.newBuilder().setSeconds(seconds).setNanos(nanos).build();
                } else {
                    long seconds = Long.parseLong(numericPart);
                    return Duration.newBuilder().setSeconds(seconds).build();
                }
            } catch (NumberFormatException e) {
                // Return default empty duration
                return Duration.getDefaultInstance();
            }
        }

        private CloudTasksStore.QueueConfig protoToQueueConfig(Queue queueProto) {
            CloudTasksStore.QueueConfig config = new CloudTasksStore.QueueConfig();

            if (queueProto.hasRateLimits()) {
                RateLimits rl = queueProto.getRateLimits();
                config.maxDispatchesPerSecond = rl.getMaxDispatchesPerSecond();
                config.maxConcurrentDispatches = rl.getMaxConcurrentDispatches();
                config.maxBurstSize = rl.getMaxBurstSize();
            }

            if (queueProto.hasRetryConfig()) {
                RetryConfig rc = queueProto.getRetryConfig();
                config.maxAttempts = rc.getMaxAttempts();
                config.maxDoublings = rc.getMaxDoublings();
                if (rc.hasMinBackoff()) {
                    config.minBackoff = durationToString(rc.getMinBackoff());
                }
                if (rc.hasMaxBackoff()) {
                    config.maxBackoff = durationToString(rc.getMaxBackoff());
                }
                if (rc.hasMaxRetryDuration()) {
                    config.maxRetryDuration = durationToString(rc.getMaxRetryDuration());
                }
            }

            // HTTP target is not a proto field on Queue in v2 (only v2beta3).
            // The REST API handles httpTarget parsing for queue-level defaults.

            return config;
        }

        private String durationToString(Duration d) {
            long totalNanos = d.getSeconds() * 1_000_000_000L + d.getNanos();
            double seconds = totalNanos / 1_000_000_000.0;
            if (seconds == (long) seconds) {
                return (long) seconds + "s";
            }
            return seconds + "s";
        }

        private String getString(Map<String, Object> data, String key, String defaultValue) {
            Object val = data.get(key);
            return val != null ? String.valueOf(val) : defaultValue;
        }

        private int getInt(Map<String, Object> data, String key, int defaultValue) {
            Object val = data.get(key);
            if (val instanceof Number) return ((Number) val).intValue();
            return defaultValue;
        }

        private double getDouble(Map<String, Object> data, String key, double defaultValue) {
            Object val = data.get(key);
            if (val instanceof Number) return ((Number) val).doubleValue();
            return defaultValue;
        }

        // ── IAM Policy gRPC methods ────────────────────────────────────────────

        @Override
        public void getIamPolicy(GetIamPolicyRequest request, StreamObserver<Policy> responseObserver) {
            incrementRequestCount();
            iamHelper.getIamPolicy(request, responseObserver);
        }

        @Override
        public void setIamPolicy(SetIamPolicyRequest request, StreamObserver<Policy> responseObserver) {
            incrementRequestCount();
            iamHelper.setIamPolicy(request, responseObserver);
        }

        @Override
        public void testIamPermissions(TestIamPermissionsRequest request,
                                       StreamObserver<TestIamPermissionsResponse> responseObserver) {
            incrementRequestCount();
            iamHelper.testIamPermissions(request, responseObserver);
        }
    }
}
