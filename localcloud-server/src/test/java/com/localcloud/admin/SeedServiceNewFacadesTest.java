package com.localcloud.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.Policy;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.emulators.alloydb.AlloyDBEmulator;
import com.localcloud.emulators.dataproc.DataprocEmulator;
import com.localcloud.emulators.functions.CloudFunctionsEmulator;
import com.localcloud.emulators.iam.IAMEmulator;
import com.localcloud.emulators.scheduler.CloudSchedulerEmulator;
import com.localcloud.emulators.workflows.WorkflowsStore;
import com.localcloud.integration.TestDataSource;

import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

class SeedServiceNewFacadesTest {
    @Test
    void seedsSchedulerFunctionsAlloyDBDataprocAndIAM() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("seed_new_facades_" + System.nanoTime());
        LocalCloudConfig config = LocalCloudConfig.fromEnvironment();

        CloudSchedulerEmulator scheduler = new CloudSchedulerEmulator(testDataSource.getDataSource());
        CloudFunctionsEmulator functions = new CloudFunctionsEmulator(testDataSource.getDataSource());
        AlloyDBEmulator alloyDB = new AlloyDBEmulator(testDataSource.getDataSource());
        DataprocEmulator dataproc = new DataprocEmulator(testDataSource.getDataSource());
        IAMEmulator iam = new IAMEmulator(testDataSource.getDataSource());
        scheduler.start();
        functions.start();
        alloyDB.start();
        dataproc.start();
        iam.start();

        try {
            SeedService seedService = new SeedService(config, testDataSource.getDataSource(),
                    config.getServiceRegistry(), new WorkflowsStore(testDataSource.getDataSource()));
            seedService.setCloudSchedulerEmulator(scheduler);
            seedService.setCloudFunctionsEmulator(functions);
            seedService.setAlloyDBEmulator(alloyDB);
            seedService.setDataprocEmulator(dataproc);
            seedService.setIAMEmulator(iam);

            String projectId = config.getProjectId();
            String yaml = """
                    services:
                      cloudscheduler:
                        jobs:
                          - job_id: nightly
                            schedule: "0 0 1 1 *"
                            time_zone: UTC
                            http_target:
                              uri: "http://127.0.0.1:9/task"
                              http_method: POST
                              body: "{}"
                      cloudfunctions:
                        functions:
                          - function_id: hello
                            runtime: nodejs20
                            entry_point: hello
                            source: gs://function-sources/hello.zip
                            service_config:
                              uri: http://localhost:8081/hello
                      alloydb:
                        clusters:
                          - cluster_id: app
                            instances:
                              - instance_id: primary
                      dataproc:
                        clusters:
                          - cluster_name: analytics
                      cloudiam:
                        policies:
                          - resource: projects/%s/buckets/app
                            bindings:
                              - role: roles/storage.objectViewer
                                members:
                                  - user:test@example.com
                    """.formatted(projectId);

            Map<String, Object> result = seedService.seedYaml(yaml, false);

            assertEquals("seeded", result.get("status"));
            assertEquals(6, result.get("total_records"));
            assertEquals(1, countRows(testDataSource, "scheduler_jobs"));
            assertEquals(1, countRows(testDataSource, "cloud_functions"));
            assertEquals(1, countRows(testDataSource, "alloydb_clusters"));
            assertEquals(1, countRows(testDataSource, "alloydb_instances"));
            assertEquals(1, countRows(testDataSource, "dataproc_clusters"));
            assertEquals(1, countRows(testDataSource, "iam_policies"));

            TestObserver<Policy> policy = new TestObserver<>();
            iam.getServiceImpl().getIamPolicy(GetIamPolicyRequest.newBuilder()
                    .setResource("projects/" + projectId + "/buckets/app")
                    .build(), policy);
            assertEquals("roles/storage.objectViewer", policy.value().getBindings(0).getRole());
        } finally {
            scheduler.stop();
            functions.stop();
            alloyDB.stop();
            dataproc.stop();
            iam.stop();
            testDataSource.close();
        }
    }

    private static int countRows(TestDataSource testDataSource, String table) throws Exception {
        try (var conn = testDataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rs.next());
            return rs.getInt(1);
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
            assertEquals(null, error, () -> String.valueOf(error));
            assertTrue(completed);
            return value;
        }
    }
}
