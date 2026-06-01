package com.localcloud.emulators.cloudbilling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Put;
import com.localcloud.common.RestResponseHelper;
import com.localcloud.persistence.PostgresDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Cloud Billing API REST facade. Always returns a linked billing account.
 */
public class CloudBillingRestService {

    private static final Logger logger = LoggerFactory.getLogger(CloudBillingRestService.class);
    private static final String FAKE_BILLING_ACCOUNT = "billingAccounts/000000-AAAAAA-BBBBBB";
    private final PostgresDataSource dataSource;

    public CloudBillingRestService() {
        this.dataSource = null;
    }

    public CloudBillingRestService(PostgresDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Get("/projects/{projectId}/billingInfo")
    public HttpResponse getBillingInfo(@Param String projectId) {
        try {
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.put("name", "projects/" + projectId + "/billingInfo");
            result.put("projectId", projectId);
            result.put("billingAccountName", FAKE_BILLING_ACCOUNT);
            result.put("billingEnabled", true);
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            logger.error("Error getting billing info for {}", projectId, e);
            return RestResponseHelper.error(500, "Internal error: " + e.getMessage());
        }
    }

    @Put("/projects/{projectId}/billingInfo")
    public HttpResponse updateBillingInfo(@Param String projectId, String body) {
        try {
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.put("name", "projects/" + projectId + "/billingInfo");
            result.put("projectId", projectId);
            String billingAccount = FAKE_BILLING_ACCOUNT;
            if (body != null && !body.isBlank()) {
                try {
                    var parsed = RestResponseHelper.parseBody(body);
                    if (parsed.has("billingAccountName")) billingAccount = parsed.get("billingAccountName").asText();
                } catch (Exception ignored) {}
            }
            result.put("billingAccountName", billingAccount);
            result.put("billingEnabled", true);
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            logger.error("Error updating billing info for {}", projectId, e);
            return RestResponseHelper.error(500, "Internal error: " + e.getMessage());
        }
    }

    @Get("/billingAccounts")
    public HttpResponse listBillingAccounts() {
        try {
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            ArrayNode accounts = result.putArray("billingAccounts");
            ObjectNode account = accounts.addObject();
            account.put("name", FAKE_BILLING_ACCOUNT);
            account.put("displayName", "LocalCloud Billing Account");
            account.put("open", true);
            account.put("masterBillingAccount", "");
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            logger.error("Error listing billing accounts", e);
            return RestResponseHelper.error(500, "Internal error: " + e.getMessage());
        }
    }

    @Get("/billingAccounts/{accountName}/projects")
    public HttpResponse listProjectBillingInfo(@Param String accountName) {
        try {
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.putArray("projectBillingInfo");
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            logger.error("Error listing project billing info", e);
            return RestResponseHelper.error(500, "Internal error: " + e.getMessage());
        }
    }

    // ---- Budget CRUD ----

    @Post("/billingAccounts/{billingAccount}/budgets")
    public HttpResponse createBudget(@Param String billingAccount, String body) {
        if (dataSource == null) {
            return RestResponseHelper.error(500, "Database not available");
        }
        try {
            JsonNode root = body != null && !body.isBlank()
                    ? RestResponseHelper.parseBody(body) : RestResponseHelper.MAPPER.createObjectNode();

            String budgetId = UUID.randomUUID().toString();
            String displayName = root.has("displayName") ? root.get("displayName").asText() : budgetId;
            String amountJson = root.has("amount") ? root.get("amount").toString() : "{}";
            String thresholdRulesJson = root.has("thresholdRules") ? root.get("thresholdRules").toString() : "[]";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO billing_budgets (billing_account, budget_id, display_name, amount_json, threshold_rules_json) " +
                     "VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, billingAccount);
                ps.setString(2, budgetId);
                ps.setString(3, displayName);
                ps.setString(4, amountJson);
                ps.setString(5, thresholdRulesJson);
                ps.executeUpdate();
            }

            return RestResponseHelper.ok(budgetJson(billingAccount, budgetId));
        } catch (Exception e) {
            logger.error("Failed to create budget", e);
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Get("/billingAccounts/{billingAccount}/budgets")
    public HttpResponse listBudgets(@Param String billingAccount) {
        if (dataSource == null) {
            return RestResponseHelper.error(500, "Database not available");
        }
        try {
            ArrayNode budgets = RestResponseHelper.MAPPER.createArrayNode();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT budget_id FROM billing_budgets WHERE billing_account = ? ORDER BY budget_id")) {
                ps.setString(1, billingAccount);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        budgets.add(budgetJson(billingAccount, rs.getString("budget_id")));
                    }
                }
            }
            ObjectNode result = RestResponseHelper.MAPPER.createObjectNode();
            result.set("budgets", budgets);
            return RestResponseHelper.ok(result);
        } catch (Exception e) {
            logger.error("Failed to list budgets", e);
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Get("/billingAccounts/{billingAccount}/budgets/{budgetId}")
    public HttpResponse getBudget(@Param String billingAccount, @Param String budgetId) {
        if (dataSource == null) return RestResponseHelper.error(500, "Database not available");
        try {
            if (!budgetExists(billingAccount, budgetId))
                return RestResponseHelper.error(404, "Budget not found: " + budgetId);
            return RestResponseHelper.ok(budgetJson(billingAccount, budgetId));
        } catch (Exception e) {
            logger.error("Failed to get budget", e);
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    @Delete("/billingAccounts/{billingAccount}/budgets/{budgetId}")
    public HttpResponse deleteBudget(@Param String billingAccount, @Param String budgetId) {
        if (dataSource == null) return RestResponseHelper.error(500, "Database not available");
        try {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM billing_budgets WHERE billing_account = ? AND budget_id = ?")) {
                ps.setString(1, billingAccount);
                ps.setString(2, budgetId);
                if (ps.executeUpdate() == 0)
                    return RestResponseHelper.error(404, "Budget not found: " + budgetId);
            }
            return RestResponseHelper.ok(RestResponseHelper.MAPPER.createObjectNode());
        } catch (Exception e) {
            logger.error("Failed to delete budget", e);
            return RestResponseHelper.error(500, e.getMessage());
        }
    }

    private boolean budgetExists(String billingAccount, String budgetId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT 1 FROM billing_budgets WHERE billing_account = ? AND budget_id = ?")) {
            ps.setString(1, billingAccount);
            ps.setString(2, budgetId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private ObjectNode budgetJson(String billingAccount, String budgetId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT display_name, amount_json, threshold_rules_json, create_time, update_time " +
                 "FROM billing_budgets WHERE billing_account = ? AND budget_id = ?")) {
            ps.setString(1, billingAccount);
            ps.setString(2, budgetId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ObjectNode budget = RestResponseHelper.MAPPER.createObjectNode();
                    budget.put("name", "billingAccounts/" + billingAccount + "/budgets/" + budgetId);
                    budget.put("displayName", rs.getString("display_name"));
                    try { budget.set("amount", RestResponseHelper.MAPPER.readTree(rs.getString("amount_json"))); }
                    catch (Exception e) { budget.put("amount", rs.getString("amount_json")); }
                    budget.put("createTime", String.valueOf(rs.getTimestamp("create_time")));
                    budget.put("updateTime", String.valueOf(rs.getTimestamp("update_time")));
                    return budget;
                }
            }
        }
        return null;
    }
}
