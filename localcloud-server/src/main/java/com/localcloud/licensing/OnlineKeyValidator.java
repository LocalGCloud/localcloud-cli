package com.localcloud.licensing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;

/**
 * Validates online license keys (lco_ prefix) against the license server.
 * When the license server URL is "none", operates in bypass mode (all keys accepted as PRO).
 *
 * Responses from the license server are RS256-signed JWTs. The public key is either:
 * 1. LOCALCLOUD_LICENSE_PUBLIC_KEY env var (base64-encoded DER X.509)
 * 2. Fetched from licenseServerUrl + "/license/public-key" on first use (cached)
 */
public class OnlineKeyValidator {

    private static final Logger logger = LoggerFactory.getLogger(OnlineKeyValidator.class);
    private static final String PREFIX = "lco_";

    private final String licenseServerUrl;
    private final boolean insecureUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    /** Cached public key — fetched lazily from /license/public-key on first successful validation. */
    private volatile PublicKey cachedPublicKey;

    public OnlineKeyValidator(String licenseServerUrl) {
        this.licenseServerUrl = licenseServerUrl != null ? licenseServerUrl : "none";
        this.insecureUrl = isInsecureUrl(this.licenseServerUrl);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static boolean isInsecureUrl(String url) {
        if ("none".equalsIgnoreCase(url)) return false;
        if (!url.startsWith("http://")) return false;
        // localhost and 127.x are OK for development
        return !url.contains("localhost") && !url.contains("127.0.0.1");
    }

    public LicenseResult validate(String key, String deviceId) {
        if (key == null || !key.startsWith(PREFIX)) {
            return LicenseResult.invalid("Invalid key prefix — expected 'lco_'");
        }

        String keyBody = key.substring(PREFIX.length());
        if (keyBody.isBlank()) {
            return LicenseResult.invalid("Empty key value after prefix");
        }

        if (insecureUrl) {
            logger.error("SECURITY: License server URL uses HTTP (not HTTPS): {}. " +
                    "License keys will be transmitted in plaintext.", licenseServerUrl);
            return LicenseResult.invalid(
                "License server URL must use HTTPS for security. " +
                "Current URL is insecure: " + licenseServerUrl);
        }

        // Bypass mode: no license server configured
        if ("none".equalsIgnoreCase(licenseServerUrl)) {
            logger.info("License server bypass mode — accepting key as PRO");
            return LicenseResult.valid(LicenseTier.PRO, "bypass@localcloud.dev", deviceId,
                    Long.MAX_VALUE);
        }

        // Call license server
        try {
            String body = mapper.writeValueAsString(
                    java.util.Map.of("key", key, "device_id", deviceId != null ? deviceId : ""));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(licenseServerUrl + "/license/validate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = mapper.readTree(response.body());
                String jwtToken = json.path("token").asText();

                PublicKey publicKey = getPublicKey();
                if (publicKey == null) {
                    return LicenseResult.invalid("Cannot verify license token — public key not available");
                }

                try {
                    var claims = Jwts.parser()
                        .verifyWith(publicKey)
                        .requireIssuer("localcloud-license")
                        .build()
                        .parseSignedClaims(jwtToken)
                        .getPayload();

                    String tier = claims.get("tier", String.class);
                    String email = claims.getSubject();
                    long expires = claims.getExpiration().toInstant().getEpochSecond();
                    return LicenseResult.valid(LicenseTier.fromString(tier), email, deviceId, expires);
                } catch (Exception e) {
                    logger.warn("JWT verification failed: {}", e.getMessage());
                    return LicenseResult.invalid("License token signature verification failed");
                }
            } else if (response.statusCode() == 401 || response.statusCode() == 403) {
                JsonNode json = mapper.readTree(response.body());
                String msg = json.path("message").asText("Invalid or revoked key");
                return LicenseResult.invalid(msg);
            } else {
                return LicenseResult.invalid("License server returned HTTP " + response.statusCode());
            }

        } catch (java.net.ConnectException e) {
            return LicenseResult.invalid("License server unreachable at " + licenseServerUrl);
        } catch (Exception e) {
            logger.debug("Online key validation failed: {}", e.getMessage());
            return LicenseResult.invalid("License server unreachable — " + e.getMessage());
        }
    }

    /**
     * Returns the RS256 public key for JWT verification.
     * Priority:
     * 1. LOCALCLOUD_LICENSE_PUBLIC_KEY env var (base64 DER X.509)
     * 2. Fetched from licenseServerUrl + "/license/public-key" (cached after first fetch)
     * Returns null if neither source is available.
     */
    PublicKey getPublicKey() {
        // Check env var first
        String envKey = System.getenv("LOCALCLOUD_LICENSE_PUBLIC_KEY");
        if (envKey != null && !envKey.isBlank()) {
            try {
                byte[] der = Base64.getDecoder().decode(envKey.strip());
                KeyFactory kf = KeyFactory.getInstance("RSA");
                return kf.generatePublic(new X509EncodedKeySpec(der));
            } catch (Exception e) {
                logger.warn("Failed to parse LOCALCLOUD_LICENSE_PUBLIC_KEY: {}", e.getMessage());
            }
        }

        // Return cached key if already fetched
        if (cachedPublicKey != null) {
            return cachedPublicKey;
        }

        // Fetch from license server
        if (!"none".equalsIgnoreCase(licenseServerUrl)) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(licenseServerUrl + "/license/public-key"))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    JsonNode json = mapper.readTree(resp.body());
                    String keyB64 = json.path("key").asText();
                    byte[] der = Base64.getDecoder().decode(keyB64);
                    KeyFactory kf = KeyFactory.getInstance("RSA");
                    cachedPublicKey = kf.generatePublic(new X509EncodedKeySpec(der));
                    logger.info("Fetched and cached license server public key from {}/license/public-key",
                            licenseServerUrl);
                    return cachedPublicKey;
                }
            } catch (Exception e) {
                logger.warn("Failed to fetch public key from license server: {}", e.getMessage());
            }
        }

        return null;
    }
}
