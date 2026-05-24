package com.localcloud.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FaultInjectionDecoratorTest {

    @Mock private HttpService delegate;
    @Mock private ServiceRequestContext ctx;
    @Mock private HttpRequest req;

    @Test
    void configuredFaultShortCircuitsMatchingServiceRequest() throws Exception {
        FaultInjectionRegistry registry = new FaultInjectionRegistry();
        registry.add(Map.of(
                "id", "gcs-fault",
                "service", "gcs",
                "status_code", 503,
                "message", "forced failure"
        ));
        FaultInjectionDecorator decorator = new FaultInjectionDecorator(registry);

        when(ctx.path()).thenReturn("/storage/v1/b");
        when(req.method()).thenReturn(HttpMethod.GET);

        HttpResponse response = decorator.serve(delegate, ctx, req);
        var aggregated = response.aggregate().join();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, aggregated.status());
        assertTrue(aggregated.contentUtf8().contains("gcs-fault"));
        verify(delegate, never()).serve(ctx, req);
    }

    @Test
    void latencyOnlyFaultDelegatesAfterMatch() throws Exception {
        FaultInjectionRegistry registry = new FaultInjectionRegistry();
        registry.add(Map.of(
                "service", "gcs",
                "status_code", 0,
                "latency_ms", 1
        ));
        FaultInjectionDecorator decorator = new FaultInjectionDecorator(registry);

        HttpResponse expected = HttpResponse.of(HttpStatus.OK);
        when(ctx.path()).thenReturn("/storage/v1/b");
        when(req.method()).thenReturn(HttpMethod.GET);
        when(delegate.serve(ctx, req)).thenReturn(expected);

        HttpResponse response = decorator.serve(delegate, ctx, req);

        assertEquals(expected, response);
        verify(delegate).serve(ctx, req);
    }

    @Test
    void unknownServicePathDelegates() throws Exception {
        FaultInjectionRegistry registry = new FaultInjectionRegistry();
        registry.add(Map.of("service", "gcs", "status_code", 503));
        FaultInjectionDecorator decorator = new FaultInjectionDecorator(registry);

        HttpResponse expected = HttpResponse.of(HttpStatus.OK);
        when(ctx.path()).thenReturn("/health");
        when(delegate.serve(ctx, req)).thenReturn(expected);

        HttpResponse response = decorator.serve(delegate, ctx, req);

        assertEquals(expected, response);
        verify(delegate).serve(ctx, req);
    }
}
