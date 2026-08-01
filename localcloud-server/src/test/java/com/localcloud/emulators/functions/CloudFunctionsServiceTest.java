package com.localcloud.emulators.functions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.cloud.functions.v2.BuildConfig;
import com.google.cloud.functions.v2.CreateFunctionRequest;
import com.google.cloud.functions.v2.DeleteFunctionRequest;
import com.google.cloud.functions.v2.EventTrigger;
import com.google.cloud.functions.v2.Function;
import com.google.cloud.functions.v2.GenerateUploadUrlRequest;
import com.google.cloud.functions.v2.GenerateUploadUrlResponse;
import com.google.cloud.functions.v2.GetFunctionRequest;
import com.google.cloud.functions.v2.ListFunctionsRequest;
import com.google.cloud.functions.v2.ListFunctionsResponse;
import com.google.cloud.functions.v2.ServiceConfig;
import com.google.cloud.functions.v2.UpdateFunctionRequest;
import com.google.longrunning.Operation;
import com.localcloud.emulators.cloudrun.CloudRunStore;
import com.localcloud.integration.TestDataSource;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

class CloudFunctionsServiceTest {
    @Test
    void createGetListUpdateDeleteAndGenerateUploadUrl() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("functions_service_" + System.nanoTime());
        CloudFunctionsEmulator emulator = new CloudFunctionsEmulator(testDataSource.getDataSource());
        emulator.start();
        try {
            var service = emulator.getServiceImpl();
            String parent = "projects/p/locations/us-central1";
            String name = parent + "/functions/fn";

            var create = new TestObserver<Operation>();
            service.createFunction(CreateFunctionRequest.newBuilder()
                    .setParent(parent)
                    .setFunctionId("fn")
                    .setFunction(function("nodejs20", "hello"))
                    .build(), create);
            Function created = create.value().getResponse().unpack(Function.class);
            assertEquals(name, created.getName());
            assertEquals(Function.State.ACTIVE, created.getState());
            assertEquals("http://localhost:8081", created.getServiceConfig().getUri());

            var duplicate = new TestObserver<Operation>();
            service.createFunction(CreateFunctionRequest.newBuilder()
                    .setParent(parent)
                    .setFunctionId("fn")
                    .setFunction(function("nodejs20", "hello"))
                    .build(), duplicate);
            assertEquals(Status.Code.ALREADY_EXISTS, duplicate.errorCode());

            var get = new TestObserver<Function>();
            service.getFunction(GetFunctionRequest.newBuilder().setName(name).build(), get);
            assertEquals("nodejs20", get.value().getBuildConfig().getRuntime());

            var list = new TestObserver<ListFunctionsResponse>();
            service.listFunctions(ListFunctionsRequest.newBuilder().setParent(parent).build(), list);
            assertEquals(1, list.value().getFunctionsCount());

            var update = new TestObserver<Operation>();
            service.updateFunction(UpdateFunctionRequest.newBuilder()
                    .setFunction(created.toBuilder()
                            .setBuildConfig(BuildConfig.newBuilder().setRuntime("python311").setEntryPoint("main"))
                            .build())
                    .build(), update);
            Function updated = update.value().getResponse().unpack(Function.class);
            assertEquals("python311", updated.getBuildConfig().getRuntime());
            assertEquals(created.getServiceConfig().getUri(), updated.getServiceConfig().getUri());

            var upload = new TestObserver<GenerateUploadUrlResponse>();
            service.generateUploadUrl(GenerateUploadUrlRequest.newBuilder().setParent(parent).build(), upload);
            assertFalse(upload.value().getUploadUrl().isBlank());

            var delete = new TestObserver<Operation>();
            service.deleteFunction(DeleteFunctionRequest.newBuilder().setName(name).build(), delete);
            assertTrue(delete.value().getDone());

            var missing = new TestObserver<Function>();
            service.getFunction(GetFunctionRequest.newBuilder().setName(name).build(), missing);
            assertEquals(Status.Code.NOT_FOUND, missing.errorCode());
        } finally {
            emulator.stop();
            testDataSource.close();
        }
    }

    @Test
    void createFunctionRejectsUnsupportedRuntime() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("functions_service_bad_runtime_" + System.nanoTime());
        CloudFunctionsEmulator emulator = new CloudFunctionsEmulator(testDataSource.getDataSource());
        emulator.start();
        try {
            var observer = new TestObserver<Operation>();
            emulator.getServiceImpl().createFunction(CreateFunctionRequest.newBuilder()
                    .setParent("projects/p/locations/us-central1")
                    .setFunctionId("fn")
                    .setFunction(function("nodejs6", "hello"))
                    .build(), observer);
            assertEquals(Status.Code.INVALID_ARGUMENT, observer.errorCode());
        } finally {
            emulator.stop();
            testDataSource.close();
        }
    }

    @Test
    void createFunctionMapsServiceConfigToCloudRunUri() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("functions_service_cloudrun_" + System.nanoTime());
        createCloudRunSchema(testDataSource);
        CloudRunStore cloudRunStore = new CloudRunStore(testDataSource.getDataSource());
        cloudRunStore.insertService(new CloudRunStore.Service(
                "p", "us-central1", "run-fn", "example/image", 8080,
                "container-1", 12000, "http://localhost:12000", "{}", 1, null, null));
        CloudFunctionsEmulator emulator = new CloudFunctionsEmulator(
                testDataSource.getDataSource(), cloudRunStore, new RecordingRegistrar());
        emulator.start();
        try {
            var create = new TestObserver<Operation>();
            emulator.getServiceImpl().createFunction(CreateFunctionRequest.newBuilder()
                    .setParent("projects/p/locations/us-central1")
                    .setFunctionId("fn")
                    .setFunction(function("nodejs20", "hello").toBuilder()
                            .setServiceConfig(ServiceConfig.newBuilder()
                                    .setService("projects/p/locations/us-central1/services/run-fn")
                                    .build())
                            .build())
                    .build(), create);

            Function created = create.value().getResponse().unpack(Function.class);
            assertEquals("projects/p/locations/us-central1/services/run-fn", created.getServiceConfig().getService());
            assertEquals("http://localhost:12000", created.getServiceConfig().getUri());
        } finally {
            emulator.stop();
            testDataSource.close();
        }
    }

    @Test
    void eventTriggerRegistersAndDeletesPubSubSubscription() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("functions_service_pubsub_trigger_" + System.nanoTime());
        RecordingRegistrar registrar = new RecordingRegistrar();
        CloudFunctionsEmulator emulator = new CloudFunctionsEmulator(
                testDataSource.getDataSource(), new CloudRunStore(testDataSource.getDataSource()), registrar);
        emulator.start();
        try {
            String parent = "projects/p/locations/us-central1";
            String name = parent + "/functions/fn";
            var create = new TestObserver<Operation>();
            emulator.getServiceImpl().createFunction(CreateFunctionRequest.newBuilder()
                    .setParent(parent)
                    .setFunctionId("fn")
                    .setFunction(function("nodejs20", "hello").toBuilder()
                            .setEventTrigger(EventTrigger.newBuilder()
                                    .setPubsubTopic("events")
                                    .setEventType("google.cloud.pubsub.topic.v1.messagePublished")
                                    .build())
                            .build())
                    .build(), create);
            create.value();

            assertEquals("projects/p/topics/events", registrar.topicName);
            assertEquals("projects/p/subscriptions/localcloud-fn-fn", registrar.subscriptionName);
            assertEquals("http://localhost:24080/functions/trigger/p/us-central1/fn", registrar.pushEndpoint);

            var delete = new TestObserver<Operation>();
            emulator.getServiceImpl().deleteFunction(DeleteFunctionRequest.newBuilder().setName(name).build(), delete);
            delete.value();
            assertEquals("projects/p/subscriptions/localcloud-fn-fn", registrar.unregisteredSubscriptionName);
        } finally {
            emulator.stop();
            testDataSource.close();
        }
    }

    private static Function function(String runtime, String entryPoint) {
        return Function.newBuilder()
                .setBuildConfig(BuildConfig.newBuilder()
                        .setRuntime(runtime)
                        .setEntryPoint(entryPoint)
                        .build())
                .build();
    }

    private static void createCloudRunSchema(TestDataSource testDataSource) throws Exception {
        try (var conn = testDataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS cloudrun_services (
                        project_id VARCHAR(255) NOT NULL,
                        location VARCHAR(255) NOT NULL,
                        service_id VARCHAR(255) NOT NULL,
                        container_image VARCHAR(512) NOT NULL,
                        container_port INT DEFAULT 8080,
                        container_id VARCHAR(255),
                        host_port INT,
                        uri VARCHAR(1024),
                        env_vars TEXT DEFAULT '{}',
                        revision_count INT DEFAULT 1,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (project_id, location, service_id)
                    )
                    """);
        }
    }

    private static final class RecordingRegistrar implements CloudFunctionsEmulator.PubSubTriggerRegistrar {
        private String topicName;
        private String subscriptionName;
        private String pushEndpoint;
        private String unregisteredSubscriptionName;

        @Override
        public void register(String topicName, String subscriptionName, String pushEndpoint) {
            this.topicName = topicName;
            this.subscriptionName = subscriptionName;
            this.pushEndpoint = pushEndpoint;
        }

        @Override
        public void unregister(String subscriptionName) {
            this.unregisteredSubscriptionName = subscriptionName;
        }
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
