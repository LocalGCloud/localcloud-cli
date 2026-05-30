package com.localcloud.emulators.workflows.connector;

import com.localcloud.emulators.workflows.engine.ExecutionContext;
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

    @Test
    void testExpandedNonBigQueryConnectorsRegistered() {
        assertTrue(registry.has("googleapis.pubsub.v1.projects.subscriptions.pull"));
        assertTrue(registry.has("googleapis.secretmanager.v1.projects.secrets.addVersion"));
        assertTrue(registry.has("googleapis.cloudtasks.v2.projects.locations.queues.tasks.create"));
        assertTrue(registry.has("googleapis.firestore.v1.projects.databases.documents.patch"));
        assertTrue(registry.has("googleapis.logging.v2.entries.write"));
        assertTrue(registry.has("googleapis.monitoring.v3.projects.timeSeries.list"));
        assertTrue(registry.has("googleapis.compute.v1.instances.list"));
        assertTrue(registry.has("googleapis.run.v2.projects.locations.services.list"));
        assertTrue(registry.has("googleapis.container.v1.projects.locations.clusters.list"));
        assertTrue(registry.has("googleapis.workflows.v1.projects.locations.workflows.list"));
        assertTrue(registry.has("googleapis.workflowexecutions.v1.projects.locations.workflows.executions.create"));
    }

    @org.junit.jupiter.api.Disabled("Requires network connectivity for HTTP fallback — fails in offline CI")
    @Test void testUnknownGoogleapisConnectorAttemptsFallback() {
        // googleapis.* unknown connectors attempt HTTP fallback instead of throwing
        assertDoesNotThrow(() ->
            registry.execute("googleapis.unknown.v1.resources.list", java.util.Map.of()));
    }

    @Test void testNonGoogleapisConnectorThrows() {
        // Non-googleapis connectors still throw immediately
        assertThrows(RuntimeException.class, () ->
            registry.execute("custom.unknown.v1.resources.list", java.util.Map.of()));
    }

    @Test void testCustomRegistration() {
        registry.register("custom.service.v1.items.list", "GET", "http://localhost:9999/items");
        assertTrue(registry.has("custom.service.v1.items.list"));
    }

    @Test
    void testHasChildWorkflowConnector() {
        ConnectorRegistry registry = new ConnectorRegistry();
        assertTrue(registry.has("googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run"));
    }

    @Test
    void currentContext_setAndGet_roundTrips() {
        ExecutionContext ctx = new ExecutionContext();
        ConnectorRegistry.setCurrentContext(ctx);
        assertSame(ctx, ConnectorRegistry.getCurrentContext());
        ConnectorRegistry.clearCurrentContext();
        assertNull(ConnectorRegistry.getCurrentContext());
    }

    @Test
    void currentContext_defaultIsNull() {
        ConnectorRegistry.clearCurrentContext();
        assertNull(ConnectorRegistry.getCurrentContext());
    }
}
