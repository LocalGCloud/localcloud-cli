package com.localcloud.emulators.dataproc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.cloud.dataproc.v1.CancelJobRequest;
import com.google.cloud.dataproc.v1.Cluster;
import com.google.cloud.dataproc.v1.ClusterStatus;
import com.google.cloud.dataproc.v1.CreateClusterRequest;
import com.google.cloud.dataproc.v1.DeleteClusterRequest;
import com.google.cloud.dataproc.v1.GetClusterRequest;
import com.google.cloud.dataproc.v1.GetJobRequest;
import com.google.cloud.dataproc.v1.Job;
import com.google.cloud.dataproc.v1.JobPlacement;
import com.google.cloud.dataproc.v1.JobReference;
import com.google.cloud.dataproc.v1.JobStatus;
import com.google.cloud.dataproc.v1.ListClustersRequest;
import com.google.cloud.dataproc.v1.ListClustersResponse;
import com.google.cloud.dataproc.v1.ListJobsRequest;
import com.google.cloud.dataproc.v1.ListJobsResponse;
import com.google.cloud.dataproc.v1.SubmitJobRequest;
import com.google.longrunning.Operation;
import com.localcloud.integration.TestDataSource;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

class DataprocServiceTest {
    @Test
    void managesClustersAndJobs() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("dataproc_service_" + System.nanoTime());
        DataprocEmulator emulator = new DataprocEmulator(testDataSource.getDataSource());
        emulator.start();
        try {
            var clusters = emulator.getClusterService();
            var jobs = emulator.getJobService();

            var createCluster = new TestObserver<Operation>();
            clusters.createCluster(CreateClusterRequest.newBuilder()
                    .setProjectId("p")
                    .setRegion("us-central1")
                    .setCluster(Cluster.newBuilder().setClusterName("c").build())
                    .build(), createCluster);
            Cluster cluster = createCluster.value().getResponse().unpack(Cluster.class);
            assertEquals("p", cluster.getProjectId());
            assertEquals("c", cluster.getClusterName());
            assertEquals(ClusterStatus.State.RUNNING, cluster.getStatus().getState());

            var duplicate = new TestObserver<Operation>();
            clusters.createCluster(CreateClusterRequest.newBuilder()
                    .setProjectId("p")
                    .setRegion("us-central1")
                    .setCluster(Cluster.newBuilder().setClusterName("c").build())
                    .build(), duplicate);
            assertEquals(Status.Code.ALREADY_EXISTS, duplicate.errorCode());

            var getCluster = new TestObserver<Cluster>();
            clusters.getCluster(GetClusterRequest.newBuilder()
                    .setProjectId("p")
                    .setRegion("us-central1")
                    .setClusterName("c")
                    .build(), getCluster);
            assertEquals("c", getCluster.value().getClusterName());

            var listClusters = new TestObserver<ListClustersResponse>();
            clusters.listClusters(ListClustersRequest.newBuilder()
                    .setProjectId("p")
                    .setRegion("us-central1")
                    .build(), listClusters);
            assertEquals(1, listClusters.value().getClustersCount());

            var missingClusterJob = new TestObserver<Job>();
            jobs.submitJob(SubmitJobRequest.newBuilder()
                    .setProjectId("p")
                    .setRegion("us-central1")
                    .setJob(job("missing", "missing-cluster"))
                    .build(), missingClusterJob);
            assertEquals(Status.Code.NOT_FOUND, missingClusterJob.errorCode());

            var submit = new TestObserver<Job>();
            jobs.submitJob(SubmitJobRequest.newBuilder()
                    .setProjectId("p")
                    .setRegion("us-central1")
                    .setJob(job("j", "c"))
                    .build(), submit);
            assertEquals("j", submit.value().getReference().getJobId());
            assertFalseBlank(submit.value().getDriverOutputResourceUri());

            var getJob = new TestObserver<Job>();
            jobs.getJob(GetJobRequest.newBuilder()
                    .setProjectId("p")
                    .setRegion("us-central1")
                    .setJobId("j")
                    .build(), getJob);
            assertEquals(JobStatus.State.ERROR, getJob.value().getStatus().getState());

            var listJobs = new TestObserver<ListJobsResponse>();
            jobs.listJobs(ListJobsRequest.newBuilder()
                    .setProjectId("p")
                    .setRegion("us-central1")
                    .build(), listJobs);
            assertEquals(1, listJobs.value().getJobsCount());

            var cancel = new TestObserver<Job>();
            jobs.cancelJob(CancelJobRequest.newBuilder()
                    .setProjectId("p")
                    .setRegion("us-central1")
                    .setJobId("j")
                    .build(), cancel);
            assertEquals(JobStatus.State.CANCELLED, cancel.value().getStatus().getState());

            var deleteCluster = new TestObserver<Operation>();
            clusters.deleteCluster(DeleteClusterRequest.newBuilder()
                    .setProjectId("p")
                    .setRegion("us-central1")
                    .setClusterName("c")
                    .build(), deleteCluster);
            assertTrue(deleteCluster.value().getDone());

            var missingCluster = new TestObserver<Cluster>();
            clusters.getCluster(GetClusterRequest.newBuilder()
                    .setProjectId("p")
                    .setRegion("us-central1")
                    .setClusterName("c")
                    .build(), missingCluster);
            assertEquals(Status.Code.NOT_FOUND, missingCluster.errorCode());
        } finally {
            emulator.stop();
            testDataSource.close();
        }
    }

    private static Job job(String jobId, String clusterName) {
        return Job.newBuilder()
                .setReference(JobReference.newBuilder().setJobId(jobId))
                .setPlacement(JobPlacement.newBuilder().setClusterName(clusterName))
                .build();
    }

    private static void assertFalseBlank(String value) {
        assertNotNull(value);
        assertTrue(!value.isBlank());
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
