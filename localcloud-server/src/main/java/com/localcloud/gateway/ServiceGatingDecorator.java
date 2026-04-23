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
 * Admin endpoints (/_localcloud) are always accessible.
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
        if (path.startsWith("/_localcloud") || path.startsWith("/icons") || path.equals("/")) {
            return delegate.serve(ctx, req);
        }

        // Static console assets always accessible
        if (path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".svg") || path.endsWith(".html")) {
            return delegate.serve(ctx, req);
        }

        // Check if this path maps to a known facade service
        String serviceId = resolveService(path);
        if (serviceId != null && !config.isServiceDynamicallyEnabled(serviceId)) {
            logger.debug("Service '{}' is disabled, returning 503 for path: {}", serviceId, path);
            return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE, MediaType.JSON,
                    "{\"error\":\"Service '" + serviceId + "' is disabled\"}");
        }

        return delegate.serve(ctx, req);
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
        if (path.startsWith("/v2/") && path.contains("/queues")) {
            return "cloudtasks";
        }
        if (path.startsWith("/compute/v1")) {
            return "compute";
        }
        return null;
    }
}
