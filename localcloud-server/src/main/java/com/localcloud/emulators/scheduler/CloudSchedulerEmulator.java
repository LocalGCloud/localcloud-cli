package com.localcloud.emulators.scheduler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.google.cloud.scheduler.v1.CloudSchedulerGrpc;
import com.google.cloud.scheduler.v1.CreateJobRequest;
import com.google.cloud.scheduler.v1.DeleteJobRequest;
import com.google.cloud.scheduler.v1.GetJobRequest;
import com.google.cloud.scheduler.v1.Job;
import com.google.cloud.scheduler.v1.ListJobsRequest;
import com.google.cloud.scheduler.v1.ListJobsResponse;
import com.google.cloud.scheduler.v1.PauseJobRequest;
import com.google.cloud.scheduler.v1.ResumeJobRequest;
import com.google.cloud.scheduler.v1.RunJobRequest;
import com.google.cloud.scheduler.v1.UpdateJobRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.emulators.common.GrpcSupport;
import com.localcloud.persistence.PostgresDataSource;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public class CloudSchedulerEmulator extends AbstractEmulator {
    @FunctionalInterface
    interface PubSubPublisher {
        List<String> publish(String topicName, ByteString data, Map<String, String> attributes) throws Exception;
    }

    private final SchedulerRepository repository;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, ScheduledFuture<?>> scheduledJobs = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final PubSubPublisher pubSubPublisher;
    private final CloudSchedulerService service = new CloudSchedulerService();
    private final CronParser cronParser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    public CloudSchedulerEmulator(PostgresDataSource dataSource) {
        this(dataSource, new GrpcPubSubPublisher());
    }

    CloudSchedulerEmulator(PostgresDataSource dataSource, PubSubPublisher pubSubPublisher) {
        super("cloudscheduler", "Cloud Scheduler", 8080, "grpc", "CLOUD_SCHEDULER_EMULATOR_HOST");
        this.repository = new SchedulerRepository(dataSource);
        this.pubSubPublisher = pubSubPublisher;
    }

    public CloudSchedulerService getServiceImpl() {
        return service;
    }

    public SchedulerRepository getRepository() {
        return repository;
    }

    @Override protected void doStart() {
        logger.info("Cloud Scheduler emulator initialized");
    }

    @Override protected void doStop() {
        scheduledJobs.values().forEach(f -> f.cancel(false));
        scheduledJobs.clear();
        scheduler.shutdownNow();
    }

    @Override protected void doReset() {
        scheduledJobs.values().forEach(f -> f.cancel(false));
        scheduledJobs.clear();
    }

    private Instant nextExecution(String schedule, String timeZone) {
        Cron cron = cronParser.parse(schedule);
        cron.validate();
        ZoneId zone = ZoneId.of(timeZone == null || timeZone.isBlank() ? "UTC" : timeZone);
        return ExecutionTime.forCron(cron).nextExecution(ZonedDateTime.now(zone))
                .orElseThrow(() -> new IllegalArgumentException("Cron has no next execution"))
                .toInstant();
    }

    private void schedule(Job job) {
        if (job.getState() != Job.State.ENABLED) return;
        Instant next = nextExecution(job.getSchedule(), job.getTimeZone());
        long delayMs = Math.max(0, next.toEpochMilli() - Instant.now().toEpochMilli());
        scheduledJobs.compute(job.getName(), (name, old) -> {
            if (old != null) old.cancel(false);
            return scheduler.schedule(() -> {
                execute(job);
                try {
                    String[] nameParts = name.split("/");
                    if (nameParts.length == 6) {
                        Job refreshed = repository.get(nameParts[1], nameParts[3], nameParts[5]);
                        if (refreshed != null && refreshed.getState() == Job.State.ENABLED) schedule(refreshed);
                    } else {
                        logger.warn("Job {} has unexpected name format, cannot reschedule", name);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to reschedule {}: {}", name, e.getMessage());
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        });
    }

    private void execute(Job job) {
        executeWithRetry(job, 0, Instant.now());
    }

    private void executeWithRetry(Job job, int attempt, Instant startInstant) {
        String status = "OK";
        String output = "App Engine targets are stored but not executed";
        boolean failed = false;
        try {
            switch (job.getTargetCase()) {
                case HTTP_TARGET -> {
                    var target = job.getHttpTarget();
                    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target.getUri()));
                    for (var header : target.getHeadersMap().entrySet()) {
                        request.header(header.getKey(), header.getValue());
                    }
                    byte[] body = target.getBody().toByteArray();
                    request.method(target.getHttpMethod().name(),
                            body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
                    HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
                    status = response.statusCode() >= 200 && response.statusCode() < 300 ? "OK" : "ERROR";
                    output = "HTTP " + response.statusCode();
                    if (!"OK".equals(status)) {
                        failed = true;
                    }
                }
                case PUBSUB_TARGET -> {
                    var target = job.getPubsubTarget();
                    List<String> messageIds = pubSubPublisher.publish(
                            target.getTopicName(), target.getData(), target.getAttributesMap());
                    status = "OK";
                    output = "Published to Pub/Sub topic " + target.getTopicName()
                            + " (" + target.getData().size() + " bytes, "
                            + messageIds.size() + " message)";
                }
                case APP_ENGINE_HTTP_TARGET -> {
                    logger.info("App Engine HTTP target for job {} is validated but not executed locally", job.getName());
                    output = "App Engine target validated only (not executed - use Functions Framework for local execution)";
                }
                default -> throw new IllegalArgumentException("Job has no executable target");
            }
        } catch (Exception e) {
            status = "ERROR";
            output = e.getMessage();
            failed = true;
        }
        try {
            repository.recordExecution(job.getName(), status, output);
        } catch (SQLException e) {
            logger.warn("Failed to record Scheduler execution: {}", e.getMessage());
        }

        if (failed && job.hasRetryConfig()) {
            var retryConfig = job.getRetryConfig();
            int maxRetryAttempts = retryConfig.getRetryCount();
            if (maxRetryAttempts > 0 && attempt < maxRetryAttempts) {
                long minBackoffSecs = retryConfig.hasMinBackoffDuration() ? retryConfig.getMinBackoffDuration().getSeconds() : 5;
                long maxBackoffSecs = retryConfig.hasMaxBackoffDuration() ? retryConfig.getMaxBackoffDuration().getSeconds() : 3600;
                int maxDoublings = retryConfig.getMaxDoublings() > 0 ? retryConfig.getMaxDoublings() : 16;
                long maxRetryDurationSecs = retryConfig.hasMaxRetryDuration() ? retryConfig.getMaxRetryDuration().getSeconds() : 0;

                long elapsedSecs = Instant.now().getEpochSecond() - startInstant.getEpochSecond();
                if (maxRetryDurationSecs > 0 && elapsedSecs >= maxRetryDurationSecs) {
                    logger.info("Scheduler job {} retry exceeded max retry duration ({}s)", job.getName(), maxRetryDurationSecs);
                    return;
                }

                int doublings = Math.min(attempt, maxDoublings);
                long backoffSecs = minBackoffSecs * (long) Math.pow(2, doublings);
                backoffSecs = Math.min(backoffSecs, maxBackoffSecs);

                logger.info("Scheduling retry attempt {} for job {} in {}s", attempt + 1, job.getName(), backoffSecs);
                scheduler.schedule(() -> executeWithRetry(job, attempt + 1, startInstant), backoffSecs, TimeUnit.SECONDS);
            }
        }
    }

    private static final class GrpcPubSubPublisher implements PubSubPublisher {
        @Override
        public List<String> publish(String topicName, ByteString data, Map<String, String> attributes) {
            String pubsubHostEnv = System.getenv("PUBSUB_EMULATOR_HOST");
            String host = "localhost";
            int port = 8085;
            if (pubsubHostEnv != null && !pubsubHostEnv.isEmpty()) {
                String[] parts = pubsubHostEnv.split(":", 2);
                host = parts[0];
                if (parts.length > 1) {
                    try {
                        port = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        // Keep the default Pub/Sub emulator port when the env var is malformed.
                    }
                }
            }
            io.grpc.ManagedChannel channel = io.grpc.ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .build();
            try {
                com.google.pubsub.v1.PublisherGrpc.PublisherBlockingStub stub =
                        com.google.pubsub.v1.PublisherGrpc.newBlockingStub(channel);
                com.google.pubsub.v1.PubsubMessage pubsubMessage = com.google.pubsub.v1.PubsubMessage.newBuilder()
                        .setData(data)
                        .putAllAttributes(attributes)
                        .build();
                com.google.pubsub.v1.PublishRequest publishRequest = com.google.pubsub.v1.PublishRequest.newBuilder()
                        .setTopic(topicName)
                        .addMessages(pubsubMessage)
                        .build();
                return stub.publish(publishRequest).getMessageIdsList();
            } finally {
                channel.shutdownNow();
            }
        }
    }

    public class CloudSchedulerService extends CloudSchedulerGrpc.CloudSchedulerImplBase {
        @Override public void createJob(CreateJobRequest request, StreamObserver<Job> responseObserver) {
            incrementRequestCount();
            try {
                String[] parent = GrpcSupport.parseLocationParent(request.getParent());
                Job input = request.getJob();
                String jobId = input.getName().isBlank() ? "" : GrpcSupport.parseNamedResource(input.getName(), "jobs")[2];
                if (jobId.isBlank()) throw new IllegalArgumentException("job.name is required");
                if (repository.exists(parent[0], parent[1], jobId)) throw Status.ALREADY_EXISTS.asRuntimeException();
                Instant next = nextExecution(input.getSchedule(), input.getTimeZone());
                Job job = input.toBuilder()
                        .setName(request.getParent() + "/jobs/" + jobId)
                        .setState(Job.State.ENABLED)
                        .setScheduleTime(GrpcSupport.timestamp(next))
                        .build();
                repository.create(parent[0], parent[1], jobId, job, next);
                schedule(job);
                responseObserver.onNext(job);
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void getJob(GetJobRequest request, StreamObserver<Job> responseObserver) {
            incrementRequestCount();
            try {
                String[] parts = GrpcSupport.parseNamedResource(request.getName(), "jobs");
                Job job = repository.get(parts[0], parts[1], parts[2]);
                if (job == null) throw Status.NOT_FOUND.asRuntimeException();
                responseObserver.onNext(job);
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void listJobs(ListJobsRequest request, StreamObserver<ListJobsResponse> responseObserver) {
            incrementRequestCount();
            try {
                String[] parent = GrpcSupport.parseLocationParent(request.getParent());
                responseObserver.onNext(ListJobsResponse.newBuilder().addAllJobs(repository.list(parent[0], parent[1])).build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void updateJob(UpdateJobRequest request, StreamObserver<Job> responseObserver) {
            incrementRequestCount();
            try {
                Job input = request.getJob();
                String[] parts = GrpcSupport.parseNamedResource(input.getName(), "jobs");
                if (repository.get(parts[0], parts[1], parts[2]) == null) throw Status.NOT_FOUND.asRuntimeException();
                Instant next = input.getState() == Job.State.PAUSED ? null : nextExecution(input.getSchedule(), input.getTimeZone());
                Job job = input.toBuilder().setScheduleTime(next == null ? com.google.protobuf.Timestamp.getDefaultInstance() : GrpcSupport.timestamp(next)).build();
                repository.update(parts[0], parts[1], parts[2], job, next);
                schedule(job);
                responseObserver.onNext(job);
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void deleteJob(DeleteJobRequest request, StreamObserver<Empty> responseObserver) {
            incrementRequestCount();
            try {
                String[] parts = GrpcSupport.parseNamedResource(request.getName(), "jobs");
                if (!repository.delete(parts[0], parts[1], parts[2])) throw Status.NOT_FOUND.asRuntimeException();
                ScheduledFuture<?> future = scheduledJobs.remove(request.getName());
                if (future != null) future.cancel(false);
                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override public void pauseJob(PauseJobRequest request, StreamObserver<Job> responseObserver) {
            setState(request.getName(), Job.State.PAUSED, responseObserver);
        }

        @Override public void resumeJob(ResumeJobRequest request, StreamObserver<Job> responseObserver) {
            setState(request.getName(), Job.State.ENABLED, responseObserver);
        }

        @Override public void runJob(RunJobRequest request, StreamObserver<Job> responseObserver) {
            incrementRequestCount();
            try {
                String[] parts = GrpcSupport.parseNamedResource(request.getName(), "jobs");
                Job job = repository.get(parts[0], parts[1], parts[2]);
                if (job == null) throw Status.NOT_FOUND.asRuntimeException();
                execute(job);
                responseObserver.onNext(job);
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        private void setState(String name, Job.State state, StreamObserver<Job> responseObserver) {
            incrementRequestCount();
            try {
                String[] parts = GrpcSupport.parseNamedResource(name, "jobs");
                Job current = repository.get(parts[0], parts[1], parts[2]);
                if (current == null) throw Status.NOT_FOUND.asRuntimeException();
                Instant next = state == Job.State.ENABLED ? nextExecution(current.getSchedule(), current.getTimeZone()) : null;
                Job updated = current.toBuilder()
                        .setState(state)
                        .setScheduleTime(next == null ? com.google.protobuf.Timestamp.getDefaultInstance() : GrpcSupport.timestamp(next))
                        .build();
                repository.update(parts[0], parts[1], parts[2], updated, next);
                ScheduledFuture<?> future = scheduledJobs.remove(name);
                if (future != null) future.cancel(false);
                if (state == Job.State.ENABLED) schedule(updated);
                responseObserver.onNext(updated);
                responseObserver.onCompleted();
            } catch (io.grpc.StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            }
        }
    }
}
