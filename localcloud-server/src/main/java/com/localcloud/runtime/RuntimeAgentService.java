package com.localcloud.runtime;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.common.RestResponseHelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Authenticated control API; host agents make outbound requests only. */
public final class RuntimeAgentService {
    private final AgentRuntimeProvider provider;
    private final byte[] expectedToken;

    public RuntimeAgentService(AgentRuntimeProvider provider, String token) {
        this.provider = provider;
        if (token == null || token.length() < 16) throw new IllegalArgumentException("runtime agent token must contain at least 16 characters");
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @Post("/runtime/agent/register")
    public HttpResponse register(ServiceRequestContext context, AggregatedHttpRequest request) {
        if (!authenticated(context)) return RestResponseHelper.error(401, "Invalid runtime agent token");
        try {
            RuntimeAgentProtocol.Registration registration = RestResponseHelper.MAPPER.readValue(
                    request.contentUtf8(), RuntimeAgentProtocol.Registration.class);
            return json(HttpStatus.OK, provider.register(registration));
        } catch (Exception e) {
            return RestResponseHelper.error(400, e.getMessage());
        }
    }

    @Post("/runtime/agent/poll")
    public HttpResponse poll(ServiceRequestContext context, AggregatedHttpRequest request) {
        if (!authenticated(context)) return RestResponseHelper.error(401, "Invalid runtime agent token");
        try {
            RuntimeAgentProtocol.Poll poll = RestResponseHelper.MAPPER.readValue(request.contentUtf8(), RuntimeAgentProtocol.Poll.class);
            RuntimeAgentProtocol.WorkItem item = provider.poll(poll);
            return item == null ? HttpResponse.of(HttpStatus.NO_CONTENT) : json(HttpStatus.OK, item);
        } catch (Exception e) {
            return RestResponseHelper.error(400, e.getMessage());
        }
    }

    @Post("/runtime/agent/events")
    public HttpResponse event(ServiceRequestContext context, AggregatedHttpRequest request) {
        if (!authenticated(context)) return RestResponseHelper.error(401, "Invalid runtime agent token");
        try {
            provider.acceptEvent(RestResponseHelper.MAPPER.readValue(request.contentUtf8(), WorkloadResult.class));
            return json(HttpStatus.OK, java.util.Map.of("accepted", true));
        } catch (Exception e) {
            return RestResponseHelper.error(400, e.getMessage());
        }
    }

    @Get("/runtime/agent/commands")
    public HttpResponse commands(ServiceRequestContext context, @Param String agentId) {
        if (!authenticated(context)) return RestResponseHelper.error(401, "Invalid runtime agent token");
        try {
            return json(HttpStatus.OK, provider.commands(agentId));
        } catch (Exception e) {
            return RestResponseHelper.error(400, e.getMessage());
        }
    }

    private boolean authenticated(ServiceRequestContext context) {
        String header = context.request().headers().get("authorization");
        if (header == null || !header.startsWith("Bearer ")) return false;
        return MessageDigest.isEqual(expectedToken, header.substring(7).getBytes(StandardCharsets.UTF_8));
    }

    private static HttpResponse json(HttpStatus status, Object value) {
        try {
            return HttpResponse.of(status, MediaType.JSON, RestResponseHelper.MAPPER.writeValueAsString(value));
        } catch (Exception e) {
            return RestResponseHelper.error(500, "Serialization failed: " + e.getMessage());
        }
    }
}
