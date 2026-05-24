package com.localcloud.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * IAM middleware that controls access to LocalCloud services.
 *
 * <p>Three modes (configured via {@code LOCALCLOUD_IAM_MODE} env var):
 * <ul>
 *   <li><b>permissive</b> (default): Accept all requests without credential checks.
 *   <li><b>strict</b>: Enforce role-based access from a local policy JSON file.
 *   <li><b>gcp-live</b>: Validate bearer tokens against Google's OAuth2 tokeninfo
 *       endpoint and check IAM permissions via the GCP IAM API.
 * </ul>
 *
 * <p>The middleware is registered as an Armeria server-level decorator so it
 * intercepts both REST and gRPC (HTTP/2) requests.
 */
public class IamMiddleware implements DecoratingHttpServiceFunction {

    private static final Logger logger = LoggerFactory.getLogger(IamMiddleware.class);

    private static final String GOOGLE_TOKENINFO_URL =
            "https://oauth2.googleapis.com/tokeninfo?access_token=";
    private static final String GOOGLE_IAM_API_URL =
            "https://iam.googleapis.com/v1/projects/%s/serviceAccounts/%s:testIamPermissions";

    private final String mode;
    private final LocalCloudConfig config;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    /** Local policy: maps identity (email or "*") to list of allowed service prefixes. */
    private final Map<String, List<String>> policyBindings;

    /** Cache validated tokens for 5 minutes to avoid repeated Google API calls. */
    private final ConcurrentHashMap<String, CachedIdentity> tokenCache = new ConcurrentHashMap<>();
    private static final long TOKEN_CACHE_TTL_MS = 5 * 60 * 1000;

    private record CachedIdentity(String email, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    public IamMiddleware(LocalCloudConfig config) {
        this.config = config;
        this.mode = config.getIamMode();
        this.mapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.policyBindings = loadLocalPolicies();

        logger.info("IAM middleware initialized in '{}' mode", mode);
        if ("strict".equals(mode) && policyBindings.isEmpty()) {
            logger.warn("IAM strict mode active but no policies loaded — all requests will be denied. "
                    + "Set LOCALCLOUD_IAM_POLICY_FILE to a policy JSON file.");
        }
    }

    @Override
    public HttpResponse serve(HttpService delegate, ServiceRequestContext ctx,
                              HttpRequest req) throws Exception {
        // Admin and developer-facing endpoints are always accessible regardless of IAM mode.
        if (isAdminPath(ctx.path())) {
            return delegate.serve(ctx, req);
        }

        return switch (mode) {
            case "permissive" -> {
                logger.debug("IAM permissive: {} {}", req.method(), ctx.path());
                yield delegate.serve(ctx, req);
            }
            case "strict" -> enforceLocalPolicy(delegate, ctx, req);
            case "gcp-live" -> enforceGcpPolicy(delegate, ctx, req);
            default -> {
                logger.warn("Unknown IAM mode '{}', falling back to permissive", mode);
                yield delegate.serve(ctx, req);
            }
        };
    }

    private static boolean isAdminPath(String path) {
        return path.equals("/")
                || path.startsWith("/icons")
                || isStaticAssetPath(path)
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
                || path.startsWith("/computeMetadata/v1");
    }

    private static boolean isStaticAssetPath(String path) {
        return path.endsWith(".js")
                || path.endsWith(".css")
                || path.endsWith(".svg")
                || path.endsWith(".html");
    }

    /**
     * Strict mode: extract identity from Authorization header and check against
     * locally configured policy bindings.
     */
    private HttpResponse enforceLocalPolicy(HttpService delegate, ServiceRequestContext ctx,
                                            HttpRequest req) throws Exception {
        String authHeader = req.headers().get("authorization");

        // Extract identity — accept "Bearer <email>" or "Bearer <token>" patterns
        String identity = extractIdentityFromHeader(authHeader);
        if (identity == null) {
            // Check for wildcard policy (allow anonymous)
            if (policyBindings.containsKey("*")) {
                List<String> allowed = policyBindings.get("*");
                if (isPathAllowed(ctx.path(), allowed)) {
                    logger.debug("IAM strict: anonymous access allowed via wildcard policy for {}", ctx.path());
                    return delegate.serve(ctx, req);
                }
            }
            return denyResponse("No credentials provided and no anonymous access policy configured.",
                    "anonymous", ctx.path(), Collections.emptyList());
        }

        // Check identity-specific policy
        List<String> allowedPrefixes = policyBindings.getOrDefault(identity,
                policyBindings.getOrDefault("*", Collections.emptyList()));

        if (isPathAllowed(ctx.path(), allowedPrefixes)) {
            logger.debug("IAM strict: {} authorized for {}", identity, ctx.path());
            return delegate.serve(ctx, req);
        }

        logger.info("IAM strict: {} denied access to {}", identity, ctx.path());
        return denyResponse("Identity '" + identity + "' does not have access to this resource.",
                identity, ctx.path(), allowedPrefixes);
    }

    /**
     * GCP-live mode: validate bearer token against Google's OAuth2 tokeninfo
     * endpoint, then check if the authenticated identity has permissions.
     */
    private HttpResponse enforceGcpPolicy(HttpService delegate, ServiceRequestContext ctx,
                                          HttpRequest req) throws Exception {
        String authHeader = req.headers().get("authorization");
        if (authHeader == null || !authHeader.toLowerCase().startsWith("bearer ")) {
            return denyResponse("GCP-live mode requires a valid Bearer token in the Authorization header.",
                    "anonymous", ctx.path(), Collections.emptyList());
        }

        String token = authHeader.substring(7).trim();

        // Check cache first
        CachedIdentity cached = tokenCache.get(token);
        if (cached != null && !cached.isExpired()) {
            logger.debug("IAM gcp-live: cached identity {} for {}", cached.email(), ctx.path());
            // Still enforce local policies if configured
            if (!policyBindings.isEmpty()) {
                List<String> allowed = policyBindings.getOrDefault(cached.email(),
                        policyBindings.getOrDefault("*", Collections.emptyList()));
                if (!isPathAllowed(ctx.path(), allowed)) {
                    return denyResponse("Identity '" + cached.email() + "' does not have access to this resource.",
                            cached.email(), ctx.path(), allowed);
                }
            }
            return delegate.serve(ctx, req);
        }

        // Validate token against Google tokeninfo
        try {
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(GOOGLE_TOKENINFO_URL + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            var response = httpClient.send(request, BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.info("IAM gcp-live: token validation failed (HTTP {})", response.statusCode());
                return denyResponse("Invalid or expired bearer token.", "unknown", ctx.path(), Collections.emptyList());
            }

            Map<String, Object> tokenInfo = mapper.readValue(
                    response.body(), new TypeReference<Map<String, Object>>() {});
            String email = (String) tokenInfo.get("email");
            if (email == null) {
                email = (String) tokenInfo.getOrDefault("sub", "unknown");
            }

            // Cache the validated identity
            tokenCache.put(token, new CachedIdentity(email,
                    System.currentTimeMillis() + TOKEN_CACHE_TTL_MS));

            // Evict expired entries periodically
            if (tokenCache.size() > 100) {
                tokenCache.entrySet().removeIf(e -> e.getValue().isExpired());
            }

            // Enforce local policies if configured
            if (!policyBindings.isEmpty()) {
                List<String> allowed = policyBindings.getOrDefault(email,
                        policyBindings.getOrDefault("*", Collections.emptyList()));
                if (!isPathAllowed(ctx.path(), allowed)) {
                    return denyResponse("Identity '" + email + "' does not have access to this resource.",
                            email, ctx.path(), allowed);
                }
            }

            logger.debug("IAM gcp-live: {} authenticated for {}", email, ctx.path());
            return delegate.serve(ctx, req);

        } catch (Exception e) {
            logger.warn("IAM gcp-live: token validation error: {}", e.getMessage());
            return denyResponse("Failed to validate credentials: " + e.getMessage(),
                    "unknown", ctx.path(), Collections.emptyList());
        }
    }

    /**
     * Extract an identity string from the Authorization header.
     * Supports: "Bearer &lt;email&gt;", "Bearer &lt;token&gt;" (returns token as identity),
     * or "Basic &lt;base64&gt;" (decodes username).
     */
    private String extractIdentityFromHeader(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        if (authHeader.toLowerCase().startsWith("bearer ")) {
            return authHeader.substring(7).trim();
        }
        if (authHeader.toLowerCase().startsWith("basic ")) {
            try {
                String decoded = new String(java.util.Base64.getDecoder()
                        .decode(authHeader.substring(6).trim()));
                int colon = decoded.indexOf(':');
                return colon > 0 ? decoded.substring(0, colon) : decoded;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Check if a request path is allowed by the given list of allowed service prefixes.
     * Prefixes match service path roots (e.g., "/storage/v1", "/bigquery/v2", "grpc").
     * A prefix of "*" or "all" grants access to everything.
     */
    private boolean isPathAllowed(String path, List<String> allowedPrefixes) {
        if (allowedPrefixes == null || allowedPrefixes.isEmpty()) {
            return false;
        }
        for (String prefix : allowedPrefixes) {
            if ("*".equals(prefix) || "all".equals(prefix)) {
                return true;
            }
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Load local IAM policies from a JSON file specified by LOCALCLOUD_IAM_POLICY_FILE.
     *
     * <p>Expected format:
     * <pre>{@code
     * {
     *   "bindings": [
     *     {"identity": "*", "services": ["*"]},
     *     {"identity": "dev@example.com", "services": ["/storage/v1", "/bigquery/v2"]},
     *     {"identity": "admin@example.com", "services": ["*"]}
     *   ]
     * }
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<String>> loadLocalPolicies() {
        String policyPath = config.getIamPolicyFile();
        if (policyPath == null || policyPath.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            Path path = Path.of(policyPath);
            if (!Files.exists(path)) {
                logger.warn("IAM policy file not found: {}", policyPath);
                return Collections.emptyMap();
            }

            String json = Files.readString(path);
            Map<String, Object> policy = mapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});

            List<Map<String, Object>> bindings =
                    (List<Map<String, Object>>) policy.getOrDefault("bindings", Collections.emptyList());

            Map<String, List<String>> result = new ConcurrentHashMap<>();
            for (Map<String, Object> binding : bindings) {
                String identity = (String) binding.get("identity");
                List<String> services = (List<String>) binding.get("services");
                if (identity != null && services != null) {
                    result.put(identity, services);
                }
            }

            logger.info("Loaded {} IAM policy bindings from {}", result.size(), policyPath);
            return result;

        } catch (Exception e) {
            logger.error("Failed to load IAM policies from {}: {}", policyPath, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Build a 403 Forbidden JSON response matching GCP error format.
     */
    private HttpResponse denyResponse(String message) {
        return denyResponse(message, "unknown", "unknown", Collections.emptyList());
    }

    private HttpResponse denyResponse(String message, String principal, String path, List<String> allowedPrefixes) {
        String service = ServiceGatingDecorator.resolveService(path);
        if (service == null) {
            service = "unknown";
        }
        String missingPermission = service + ".local.access";
        String suggestedRole = "roles/localcloud." + service + "Developer";
        String suggestedBinding = "{\"identity\":\"" + escape(principal) + "\",\"services\":[\"" + escape(suggestedPolicyPrefix(path)) + "\"]}";
        String body = """
                {
                  "error": {
                    "code": 403,
                    "message": "%s",
                    "status": "PERMISSION_DENIED",
                    "details": [
                      {
                        "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                        "reason": "IAM_PERMISSION_DENIED",
                        "domain": "localcloud",
                        "metadata": {
                          "iam_mode": "%s",
                          "principal": "%s",
                          "service": "%s",
                          "operation": "%s",
                          "resource": "%s",
                          "missing_permission": "%s",
                          "suggested_role": "%s",
                          "suggested_binding": "%s",
                          "allowed_prefixes": "%s"
                        }
                      }
                    ]
                  }
                }
                """.formatted(escape(message), escape(mode), escape(principal), escape(service),
                        escape(path), escape(path), escape(missingPermission), escape(suggestedRole),
                        escape(suggestedBinding), escape(String.valueOf(allowedPrefixes)));

        return HttpResponse.of(HttpStatus.FORBIDDEN, MediaType.JSON, body);
    }

    private static String suggestedPolicyPrefix(String path) {
        if (path == null || path.isBlank() || "unknown".equals(path)) {
            return "*";
        }
        if (path.startsWith("/storage/")) {
            return "/storage/";
        }
        if (path.startsWith("/bigquery/")) {
            return "/bigquery/";
        }
        if (path.startsWith("/compute/")) {
            return "/compute/";
        }
        if (path.startsWith("/v1/")) {
            return "/v1/";
        }
        if (path.startsWith("/v2/")) {
            return "/v2/";
        }
        int secondSlash = path.indexOf('/', 1);
        return secondSlash > 0 ? path.substring(0, secondSlash + 1) : path;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Get the current IAM mode.
     */
    public String getMode() {
        return mode;
    }

    /**
     * Close the underlying HTTP client to release resources.
     */
    public void close() {
        httpClient.close();
    }
}
