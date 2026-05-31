package com.localcloud.emulators.cloudbilling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Put;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cloud Billing API REST facade.
 * <p>
 * Terraform and GCP client libraries validate that a billing account is linked
 * to the project before creating billable resources. This stub always returns
 * a linked billing account so resource creation proceeds without errors.
 * <p>
 * Registered at /v1 (shared with CRM v1 via same prefix; non-overlapping paths).
 */
public class CloudBillingRestService {

    private static final Logger logger = LoggerFactory.getLogger(CloudBillingRestService.class);
    private static final String FAKE_BILLING_ACCOUNT = "billingAccounts/000000-AAAAAA-BBBBBB";

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Get billing info for a project. Always returns enabled with a fake account.
     * Maps to: GET https://cloudbilling.googleapis.com/v1/projects/{projectId}/billingInfo
     */
    @Get("/projects/{projectId}/billingInfo")
    public HttpResponse getBillingInfo(@Param String projectId) {
        try {
            ObjectNode result = mapper.createObjectNode();
            result.put("name", "projects/" + projectId + "/billingInfo");
            result.put("projectId", projectId);
            result.put("billingAccountName", FAKE_BILLING_ACCOUNT);
            result.put("billingEnabled", true);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error getting billing info for {}", projectId, e);
            return errorResponse(500, "Internal error: " + e.getMessage());
        }
    }

    /**
     * Update billing info for a project. Echoes back the linked account.
     * Maps to: PUT https://cloudbilling.googleapis.com/v1/projects/{projectId}/billingInfo
     */
    @Put("/projects/{projectId}/billingInfo")
    public HttpResponse updateBillingInfo(@Param String projectId, String body) {
        try {
            ObjectNode result = mapper.createObjectNode();
            result.put("name", "projects/" + projectId + "/billingInfo");
            result.put("projectId", projectId);
            // Accept whatever billing account was sent, or use default
            String billingAccount = FAKE_BILLING_ACCOUNT;
            if (body != null && !body.isBlank()) {
                try {
                    var parsed = mapper.readTree(body);
                    if (parsed.has("billingAccountName")) {
                        billingAccount = parsed.get("billingAccountName").asText();
                    }
                } catch (Exception ignored) {
                }
            }
            result.put("billingAccountName", billingAccount);
            result.put("billingEnabled", true);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error updating billing info for {}", projectId, e);
            return errorResponse(500, "Internal error: " + e.getMessage());
        }
    }

    /**
     * List billing accounts. Returns a single fake account for all projects.
     * Maps to: GET https://cloudbilling.googleapis.com/v1/billingAccounts
     */
    @Get("/billingAccounts")
    public HttpResponse listBillingAccounts() {
        try {
            ObjectNode result = mapper.createObjectNode();
            ArrayNode accounts = result.putArray("billingAccounts");
            ObjectNode account = accounts.addObject();
            account.put("name", FAKE_BILLING_ACCOUNT);
            account.put("displayName", "LocalCloud Billing Account");
            account.put("open", true);
            account.put("masterBillingAccount", "");
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error listing billing accounts", e);
            return errorResponse(500, "Internal error: " + e.getMessage());
        }
    }

    /**
     * List project billing info. Maps to: GET /v1/billingAccounts/{name}/projects
     */
    @Get("/billingAccounts/{accountName}/projects")
    public HttpResponse listProjectBillingInfo(@Param String accountName) {
        try {
            ObjectNode result = mapper.createObjectNode();
            result.putArray("projectBillingInfo");
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error listing project billing info", e);
            return errorResponse(500, "Internal error: " + e.getMessage());
        }
    }

    private HttpResponse errorResponse(int code, String message) {
        try {
            ObjectNode error = mapper.createObjectNode();
            ObjectNode inner = mapper.createObjectNode();
            inner.put("code", code);
            inner.put("message", message);
            error.set("error", inner);
            return HttpResponse.of(HttpStatus.valueOf(code), MediaType.JSON, mapper.writeValueAsString(error));
        } catch (Exception e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8, message);
        }
    }
}
