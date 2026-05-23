package com.localcloud.gateway;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.localcloud.config.LocalCloudConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link IamMiddleware}.
 *
 * <p>Uses Mockito mocks for {@link LocalCloudConfig}, {@link HttpService},
 * {@link ServiceRequestContext}, and {@link HttpRequest}. These tests verify
 * IAM decision logic without any network calls or database access.
 *
 * <p>The IamMiddleware constructor reads {@code config.getIamMode()} and
 * {@code config.getIamPolicyFile()} to initialise its mode and load policies.
 * By returning an empty string for the policy file, we avoid filesystem access.
 */
@ExtendWith(MockitoExtension.class)
class IamMiddlewareTest {

    @Mock private HttpService delegate;
    @Mock private ServiceRequestContext ctx;
    @Mock private HttpRequest req;

    private IamMiddleware middleware;

    @AfterEach
    void tearDown() {
        if (middleware != null) {
            middleware.close();
        }
    }

    /**
     * Create a mock {@link LocalCloudConfig} with the given IAM mode and empty policy file.
     */
    private LocalCloudConfig mockConfig(String iamMode) {
        LocalCloudConfig config = mock(LocalCloudConfig.class);
        when(config.getIamMode()).thenReturn(iamMode);
        when(config.getIamPolicyFile()).thenReturn("");
        return config;
    }

    /**
     * Set up the mock context and request for a given path.
     */
    private void setupRequest(String path) {
        when(ctx.path()).thenReturn(path);
        lenient().when(req.method()).thenReturn(HttpMethod.GET);
    }

    // -----------------------------------------------------------------------
    // Permissive mode
    // -----------------------------------------------------------------------

    @Test
    void permissiveModeAllowsAllRequests() throws Exception {
        middleware = new IamMiddleware(mockConfig("permissive"));

        setupRequest("/storage/v1/b/my-bucket/o");
        HttpResponse expected = HttpResponse.of(HttpStatus.OK);
        when(delegate.serve(ctx, req)).thenReturn(expected);

        HttpResponse result = middleware.serve(delegate, ctx, req);

        assertSame(expected, result);
        verify(delegate).serve(ctx, req);
    }

    @Test
    void permissiveModeAllowsRequestsWithNoAuth() throws Exception {
        middleware = new IamMiddleware(mockConfig("permissive"));

        setupRequest("/bigquery/v2/projects/p/datasets");
        HttpResponse expected = HttpResponse.of(HttpStatus.OK);
        when(delegate.serve(ctx, req)).thenReturn(expected);

        HttpResponse result = middleware.serve(delegate, ctx, req);

        assertSame(expected, result);
    }

    @Test
    void permissiveModeGetMode() {
        middleware = new IamMiddleware(mockConfig("permissive"));
        assertEquals("permissive", middleware.getMode());
    }

    // -----------------------------------------------------------------------
    // Admin endpoint bypass
    // -----------------------------------------------------------------------

    @Test
    void adminEndpointBypassesIamInPermissiveMode() throws Exception {
        middleware = new IamMiddleware(mockConfig("permissive"));

        setupRequest("/_localcloud/status");
        HttpResponse expected = HttpResponse.of(HttpStatus.OK);
        when(delegate.serve(ctx, req)).thenReturn(expected);

        HttpResponse result = middleware.serve(delegate, ctx, req);

        assertSame(expected, result);
    }

    @Test
    void adminEndpointBypassesIamInStrictMode() throws Exception {
        middleware = new IamMiddleware(mockConfig("strict"));

        setupRequest("/_localcloud/reset");
        HttpResponse expected = HttpResponse.of(HttpStatus.OK);
        when(delegate.serve(ctx, req)).thenReturn(expected);

        HttpResponse result = middleware.serve(delegate, ctx, req);

        // Admin endpoints should always pass through, even in strict mode
        assertSame(expected, result);
        verify(delegate).serve(ctx, req);
    }

    @Test
    void rootLevelHealthEndpointBypassesIamInStrictMode() throws Exception {
        middleware = new IamMiddleware(mockConfig("strict"));

        setupRequest("/health");
        HttpResponse expected = HttpResponse.of(HttpStatus.OK);
        when(delegate.serve(ctx, req)).thenReturn(expected);

        HttpResponse result = middleware.serve(delegate, ctx, req);

        assertSame(expected, result);
        verify(delegate).serve(ctx, req);
    }

    @Test
    void rootLevelHealthNestedEndpointBypassesIam() throws Exception {
        middleware = new IamMiddleware(mockConfig("strict"));

        setupRequest("/health/localcloud-server");
        HttpResponse expected = HttpResponse.of(HttpStatus.OK);
        when(delegate.serve(ctx, req)).thenReturn(expected);

        HttpResponse result = middleware.serve(delegate, ctx, req);

        assertSame(expected, result);
    }

    @Test
    void rootLevelServicesEndpointBypassesIam() throws Exception {
        middleware = new IamMiddleware(mockConfig("strict"));

        setupRequest("/services");
        HttpResponse expected = HttpResponse.of(HttpStatus.OK);
        when(delegate.serve(ctx, req)).thenReturn(expected);

        HttpResponse result = middleware.serve(delegate, ctx, req);

        assertSame(expected, result);
    }

    @Test
    void rootLevelUsageEndpointBypassesIam() throws Exception {
        middleware = new IamMiddleware(mockConfig("strict"));

        setupRequest("/usage");
        HttpResponse expected = HttpResponse.of(HttpStatus.OK);
        when(delegate.serve(ctx, req)).thenReturn(expected);

        HttpResponse result = middleware.serve(delegate, ctx, req);

        assertSame(expected, result);
    }

    @Test
    void rootLevelExportEndpointBypassesIam() throws Exception {
        middleware = new IamMiddleware(mockConfig("strict"));

        setupRequest("/export?format=shell");
        HttpResponse expected = HttpResponse.of(HttpStatus.OK);
        when(delegate.serve(ctx, req)).thenReturn(expected);

        HttpResponse result = middleware.serve(delegate, ctx, req);

        assertSame(expected, result);
    }

    @Test
    void adminEndpointNestedPathBypassesIam() throws Exception {
        middleware = new IamMiddleware(mockConfig("strict"));

        setupRequest("/_localcloud/services/gcs/config");
        HttpResponse expected = HttpResponse.of(HttpStatus.OK);
        when(delegate.serve(ctx, req)).thenReturn(expected);

        HttpResponse result = middleware.serve(delegate, ctx, req);

        assertSame(expected, result);
    }

    // -----------------------------------------------------------------------
    // Strict mode with no policies
    // -----------------------------------------------------------------------

    @Test
    void strictModeWithNoPoliciesDeniesNonAdminRequests() throws Exception {
        middleware = new IamMiddleware(mockConfig("strict"));

        setupRequest("/storage/v1/b/my-bucket/o");
        // Simulate no Authorization header
        when(req.headers()).thenReturn(RequestHeaders.of(HttpMethod.GET, "/storage/v1/b/my-bucket/o"));

        HttpResponse result = middleware.serve(delegate, ctx, req);

        // Should return a 403 response, not delegate
        assertNotNull(result);
        verify(delegate, never()).serve(ctx, req);
    }

    @Test
    void strictModeGetMode() {
        middleware = new IamMiddleware(mockConfig("strict"));
        assertEquals("strict", middleware.getMode());
    }

    // -----------------------------------------------------------------------
    // Unknown IAM mode
    // -----------------------------------------------------------------------

    @Test
    void unknownIamModeFallsBackToPermissive() throws Exception {
        middleware = new IamMiddleware(mockConfig("invalid-mode"));

        setupRequest("/storage/v1/b/my-bucket/o");
        HttpResponse expected = HttpResponse.of(HttpStatus.OK);
        when(delegate.serve(ctx, req)).thenReturn(expected);

        HttpResponse result = middleware.serve(delegate, ctx, req);

        // Unknown mode should fall through to permissive behavior
        assertSame(expected, result);
        verify(delegate).serve(ctx, req);
    }

    @Test
    void unknownModeStillReportsItsMode() {
        middleware = new IamMiddleware(mockConfig("custom-unknown"));
        assertEquals("custom-unknown", middleware.getMode());
    }

    // -----------------------------------------------------------------------
    // Strict mode with Bearer token (no matching policy)
    // -----------------------------------------------------------------------

    @Test
    void strictModeWithBearerTokenButNoPolicyDenies() throws Exception {
        middleware = new IamMiddleware(mockConfig("strict"));

        setupRequest("/storage/v1/b/my-bucket/o");
        when(req.headers()).thenReturn(
                RequestHeaders.of(HttpMethod.GET, "/storage/v1/b/my-bucket/o",
                        "authorization", "Bearer user@example.com"));

        HttpResponse result = middleware.serve(delegate, ctx, req);

        // No policies loaded, so identity has no allowed prefixes -> deny
        assertNotNull(result);
        verify(delegate, never()).serve(ctx, req);
    }

    // -----------------------------------------------------------------------
    // Empty policy file path
    // -----------------------------------------------------------------------

    @Test
    void emptyPolicyFileResultsInNoBindings() {
        LocalCloudConfig config = mockConfig("strict");
        middleware = new IamMiddleware(config);
        // Constructor should not throw with empty policy file path
        assertEquals("strict", middleware.getMode());
    }

    @Test
    void nullPolicyFileResultsInNoBindings() {
        LocalCloudConfig config = mock(LocalCloudConfig.class);
        when(config.getIamMode()).thenReturn("strict");
        when(config.getIamPolicyFile()).thenReturn(null);

        middleware = new IamMiddleware(config);
        assertEquals("strict", middleware.getMode());
    }

    @Test
    void nonexistentPolicyFileResultsInNoBindings() {
        LocalCloudConfig config = mock(LocalCloudConfig.class);
        when(config.getIamMode()).thenReturn("strict");
        when(config.getIamPolicyFile()).thenReturn("/nonexistent/path/policy.json");

        middleware = new IamMiddleware(config);
        assertEquals("strict", middleware.getMode());
    }
}
