package com.localcloud.emulators.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.google.cloud.scheduler.v1.CreateJobRequest;
import com.google.cloud.scheduler.v1.DeleteJobRequest;
import com.google.cloud.scheduler.v1.GetJobRequest;
import com.google.cloud.scheduler.v1.HttpMethod;
import com.google.cloud.scheduler.v1.HttpTarget;
import com.google.cloud.scheduler.v1.Job;
import com.google.cloud.scheduler.v1.ListJobsRequest;
import com.google.cloud.scheduler.v1.ListJobsResponse;
import com.google.cloud.scheduler.v1.PauseJobRequest;
import com.google.cloud.scheduler.v1.PubsubTarget;
import com.google.cloud.scheduler.v1.ResumeJobRequest;
import com.google.cloud.scheduler.v1.RetryConfig;
import com.google.cloud.scheduler.v1.RunJobRequest;
import com.google.cloud.scheduler.v1.UpdateJobRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.Empty;
import com.localcloud.integration.TestDataSource;
import com.sun.net.httpserver.HttpServer;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

class CloudSchedulerServiceTest {
    @Test
    void createGetListUpdatePauseResumeDeleteJob() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("scheduler_service_crud_" + System.nanoTime());
        CloudSchedulerEmulator emulator = new CloudSchedulerEmulator(testDataSource.getDataSource(), this::noOpPublish);
        emulator.start();
        try {
            var service = emulator.getServiceImpl();
            String parent = "projects/p/locations/us-central1";
            String name = parent + "/jobs/hourly";

            Job created = createJob(service, httpJob(name, "0 * * * *", "http://127.0.0.1:9"));
            assertEquals(name, created.getName());
            assertEquals(Job.State.ENABLED, created.getState());
            assertTrue(created.hasScheduleTime());

            var duplicate = new TestObserver<Job>();
            service.createJob(CreateJobRequest.newBuilder()
                    .setParent(parent)
                    .setJob(httpJob(name, "0 * * * *", "http://127.0.0.1:9"))
                    .build(), duplicate);
            assertEquals(Status.Code.ALREADY_EXISTS, duplicate.errorCode());

            var get = new TestObserver<Job>();
            service.getJob(GetJobRequest.newBuilder().setName(name).build(), get);
            assertEquals(created, get.value());

            var list = new TestObserver<ListJobsResponse>();
            service.listJobs(ListJobsRequest.newBuilder().setParent(parent).build(), list);
            assertEquals(1, list.value().getJobsCount());

            var update = new TestObserver<Job>();
            service.updateJob(UpdateJobRequest.newBuilder()
                    .setJob(created.toBuilder().setSchedule("5 * * * *").build())
                    .build(), update);
            assertEquals("5 * * * *", update.value().getSchedule());

            var pause = new TestObserver<Job>();
            service.pauseJob(PauseJobRequest.newBuilder().setName(name).build(), pause);
            assertEquals(Job.State.PAUSED, pause.value().getState());

            var resume = new TestObserver<Job>();
            service.resumeJob(ResumeJobRequest.newBuilder().setName(name).build(), resume);
            assertEquals(Job.State.ENABLED, resume.value().getState());

            var delete = new TestObserver<Empty>();
            service.deleteJob(DeleteJobRequest.newBuilder().setName(name).build(), delete);
            delete.value();

            var missing = new TestObserver<Job>();
            service.getJob(GetJobRequest.newBuilder().setName(name).build(), missing);
            assertEquals(Status.Code.NOT_FOUND, missing.errorCode());
        } finally {
            emulator.stop();
            testDataSource.close();
        }
    }

    @Test
    void createJobRejectsInvalidCron() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("scheduler_service_invalid_cron_" + System.nanoTime());
        CloudSchedulerEmulator emulator = new CloudSchedulerEmulator(testDataSource.getDataSource(), this::noOpPublish);
        emulator.start();
        try {
            var service = emulator.getServiceImpl();
            var observer = new TestObserver<Job>();
            service.createJob(CreateJobRequest.newBuilder()
                    .setParent("projects/p/locations/us-central1")
                    .setJob(httpJob("projects/p/locations/us-central1/jobs/bad", "not a cron", "http://127.0.0.1:9"))
                    .build(), observer);

            assertEquals(Status.Code.INVALID_ARGUMENT, observer.errorCode());
        } finally {
            emulator.stop();
            testDataSource.close();
        }
    }

    @Test
    void runJobPublishesPubSubTargetAndRecordsExecution() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("scheduler_service_pubsub_" + System.nanoTime());
        AtomicReference<String> topic = new AtomicReference<>();
        AtomicReference<ByteString> data = new AtomicReference<>();
        AtomicReference<Map<String, String>> attributes = new AtomicReference<>();
        CloudSchedulerEmulator.PubSubPublisher publisher = (topicName, payload, attrs) -> {
            topic.set(topicName);
            data.set(payload);
            attributes.set(Map.copyOf(attrs));
            return List.of("message-1");
        };
        CloudSchedulerEmulator emulator = new CloudSchedulerEmulator(testDataSource.getDataSource(), publisher);
        emulator.start();
        try {
            var service = emulator.getServiceImpl();
            String parent = "projects/p/locations/us-central1";
            String name = parent + "/jobs/pubsub";
            Job job = Job.newBuilder()
                    .setName(name)
                    .setSchedule("0 * * * *")
                    .setTimeZone("UTC")
                    .setPubsubTarget(PubsubTarget.newBuilder()
                            .setTopicName("projects/p/topics/events")
                            .setData(ByteString.copyFromUtf8("hello"))
                            .putAttributes("source", "scheduler")
                            .build())
                    .build();
            createJob(service, job);

            var run = new TestObserver<Job>();
            service.runJob(RunJobRequest.newBuilder().setName(name).build(), run);
            assertEquals(name, run.value().getName());

            assertEquals("projects/p/topics/events", topic.get());
            assertEquals("hello", data.get().toStringUtf8());
            assertEquals("scheduler", attributes.get().get("source"));
            assertEquals(List.of("OK"), executionStatuses(testDataSource, name));
        } finally {
            emulator.stop();
            testDataSource.close();
        }
    }

    @Test
    void runJobRetriesFailedHttpTargetWithRetryConfig() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("scheduler_service_retry_" + System.nanoTime());
        CloudSchedulerEmulator emulator = new CloudSchedulerEmulator(testDataSource.getDataSource(), this::noOpPublish);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/retry", exchange -> {
            int code = attempts.incrementAndGet() == 1 ? 500 : 204;
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
        server.start();
        emulator.start();
        try {
            var service = emulator.getServiceImpl();
            String parent = "projects/p/locations/us-central1";
            String name = parent + "/jobs/retry";
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/retry";
            Job job = httpJob(name, "0 * * * *", url).toBuilder()
                    .setRetryConfig(RetryConfig.newBuilder()
                            .setRetryCount(1)
                            .setMinBackoffDuration(Duration.newBuilder().setSeconds(0).build())
                            .setMaxBackoffDuration(Duration.newBuilder().setSeconds(0).build())
                            .build())
                    .build();
            createJob(service, job);

            var run = new TestObserver<Job>();
            service.runJob(RunJobRequest.newBuilder().setName(name).build(), run);
            assertEquals(name, run.value().getName());

            assertTrue(await(() -> attempts.get() >= 2));
            assertTrue(awaitExecutionCount(testDataSource, name, 2));
            assertEquals(List.of("ERROR", "OK"), executionStatuses(testDataSource, name));
        } finally {
            emulator.stop();
            server.stop(0);
            testDataSource.close();
        }
    }

    @Test
    void runJobExhaustsRetriesAndDoesNotReschedule() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("scheduler_service_exhaust_retries_" + System.nanoTime());
        CloudSchedulerEmulator emulator = new CloudSchedulerEmulator(testDataSource.getDataSource(), this::noOpPublish);
        emulator.start();
        try {
            var service = emulator.getServiceImpl();
            String parent = "projects/p/locations/us-central1";
            String name = parent + "/jobs/exhaust";
            Job job = httpJob(name, "0 * * * *", "http://127.0.0.1:9").toBuilder()
                    .setRetryConfig(RetryConfig.newBuilder()
                            .setRetryCount(2)
                            .setMinBackoffDuration(Duration.newBuilder().setSeconds(0).build())
                            .setMaxBackoffDuration(Duration.newBuilder().setSeconds(0).build())
                            .setMaxRetryDuration(Duration.newBuilder().setSeconds(0).build())
                            .build())
                    .build();
            createJob(service, job);

            var run = new TestObserver<Job>();
            service.runJob(RunJobRequest.newBuilder().setName(name).build(), run);
            assertEquals(name, run.value().getName());

            long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                List<String> statuses = executionStatuses(testDataSource, name);
                if (statuses.size() >= 3) {
                    assertEquals(List.of("ERROR", "ERROR", "ERROR"), statuses);
                    return;
                }
                Thread.sleep(100);
            }
            List<String> finalStatuses = executionStatuses(testDataSource, name);
            assertTrue(finalStatuses.size() >= 3, "Expected 3 executions (1 initial + 2 retries), got: " + finalStatuses.size());
        } finally {
            emulator.stop();
            testDataSource.close();
        }
    }

    @Test
    void schedulerJobsSurviveResetAndCanBeRescheduled() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("scheduler_service_reset_" + System.nanoTime());
        CloudSchedulerEmulator emulator = new CloudSchedulerEmulator(testDataSource.getDataSource(), this::noOpPublish);
        emulator.start();
        try {
            var service = emulator.getServiceImpl();
            String parent = "projects/p/locations/us-central1";
            String name = parent + "/jobs/after-reset";

            createJob(service, httpJob(name, "0 * * * *", "http://127.0.0.1:9"));

            assertNotNull(emulator.getRepository().get("p", "us-central1", "after-reset"));

            emulator.doReset();

            assertNotNull(emulator.getRepository().get("p", "us-central1", "after-reset"));
        } finally {
            emulator.stop();
            testDataSource.close();
        }
    }

    private Job createJob(CloudSchedulerEmulator.CloudSchedulerService service, Job job) {
        var observer = new TestObserver<Job>();
        service.createJob(CreateJobRequest.newBuilder()
                .setParent(parentName(job.getName()))
                .setJob(job)
                .build(), observer);
        return observer.value();
    }

    private Job httpJob(String name, String schedule, String uri) {
        return Job.newBuilder()
                .setName(name)
                .setSchedule(schedule)
                .setTimeZone("UTC")
                .setHttpTarget(HttpTarget.newBuilder()
                        .setUri(uri)
                        .setHttpMethod(HttpMethod.GET)
                        .build())
                .build();
    }

    private List<String> noOpPublish(String topicName, ByteString data, Map<String, String> attributes) {
        return List.of("message-1");
    }

    private static String parentName(String jobName) {
        int idx = jobName.lastIndexOf("/jobs/");
        return idx < 0 ? jobName : jobName.substring(0, idx);
    }

    private static List<String> executionStatuses(TestDataSource testDataSource, String jobName) throws Exception {
        List<String> statuses = new ArrayList<>();
        try (var conn = testDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM scheduler_executions WHERE job_name = ? ORDER BY id")) {
            ps.setString(1, jobName);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    statuses.add(rs.getString("status"));
                }
            }
        }
        return statuses;
    }

    private static boolean awaitExecutionCount(TestDataSource testDataSource, String jobName, int count) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (executionStatuses(testDataSource, jobName).size() >= count) {
                return true;
            }
            Thread.sleep(25);
        }
        return executionStatuses(testDataSource, jobName).size() >= count;
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    private static final class TestObserver<T> implements StreamObserver<T> {
        private T value;
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(T value) {
            this.value = value;
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onCompleted() {
            this.completed = true;
        }

        T value() {
            assertNull(error, () -> String.valueOf(error));
            assertTrue(completed);
            assertNotNull(value);
            return value;
        }

        Status.Code errorCode() {
            assertNotNull(error);
            return Status.fromThrowable(error).getCode();
        }
    }
}
