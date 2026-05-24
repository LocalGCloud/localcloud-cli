package com.localcloud.gateway;

import java.util.Map;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Gateway decorator that injects configured latency and error responses.
 */
public class FaultInjectionDecorator implements DecoratingHttpServiceFunction {

    private final FaultInjectionRegistry registry;

    public FaultInjectionDecorator(FaultInjectionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req) throws Exception {
        String service = ServiceGatingDecorator.resolveService(ctx.path());
        if (service == null) {
            return delegate.serve(ctx, req);
        }

        FaultInjectionRegistry.FaultDecision decision =
                registry.evaluate(service, req.method().name(), ctx.path());
        if (!decision.matched()) {
            return delegate.serve(ctx, req);
        }

        FaultInjectionRegistry.FaultRule rule = decision.rule();
        if (rule.latencyMs() > 0) {
            try {
                Thread.sleep(rule.latencyMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE, MediaType.JSON,
                        "{\"error\":{\"status\":\"INTERRUPTED\",\"message\":\"Fault injection was interrupted\"}}");
            }
        }

        if (!rule.injectsResponse()) {
            return delegate.serve(ctx, req);
        }

        int statusCode = rule.statusCode();
        String statusText = httpStatusText(statusCode);
        String body = "{\"error\":{\"code\":" + statusCode
                + ",\"status\":\"" + escape(statusText)
                + "\",\"message\":\"" + escape(rule.message())
                + "\",\"details\":[{\"service\":\"" + escape(service)
                + "\",\"fault_id\":\"" + escape(rule.id())
                + "\",\"error_type\":\"" + escape(rule.errorType())
                + "\"}]}}";
        return HttpResponse.of(HttpStatus.valueOf(statusCode), MediaType.JSON, body);
    }

    private static String httpStatusText(int statusCode) {
        return Map.of(
                429, "RESOURCE_EXHAUSTED",
                500, "INTERNAL",
                502, "BAD_GATEWAY",
                503, "UNAVAILABLE",
                504, "DEADLINE_EXCEEDED"
        ).getOrDefault(statusCode, "INJECTED_FAULT");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
