package com.localcloud.gateway;

import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Gateway decorator that records every served request into the {@link RequestLogger}
 * ring buffer, which backs the console's Logs page ({@code /requests}).
 *
 * <p>Logs real service/data traffic (REST {@code /v1/*} paths, gRPC service paths,
 * and the gateway data-plane endpoints — {@code /browse}, {@code /query}, {@code /mutate},
 * {@code /schema}). Pure admin, console static assets, health, diagnostics, and the
 * request-log endpoint itself are skipped to avoid noise and recursion.
 *
 * <p>The delegate's {@link HttpResponse} stays untouched so streamed responses preserve
 * their normal subscription and backpressure behavior. Completed status, sizes, and
 * duration are read from Armeria's request log after the server finishes the exchange.
 */
public class RequestLoggingDecorator implements DecoratingHttpServiceFunction {

    private final RequestLogger requestLogger;

    public RequestLoggingDecorator(RequestLogger requestLogger) {
        this.requestLogger = requestLogger;
    }

    @Override
    public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req) throws Exception {
        String path = ctx.path();
        if (shouldSkip(path)) {
            return delegate.serve(ctx, req);
        }

        String method = req.method().name();
        String service = resolveLoggedService(ctx, path);

        HttpResponse response = delegate.serve(ctx, req);

        // Observe Armeria's completed request log instead of aggregating the response.
        // Aggregating here would subscribe to the response stream and can consume or
        // buffer streaming responses before the server writes them to the client.
        ctx.log().whenComplete().thenAccept(log -> {
            long duration = TimeUnit.NANOSECONDS.toMillis(log.totalDurationNanos());
            int statusCode = log.responseStatus().code();
            requestLogger.log(RequestLogger.RequestLogEntry.create(
                    service, method, path, statusCode, duration,
                    log.requestLength(), log.responseLength()));
        });

        return response;
    }

    private static String resolveLoggedService(ServiceRequestContext ctx, String path) {
        String service = ServiceGatingDecorator.resolveService(path);
        if (service != null) {
            return service;
        }

        // Developer data-plane routes use the service as the first annotated path
        // parameter (for example, /browse/bigquery). These paths increment usage
        // metrics inside their handlers and must retain the same service identity
        // in the request log so /requests?service=... sees the event.
        if (path.startsWith("/browse/") || path.startsWith("/mutate/")
                || path.startsWith("/schema/")) {
            return ctx.pathParam("service");
        }
        return null;
    }

    private static boolean shouldSkip(String path) {
        if (path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".svg")
                || path.endsWith(".png") || path.endsWith(".ico") || path.endsWith(".html")) {
            return true;
        }
        return path.equals("/")
                || path.equals("/health") || path.startsWith("/health/")
                || path.equals("/readiness") || path.startsWith("/readiness/")
                || path.equals("/requests")
                || path.equals("/usage")
                || path.equals("/diagnostics") || path.startsWith("/diagnostics/")
                || path.equals("/faults") || path.startsWith("/faults/")
                || path.startsWith("/dashboard/")
                || path.startsWith("/icons")
                || path.startsWith("/computeMetadata/v1")
                || path.equals("/terraform/readiness");
    }

}