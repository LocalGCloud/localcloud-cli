package com.localcloud.gateway;

import java.util.Map;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.localcloud.config.LocalCloudConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorator that gates requests to facade services based on their enabled/disabled state.
 * Returns 503 Service Unavailable for requests to disabled services.
 * Admin endpoints are always accessible.
 */
public class ServiceGatingDecorator implements DecoratingHttpServiceFunction {

    private static final Logger logger = LoggerFactory.getLogger(ServiceGatingDecorator.class);

    private final LocalCloudConfig config;

    // Map gRPC path prefixes to service IDs
    private static final Map<String, String> GRPC_PATH_TO_SERVICE = Map.ofEntries(
        Map.entry("/google.cloud.secretmanager", "secretmanager"),
        Map.entry("/google.cloud.tasks", "cloudtasks"),
        Map.entry("/google.logging", "logging"),
        Map.entry("/google.monitoring", "monitoring"),
        Map.entry("/google.container", "gke"),
        Map.entry("/google.cloud.run", "cloudrun"),
        Map.entry("/google.cloud.workflows", "workflows"),
        Map.entry("/google.cloud.redis", "memorystore")
    );

    public ServiceGatingDecorator(LocalCloudConfig config) {
        this.config = config;
    }

    @Override
    public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx,
                              HttpRequest req) throws Exception {
        String path = ctx.path();

        // Admin endpoints are always accessible
        if (isAdminOrConsolePath(path)) {
            return delegate.serve(ctx, req);
        }

        // Static console assets always accessible
        if (path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".svg") || path.endsWith(".html")) {
            return delegate.serve(ctx, req);
        }

        // Check if this path maps to a known facade service
        String serviceId = resolveService(path);
        if (serviceId != null && !config.isServiceDynamicallyEnabled(serviceId)) {
            logger.debug("Service '{}' is disabled, returning 501 for path: {}", serviceId, path);
            return HttpResponse.of(HttpStatus.NOT_IMPLEMENTED, MediaType.JSON,
                    "{\"error\":\"Service '" + serviceId + "' is not implemented\"}");
        }

        return delegate.serve(ctx, req);
    }

    private static boolean isAdminOrConsolePath(String path) {
        return path.startsWith("/icons")
                || path.equals("/")
                || path.equals("/health")
                || path.startsWith("/health/")
                || path.equals("/readiness")
                || path.startsWith("/readiness/")
                || path.equals("/services")
                || path.startsWith("/services/")
                || path.equals("/usage")
                || path.equals("/env")
                || path.equals("/requests")
                || path.equals("/profiles")
                || path.equals("/capabilities")
                || path.equals("/coverage")
                || path.startsWith("/coverage/")
                || path.equals("/diagnostics")
                || path.startsWith("/diagnostics/")
                || path.equals("/faults")
                || path.startsWith("/faults/")
                || path.startsWith("/export")
                || path.equals("/import")
                || path.equals("/seed")
                || path.equals("/reseed")
                || path.equals("/reset")
                || path.startsWith("/reset/")
                || path.equals("/projects")
                || path.startsWith("/projects/")
                || path.equals("/routing")
                || path.startsWith("/routing/")
                || path.equals("/credentials")
                || path.startsWith("/config/")
                || path.equals("/browse")
                || path.startsWith("/browse/")
                || path.equals("/mutate")
                || path.startsWith("/mutate/")
                || path.equals("/query")
                || path.startsWith("/query/")
                || path.startsWith("/schema/")
                || path.equals("/gcs/file-schema")
                || path.equals("/query-history")
                || path.startsWith("/workflow-env")
                || path.startsWith("/workflow")
                || path.startsWith("/sync")
                || path.equals("/snapshots")
                || path.startsWith("/snapshots/")
                || path.startsWith("/dashboard/")
                || path.startsWith("/computeMetadata/v1")
                || path.equals("/terraform/readiness");
    }

    /**
     * Resolve a request path to a service ID. Uses prefix matching for gRPC paths
     * and service-specific path segment checks for REST paths to avoid false positives.
     */
    static String resolveService(String path) {
        // gRPC service paths (dot-prefixed, unambiguous)
        for (Map.Entry<String, String> entry : GRPC_PATH_TO_SERVICE.entrySet()) {
            if (path.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        // REST paths — match on service-specific segments to avoid overly broad prefixes
        if (path.startsWith("/v1/") && path.contains("/secrets")) {
            return "secretmanager";
        }
        if (path.startsWith("/v1/") && path.contains("/publishers/") && path.contains("/models/")) {
            return "vertexai";
        }
        if (path.startsWith("/v1/") && path.contains("/keyRings")) {
            return "kms";
        }
        if (path.startsWith("/sql/v1/") || path.startsWith("/sql/v1beta4/")) {
            return "cloudsql";
        }
        if (path.startsWith("/v2/") && path.contains("/queues")) {
            return "cloudtasks";
        }
        if (path.startsWith("/compute/v1")) {
            return "compute";
        }
        if (path.startsWith("/spanner/")) {
            return "spanner";
        }
        if (path.startsWith("/bigquery/")) {
            return "bigquery";
        }
        if (path.startsWith("/datastore/")) {
            return "firestore";
        }
        if (path.startsWith("/v1/") && path.contains("/projects/") && path.contains("/locations/") 
                && path.contains("/instances/")) {
            return "memorystore";
        }
        if (path.startsWith("/pubsub/")) {
            return "pubsub";
        }
        if (path.startsWith("/storage/")) {
            return "gcs";
        }
        if (path.startsWith("/bigtable/")) {
            return "bigtable";
        }
        if (path.startsWith("/v1/") && path.contains("/logs")) {
            return "logging";
        }
        if (path.startsWith("/v1/") && (path.contains("/metricDescriptors") || path.contains("/timeSeries"))) {
            return "monitoring";
        }
        if (path.startsWith("/v1/") && (path.contains("/workflows") || path.contains("/executions"))) {
            return "workflows";
        }
        return null;
    }
}
