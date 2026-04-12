package com.localcloud.admin;

import java.util.Map;

import com.localcloud.integration.TestDataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServiceRoutingRepositoryTest {

    private TestDataSource testDs;
    private ServiceRoutingRepository repo;

    @BeforeEach
    void setUp() {
        testDs = TestDataSource.create("routing_test_" + System.nanoTime());
        repo = new ServiceRoutingRepository(testDs.getDataSource());
    }

    @AfterEach
    void tearDown() {
        testDs.close();
    }

    @Test
    void getReturnsNullWhenNoConfig() throws Exception {
        assertNull(repo.get("proj", "gcs"));
    }

    @Test
    void upsertAndGet() throws Exception {
        repo.upsert("proj", "gcs", "remote", "my-dev", "us-central1");

        Map<String, String> config = repo.get("proj", "gcs");
        assertNotNull(config);
        assertEquals("remote", config.get("mode"));
        assertEquals("my-dev", config.get("remote_project"));
        assertEquals("us-central1", config.get("remote_region"));
    }

    @Test
    void upsertUpdatesExisting() throws Exception {
        repo.upsert("proj", "gcs", "remote", "dev1", "us-east1");
        repo.upsert("proj", "gcs", "local", null, null);

        Map<String, String> config = repo.get("proj", "gcs");
        assertNotNull(config);
        assertEquals("local", config.get("mode"));
        assertNull(config.get("remote_project"));
    }

    @Test
    void getAllReturnsMultipleServices() throws Exception {
        repo.upsert("proj", "gcs", "remote", "dev", "us-central1");
        repo.upsert("proj", "pubsub", "local", null, null);

        Map<String, Map<String, String>> all = repo.getAll("proj");
        assertEquals(2, all.size());
        assertEquals("remote", all.get("gcs").get("mode"));
        assertEquals("local", all.get("pubsub").get("mode"));
    }

    @Test
    void getAllReturnsEmptyForUnknownProject() throws Exception {
        Map<String, Map<String, String>> all = repo.getAll("nonexistent");
        assertTrue(all.isEmpty());
    }

    @Test
    void defaultModeIsLocal() throws Exception {
        // No entry means local (null return = default to local in caller)
        assertNull(repo.get("proj", "gcs"));
    }
}
