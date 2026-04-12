package com.localcloud.admin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcloud.config.LocalCloudConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages GCP credential detection, validation, and distribution.
 * Supports two credential sources:
 * <ul>
 *   <li>{@code adc} - Application Default Credentials from gcloud auth</li>
 *   <li>{@code service-account} - Service account key JSON file</li>
 *   <li>{@code none} - No credentials (default, fully isolated)</li>
 * </ul>
 */
public class CredentialBroker {

    private static final Logger logger = LoggerFactory.getLogger(CredentialBroker.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String source;
    private final String credentialFilePath;
    private boolean valid;
    private String identity;
    private String project;
    private String error;

    public CredentialBroker(LocalCloudConfig config) {
        this.source = config.getGcpCredentialSource();

        String path = null;
        if ("adc".equals(source)) {
            path = config.getGcpCredentialAdcPath();
        } else if ("service-account".equals(source)) {
            path = config.getGcpCredentialSaKeyPath();
        }
        this.credentialFilePath = path;

        if (path != null) {
            validateCredentialFile(path);
        } else {
            this.valid = false;
            this.identity = null;
            this.project = null;
            if (!"none".equals(source)) {
                this.error = "Unknown credential source: " + source;
                logger.warn("Unknown GCP credential source: {}", source);
            }
        }
    }

    private void validateCredentialFile(String path) {
        try {
            Path filePath = Path.of(path);
            if (!Files.exists(filePath)) {
                this.valid = false;
                this.error = "Credential file not found: " + path;
                logger.warn("GCP credential file not found: {} — falling back to no credentials", path);
                return;
            }

            String content = Files.readString(filePath);
            JsonNode root = mapper.readTree(content);

            // Extract identity and project from credential file
            if (root.has("client_email")) {
                this.identity = root.get("client_email").asText();
            } else if (root.has("client_id")) {
                this.identity = root.get("client_id").asText();
            }

            if (root.has("project_id")) {
                this.project = root.get("project_id").asText();
            } else if (root.has("quota_project_id")) {
                this.project = root.get("quota_project_id").asText();
            }

            this.valid = true;
            this.error = null;
            logger.info("GCP credentials loaded: source={}, identity={}, project={}", source, identity, project);

        } catch (IOException e) {
            this.valid = false;
            this.error = "Failed to read credential file: " + e.getMessage();
            logger.warn("Failed to read GCP credential file: {}", path, e);
        }
    }

    /**
     * Get the credential status as a map suitable for JSON serialization.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("source", source);
        status.put("valid", valid);
        status.put("identity", identity);
        status.put("project", project);
        if (error != null) {
            status.put("error", error);
        }
        return status;
    }

    public String getSource() {
        return source;
    }

    public boolean isValid() {
        return valid;
    }

    public String getIdentity() {
        return identity;
    }

    public String getProject() {
        return project;
    }

    /**
     * Get the path to the credential file for mounting into containers.
     * Returns null if no credentials are configured or file doesn't exist.
     */
    public String getCredentialFilePath() {
        if (valid && credentialFilePath != null) {
            return credentialFilePath;
        }
        return null;
    }

    /**
     * Get an access token from the credential file.
     * <ul>
     *   <li>For ADC files ({@code type: authorized_user}), extracts the token directly from the JSON.</li>
     *   <li>For SA key files ({@code type: service_account}), returns null
     *       (token generation requires google-auth library which may not be available).</li>
     * </ul>
     *
     * @return the access token, or null if credentials are not valid or token cannot be extracted
     */
    public String getAccessToken() {
        if (!valid || credentialFilePath == null) {
            return null;
        }
        try {
            Path filePath = Path.of(credentialFilePath);
            if (!Files.exists(filePath)) {
                return null;
            }
            String content = Files.readString(filePath);
            JsonNode root = mapper.readTree(content);

            String type = root.has("type") ? root.get("type").asText() : null;

            if ("authorized_user".equals(type)) {
                // ADC files may contain an access_token directly
                if (root.has("access_token")) {
                    String token = root.get("access_token").asText();
                    if (token != null && !token.isBlank()) {
                        return token;
                    }
                }
                // Some ADC files only have refresh_token; we cannot refresh without google-auth
                logger.debug("ADC file has no access_token field; refresh not supported");
                return null;
            } else if ("service_account".equals(type)) {
                // SA key requires JWT signing + token exchange; not supported without google-auth
                logger.debug("Service account key detected; token generation not supported without google-auth library");
                return null;
            }

            return null;
        } catch (IOException e) {
            logger.warn("Failed to read credential file for access token: {}", e.getMessage());
            return null;
        }
    }
}
