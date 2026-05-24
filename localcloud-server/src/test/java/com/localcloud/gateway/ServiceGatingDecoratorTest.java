package com.localcloud.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ServiceGatingDecorator path resolution logic.
 * Verifies that gRPC and REST paths map to the correct service IDs
 * and that REST path matching is specific enough to avoid false positives.
 */
class ServiceGatingDecoratorTest {

    // --- gRPC path resolution ---

    @ParameterizedTest
    @CsvSource({
        "/google.cloud.secretmanager.v1.SecretManagerService/CreateSecret, secretmanager",
        "/google.cloud.secretmanager.v1.SecretManagerService/GetSecret, secretmanager",
        "/google.cloud.tasks.v2.CloudTasks/CreateQueue, cloudtasks",
        "/google.cloud.tasks.v2.CloudTasks/ListQueues, cloudtasks",
        "/google.logging.v2.LoggingServiceV2/WriteLogEntries, logging",
        "/google.monitoring.v3.MetricService/CreateTimeSeries, monitoring",
        "/google.container.v1.ClusterManager/ListClusters, gke",
        "/google.cloud.run.v2.Services/CreateService, cloudrun",
        "/google.cloud.workflows.v1.Workflows/CreateWorkflow, workflows",
        "/google.cloud.redis.v1.CloudRedis/GetInstance, memorystore"
    })
    void resolveService_grpcPaths(String path, String expectedService) {
        assertEquals(expectedService, ServiceGatingDecorator.resolveService(path));
    }

    // --- REST path resolution (Terraform) ---

    @Test
    void resolveService_secretManagerRestPaths() {
        assertEquals("secretmanager",
                ServiceGatingDecorator.resolveService("/v1/projects/my-proj/secrets"));
        assertEquals("secretmanager",
                ServiceGatingDecorator.resolveService("/v1/projects/my-proj/secrets/my-secret"));
        assertEquals("secretmanager",
                ServiceGatingDecorator.resolveService("/v1/projects/p/secrets/s/versions/1"));
        assertEquals("secretmanager",
                ServiceGatingDecorator.resolveService("/v1/projects/p/secrets/s/versions/1:access"));
    }

    @Test
    void resolveService_cloudTasksRestPaths() {
        assertEquals("cloudtasks",
                ServiceGatingDecorator.resolveService("/v2/projects/p/locations/l/queues"));
        assertEquals("cloudtasks",
                ServiceGatingDecorator.resolveService("/v2/projects/p/locations/l/queues/q"));
        assertEquals("cloudtasks",
                ServiceGatingDecorator.resolveService("/v2/projects/p/locations/l/queues/q/tasks"));
    }

    @Test
    void resolveService_computeRestPaths() {
        assertEquals("compute",
                ServiceGatingDecorator.resolveService("/compute/v1/projects/p/zones/z/instances"));
        assertEquals("compute",
                ServiceGatingDecorator.resolveService("/compute/v1/projects/p/zones/z/instances/i"));
    }

    @Test
    void resolveService_newFacadeRestPaths() {
        assertEquals("vertexai",
                ServiceGatingDecorator.resolveService("/v1/projects/p/locations/us/publishers/google/models/gemini:generateContent"));
        assertEquals("kms",
                ServiceGatingDecorator.resolveService("/v1/projects/p/locations/us/keyRings/r/cryptoKeys/k:encrypt"));
        assertEquals("cloudsql",
                ServiceGatingDecorator.resolveService("/sql/v1beta4/projects/p/instances/i"));
        assertEquals("cloudsql",
                ServiceGatingDecorator.resolveService("/sql/v1/projects/p/instances/i/databases"));
    }

    // --- False positive prevention (the critical fix) ---

    @Test
    void resolveService_v1ProjectsWithoutSecrets_returnsNull() {
        // /v1/projects/ alone should NOT match secretmanager — it could be any v1 API
        assertNull(ServiceGatingDecorator.resolveService("/v1/projects/p/datasets"));
        assertNull(ServiceGatingDecorator.resolveService("/v1/projects/p/topics"));
        assertNull(ServiceGatingDecorator.resolveService("/v1/projects/p/instances"));
        assertNull(ServiceGatingDecorator.resolveService("/v1/projects/p/locations/l/functions"));
    }

    @Test
    void resolveService_v2ProjectsWithoutQueues_returnsNull() {
        // /v2/projects/ alone should NOT match cloudtasks
        assertNull(ServiceGatingDecorator.resolveService("/v2/projects/p/locations/l/services"));
        assertNull(ServiceGatingDecorator.resolveService("/v2/projects/p/locations/l/jobs"));
    }

    // --- Unknown paths ---

    @Test
    void resolveService_unknownPaths_returnNull() {
        assertNull(ServiceGatingDecorator.resolveService("/health"));
        assertNull(ServiceGatingDecorator.resolveService("/"));
        assertNull(ServiceGatingDecorator.resolveService("/some/random/path"));
        assertNull(ServiceGatingDecorator.resolveService("/v3/projects/p/resources"));
    }

    @Test
    void resolveService_rootAdminPaths_returnNull() {
        // Root-level admin endpoints are not service paths
        assertNull(ServiceGatingDecorator.resolveService("/health"));
        assertNull(ServiceGatingDecorator.resolveService("/health/localcloud-server"));
        assertNull(ServiceGatingDecorator.resolveService("/services"));
        assertNull(ServiceGatingDecorator.resolveService("/usage"));
        assertNull(ServiceGatingDecorator.resolveService("/export"));
        assertNull(ServiceGatingDecorator.resolveService("/export?format=shell"));
    }

    @Test
    void resolveService_emptyPath_returnsNull() {
        assertNull(ServiceGatingDecorator.resolveService(""));
    }
}
