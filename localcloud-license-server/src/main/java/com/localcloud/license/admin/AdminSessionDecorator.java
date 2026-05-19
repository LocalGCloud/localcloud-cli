package com.localcloud.license.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.*;
import com.linecorp.armeria.server.DecoratingHttpServiceFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import java.util.Map;

public class AdminSessionDecorator implements DecoratingHttpServiceFunction {

    private final AdminSessionStore sessionStore;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdminSessionDecorator(AdminSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @Override
    public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx, HttpRequest req) throws Exception {
        if (ctx.path().equals("/admin/api/login")) {
            return delegate.serve(ctx, req);
        }
        String auth = req.headers().get(HttpHeaderNames.AUTHORIZATION);
        String token = null;
        if (auth != null && auth.startsWith("Bearer ")) {
            token = auth.substring(7).strip();
        }
        if (token == null || !sessionStore.validateSession(token)) {
            return HttpResponse.of(HttpStatus.UNAUTHORIZED, MediaType.JSON_UTF_8,
                mapper.writeValueAsString(Map.of("error", "Admin authentication required")));
        }
        return delegate.serve(ctx, req);
    }
}
