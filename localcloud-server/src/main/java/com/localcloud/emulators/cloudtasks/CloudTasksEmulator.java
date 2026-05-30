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
import com.google.cloud.tasks.v2.Queue;
import com.google.cloud.tasks.v2.ResumeQueueRequest;
import com.google.cloud.tasks.v2.RunTaskRequest;
import com.google.cloud.tasks.v2.Task;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.Policy;
import com.google.iam.v1.SetIamPolicyRequest;
import com.google.iam.v1.TestIamPermissionsRequest;
import com.google.iam.v1.TestIamPermissionsResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.emulators.iam.IAMPolicyGrpcHelper;
import com.localcloud.emulators.iam.IAMRepository;
import com.localcloud.persistence.PostgresDataSource;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * Cloud Tasks gRPC emulator.
 * Implements the CloudTasks gRPC API with PostgreSQL-backed queues and in-memory tasks.
 */
public class CloudTasksEmulator extends AbstractEmulator {

    private final CloudTasksStore store;
    private final TaskDispatcher dispatcher;
    private final CloudTasksServiceImpl serviceImpl;
    private final IAMPolicyGrpcHelper iamHelper;

    public CloudTasksEmulator(PostgresDataSource dataSource) {
        super("cloudtasks", "Cloud Tasks", 8080, "grpc", "CLOUD_TASKS_EMULATOR_HOST");
        this.store = new CloudTasksStore(dataSource);
        this.dispatcher = new TaskDispatcher(store);
        this.iamHelper = new IAMPolicyGrpcHelper(new IAMRepository(dataSource));
        this.serviceImpl = new CloudTasksServiceImpl();
    }

    @Override
    protected void doStart() throws Exception {
        dispatcher.start();
        logger.info("Cloud Tasks emulator gRPC services ready");
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

                // Extract queue ID from full name or generate
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

                store.createQueue(locParts[0], locParts[1], queueId);

                String fullName = parent + "/queues/" + queueId;
                Queue response = Queue.newBuilder()
                        .setName(fullName)
                        .setState(Queue.State.RUNNING)
                        .build();

                responseObserver.onNext(response);
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
        public void getQueue(GetQueueRequest request, StreamObserver<Queue> responseObserver) {
            incrementRequestCount();
            try {
                String fullName = request.getName();
                String[] parts = CloudTasksStore.parseQueueName(fullName);

                Map<String, Object> data = store.getQueue(parts[0], parts[1], parts[2]);
                if (data == null) {
                    responseObserver.onError(Status.NOT_FOUND
                            .withDescription("Queue not found: " + fullName)
                            .asRuntimeException());
                    return;
                }

                responseObserver.onNext(buildQueue(fullName, data));
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

                Map<String, Object> data = store.getQueue(parts[0], parts[1], parts[2]);
                responseObserver.onNext(buildQueue(fullName, data));
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

                Map<String, Object> data = store.getQueue(parts[0], parts[1], parts[2]);
                responseObserver.onNext(buildQueue(fullName, data));
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

                // Extract HTTP target
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

                // Extract schedule time
                Instant scheduleTime = null;
                if (taskProto.hasScheduleTime()) {
                    scheduleTime = Instant.ofEpochSecond(
                            taskProto.getScheduleTime().getSeconds(),
                            taskProto.getScheduleTime().getNanos());
                }

                CloudTasksStore.TaskEntry entry = store.createTask(
                        parent, taskId, httpMethod, httpUrl, httpHeaders, httpBody, scheduleTime);

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
            Queue.Builder builder = Queue.newBuilder()
                    .setName(fullName);

            String state = (String) data.get("state");
            if (state != null) {
                builder.setState(mapQueueState(state));
            }

            return builder.build();
        }

        private Task buildTask(String queueFullName, CloudTasksStore.TaskEntry entry) {
            String taskFullName = queueFullName + "/tasks/" + entry.taskId;

            Task.Builder builder = Task.newBuilder()
                    .setName(taskFullName)
                    .setDispatchCount(entry.dispatchCount)
                    .setResponseCount(entry.responseCount);

            // Set schedule time
            if (entry.scheduleTime != null) {
                builder.setScheduleTime(Timestamp.newBuilder()
                        .setSeconds(entry.scheduleTime.getEpochSecond())
                        .setNanos(entry.scheduleTime.getNano())
                        .build());
            }

            // Set create time
            if (entry.createTime != null) {
                builder.setCreateTime(Timestamp.newBuilder()
                        .setSeconds(entry.createTime.getEpochSecond())
                        .setNanos(entry.createTime.getNano())
                        .build());
            }

            // Set HTTP request details
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
