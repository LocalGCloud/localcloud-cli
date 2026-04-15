package com.localcloud.emulators.workflows.connector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

class ConnectorRegistryTest {
    private ConnectorRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ConnectorRegistry();
    }

    @Test void testGcsListRegistered() { assertTrue(registry.has("googleapis.storage.v1.objects.list")); }
    @Test void testGcsInsertRegistered() { assertTrue(registry.has("googleapis.storage.v1.objects.insert")); }
    @Test void testGcsBucketsRegistered() { assertTrue(registry.has("googleapis.storage.v1.buckets.list")); }
    @Test void testBigQueryQueryRegistered() { assertTrue(registry.has("googleapis.bigquery.v2.jobs.query")); }
    @Test void testBigQueryDatasetsRegistered() { assertTrue(registry.has("googleapis.bigquery.v2.datasets.list")); }
    @Test void testPubSubPublishRegistered() { assertTrue(registry.has("googleapis.pubsub.v1.projects.topics.publish")); }
    @Test void testSecretManagerRegistered() { assertTrue(registry.has("googleapis.secretmanager.v1.projects.secrets.list")); }
    @Test void testCloudTasksRegistered() { assertTrue(registry.has("googleapis.cloudtasks.v2.projects.locations.queues.list")); }
    @Test void testFirestoreRegistered() { assertTrue(registry.has("googleapis.firestore.v1.projects.databases.documents.get")); }
    @Test void testUnknownConnector() { assertFalse(registry.has("googleapis.unknown.v1.resources.list")); }

    @Test void testUnknownConnectorThrows() {
        assertThrows(RuntimeException.class, () ->
            registry.execute("googleapis.unknown.v1.resources.list", java.util.Map.of()));
    }

    @Test void testCustomRegistration() {
        registry.register("custom.service.v1.items.list", "GET", "http://localhost:9999/items");
        assertTrue(registry.has("custom.service.v1.items.list"));
    }
}
