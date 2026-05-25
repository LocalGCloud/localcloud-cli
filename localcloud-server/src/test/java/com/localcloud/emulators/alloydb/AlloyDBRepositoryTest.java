package com.localcloud.emulators.alloydb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.cloud.alloydb.v1.Cluster;
import com.google.cloud.alloydb.v1.Instance;
import com.localcloud.integration.TestDataSource;
import org.junit.jupiter.api.Test;

class AlloyDBRepositoryTest {
    @Test
    void clusterCascadeDeletesInstances() throws Exception {
        TestDataSource testDataSource = TestDataSource.create("alloydb_repo");
        try {
            AlloyDBRepository repository = new AlloyDBRepository(testDataSource.getDataSource());
            Cluster cluster = Cluster.newBuilder()
                    .setName("projects/p/locations/us/clusters/c")
                    .setState(Cluster.State.READY)
                    .build();
            Instance instance = Instance.newBuilder()
                    .setName("projects/p/locations/us/clusters/c/instances/i")
                    .setState(Instance.State.READY)
                    .build();

            repository.createCluster("p", "us", "c", cluster);
            repository.createInstance("p", "us", "c", "i", instance);
            assertTrue(repository.clusterExists("p", "us", "c"));
            assertEquals(instance, repository.getInstance("p", "us", "c", "i"));

            repository.deleteCluster("p", "us", "c");
            assertNull(repository.getCluster("p", "us", "c"));
        } finally {
            testDataSource.close();
        }
    }
}
