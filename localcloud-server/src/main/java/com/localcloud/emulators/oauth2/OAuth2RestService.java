package com.localcloud.emulators.oauth2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal OAuth2 token endpoint facade.
 * <p>
 * Google Cloud client libraries call {@code https://oauth2.googleapis.com/token}
 * to exchange service account JWTs for access tokens. This facade returns a
 * valid-looking token response so the library proceeds without leaving LocalCloud.
 * <p>
 * Also handles {@code /tokeninfo} for token validation (always returns valid).
 */
public class OAuth2RestService {

    private static final Logger logger = LoggerFactory.getLogger(OAuth2RestService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Token endpoint — returns a fake access token.
     * Maps to Google OAuth2 POST /token.
     * Route registered manually in LocalCloudApplication. No Armeria annotations needed.
     */
    public HttpResponse token(String body) {
        try {
            ObjectNode result = mapper.createObjectNode();
            result.put("access_token", "ya29.localcloud-" + System.currentTimeMillis());
            result.put("expires_in", 3600);
            result.put("token_type", "Bearer");
            result.put("scope", "https://www.googleapis.com/auth/cloud-platform");
            result.put("id_token", "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJsb2NhbGNsb3VkIiwiaXNzIjoibG9jYWxjbG91ZCIsImF1ZCI6ImxvY2FsY2xvdWQiLCJleHAiOjk5OTk5OTk5OTl9.fake-signature");

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error in token endpoint", e);
            return errorResponse("Internal error");
        }
    }

    /**
     * Token info endpoint — always returns valid.
     * Maps to Google OAuth2 GET /tokeninfo?access_token=...
     */
    public HttpResponse tokenInfo() {
        try {
            ObjectNode result = mapper.createObjectNode();
            result.put("azp", "localcloud");
            result.put("aud", "localcloud");
            result.put("scope", "https://www.googleapis.com/auth/cloud-platform");
            result.put("exp", String.valueOf(System.currentTimeMillis() / 1000 + 3600));
            result.put("expires_in", "3600");
            result.put("email", "localcloud@localcloud.iam.gserviceaccount.com");
            result.put("email_verified", "true");
            result.put("access_type", "offline");

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error in tokeninfo", e);
            return errorResponse("Internal error");
        }
    }

    /**
     * OIDC userinfo endpoint — returns a fake user profile.
     * Maps to Google OpenID Connect GET /v1/userinfo
     */
    public HttpResponse userInfo() {
        try {
            ObjectNode result = mapper.createObjectNode();
            result.put("sub", "localcloud");
            result.put("name", "LocalCloud Service Account");
            result.put("given_name", "LocalCloud");
            result.put("family_name", "Service Account");
            result.put("picture", "https://localhost/picture.jpg");
            result.put("email", "localcloud@localcloud.iam.gserviceaccount.com");
            result.put("email_verified", true);
            result.put("locale", "en");
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error in userinfo", e);
            return errorResponse("Internal error");
        }
    }

    /**
     * OAuth2 JWKS endpoint — returns a minimal key set.
     * Maps to Google OAuth2 GET /oauth2/v3/certs
     */
    public HttpResponse certs() {
        try {
            ObjectNode result = mapper.createObjectNode();
            result.putArray("keys");
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error in certs", e);
            return errorResponse("Internal error");
        }
    }

    private HttpResponse errorResponse(String message) {
        try {
            ObjectNode error = mapper.createObjectNode();
            ObjectNode details = mapper.createObjectNode();
            details.put("code", 500);
            details.put("message", message);
            error.set("error", details);
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                    mapper.writeValueAsString(error));
        } catch (Exception e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8, message);
        }
    }
}
