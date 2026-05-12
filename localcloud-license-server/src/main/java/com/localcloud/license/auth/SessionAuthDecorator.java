package com.localcloud.license.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.*;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import io.netty.util.AttributeKey;

import java.util.Map;
import java.util.UUID;

public class SessionAuthDecorator implements DecoratingHttpServiceFunction {
    public static final AttributeKey<UUID> USER_ID_KEY =
        AttributeKey.valueOf(SessionAuthDecorator.class, "USER_ID");

    private final SessionRepository sessionRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public SessionAuthDecorator(SessionRepository sessionRepo) {
        this.sessionRepo = sessionRepo;
    }

    @Override
    public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req) throws Exception {
        String auth = req.headers().get(HttpHeaderNames.AUTHORIZATION);
        String token = null;
        if (auth != null && auth.startsWith("Bearer ")) {
            token = auth.substring(7).strip();
        }

        UUID userId;
        try {
            userId = sessionRepo.validateSession(token);
        } catch (Exception e) {
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Session validation failed");
        }

        if (userId == null) {
            return errorResponse(HttpStatus.UNAUTHORIZED,
                "Authentication required — provide Authorization: Bearer <session_token>");
        }

        ctx.setAttr(USER_ID_KEY, userId);
        return delegate.serve(ctx, req);
    }

    private HttpResponse errorResponse(HttpStatus status, String message) {
        try {
            return HttpResponse.of(status, MediaType.JSON_UTF_8,
                mapper.writeValueAsString(Map.of("error", message)));
        } catch (Exception e) {
            return HttpResponse.of(status);
        }
    }
}
