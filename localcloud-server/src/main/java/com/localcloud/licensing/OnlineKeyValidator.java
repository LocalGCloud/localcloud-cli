package com.localcloud.licensing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Validates online license keys (lco_ prefix) against the license server.
 * When the license server URL is "none", operates in bypass mode (all keys accepted as PRO).
 */
public class OnlineKeyValidator {

    private static final Logger logger = LoggerFactory.getLogger(OnlineKeyValidator.class);
    private static final String PREFIX = "lco_";

    private final String licenseServerUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    public OnlineKeyValidator(String licenseServerUrl) {
        this.licenseServerUrl = licenseServerUrl != null ? licenseServerUrl : "none";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public LicenseResult validate(String key, String deviceId) {
        if (key == null || !key.startsWith(PREFIX)) {
            return LicenseResult.invalid("Invalid key prefix — expected 'lco_'");
        }

        String keyBody = key.substring(PREFIX.length());
        if (keyBody.isBlank()) {
            return LicenseResult.invalid("Empty key value after prefix");
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
                String tier = json.path("tier").asText("community");
                String email = json.path("email").asText("unknown");
                long expires = json.path("expires").asLong(0);
                return LicenseResult.valid(LicenseTier.fromString(tier), email, deviceId, expires);
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
}
