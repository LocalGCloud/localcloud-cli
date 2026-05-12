package com.localcloud.license.auth;

import com.linecorp.armeria.common.*;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionAuthDecoratorTest {

    @Mock
    private SessionRepository sessionRepo;

    @Mock
    private HttpService delegate;

    @Mock
    private ServiceRequestContext ctx;

    private SessionAuthDecorator decorator;

    @BeforeEach
    void setUp() {
        decorator = new SessionAuthDecorator(sessionRepo);
    }

    private HttpRequest requestWithAuth(String authHeader) {
        RequestHeaders headers;
        if (authHeader != null) {
            headers = RequestHeaders.of(HttpMethod.GET, "/keys/list",
                HttpHeaderNames.AUTHORIZATION, authHeader);
        } else {
            headers = RequestHeaders.of(HttpMethod.GET, "/keys/list");
        }
        return HttpRequest.of(headers);
    }

    @Test
    void missingAuthHeader_returns401() throws Exception {
        HttpRequest req = requestWithAuth(null);
        HttpResponse response = decorator.serve(delegate, ctx, req);
        AggregatedHttpResponse agg = response.aggregate().join();
        assertEquals(HttpStatus.UNAUTHORIZED, agg.status());
        assertTrue(agg.contentUtf8().contains("Authentication required"));
        verifyNoInteractions(delegate);
    }

    @Test
    void invalidToken_returns401() throws Exception {
        when(sessionRepo.validateSession(any())).thenReturn(null);
        HttpRequest req = requestWithAuth("Bearer bogus-token");
        HttpResponse response = decorator.serve(delegate, ctx, req);
        AggregatedHttpResponse agg = response.aggregate().join();
        assertEquals(HttpStatus.UNAUTHORIZED, agg.status());
        verifyNoInteractions(delegate);
    }

    @Test
    void validToken_setsUserIdAttributeAndDelegates() throws Exception {
        UUID expectedUserId = UUID.randomUUID();
        when(sessionRepo.validateSession("valid-token")).thenReturn(expectedUserId);
        when(delegate.serve(eq(ctx), any())).thenReturn(HttpResponse.of(HttpStatus.OK));

        HttpRequest req = requestWithAuth("Bearer valid-token");
        HttpResponse response = decorator.serve(delegate, ctx, req);

        ArgumentCaptor<UUID> captor = ArgumentCaptor.forClass(UUID.class);
        verify(ctx).setAttr(eq(SessionAuthDecorator.USER_ID_KEY), captor.capture());
        assertEquals(expectedUserId, captor.getValue());

        verify(delegate).serve(eq(ctx), any());
        AggregatedHttpResponse agg = response.aggregate().join();
        assertEquals(HttpStatus.OK, agg.status());
    }

    @Test
    void sessionRepoException_returns500() throws Exception {
        when(sessionRepo.validateSession(any())).thenThrow(new RuntimeException("DB down"));
        HttpRequest req = requestWithAuth("Bearer some-token");
        HttpResponse response = decorator.serve(delegate, ctx, req);
        AggregatedHttpResponse agg = response.aggregate().join();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, agg.status());
        verifyNoInteractions(delegate);
    }
}
