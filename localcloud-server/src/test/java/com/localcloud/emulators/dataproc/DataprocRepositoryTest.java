package com.localcloud.emulators.dataproc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.cloud.dataproc.v1.Cluster;
import com.google.cloud.dataproc.v1.ClusterStatus;
import com.google.cloud.dataproc.v1.Job;
import com.google.cloud.dataproc.v1.JobPlacement;
import com.google.cloud.dataproc.v1.JobReference;
import com.google.cloud.dataproc.v1.JobStatus;
import com.localcloud.integration.TestDataSource;
import org.junit.jupiter.api.Test;

class DataprocRepositoryTest {
    @Test
    void createListDeleteClusterAndPersistJob() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("dataproc_repo");
        try {
            DataprocRepository repository = new DataprocRepository(testDataSource.getDataSource());
            Cluster cluster = Cluster.newBuilder()
                    .setProjectId("p")
                    .setClusterName("c")
                    .setStatus(ClusterStatus.newBuilder().setState(ClusterStatus.State.RUNNING))
                    .build();
            repository.createCluster("p", "us", "c", cluster);
            assertTrue(repository.clusterExists("p", "us", "c"));
            assertEquals(1, repository.listClusters("p", "us").size());

            Job job = Job.newBuilder()
                    .setReference(JobReference.newBuilder().setProjectId("p").setJobId("j"))
                    .setPlacement(JobPlacement.newBuilder().setClusterName("c"))
                    .setStatus(JobStatus.newBuilder().setState(JobStatus.State.PENDING))
                    .build();
            repository.createJob("p", "us", "j", "c", "PENDING", "/tmp/j.log", job);
            assertEquals(job, repository.getJob("p", "us", "j"));

            repository.deleteCluster("p", "us", "c");
            assertNull(repository.getCluster("p", "us", "c"));
        } finally {
            testDataSource.close();
        }
    }
}
