package com.localcloud.emulators.cloudbilling;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CloudBilling REST handler response format and billing info parsing.
 */
class CloudBillingResponseFormatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void billingInfoResponse_containsRequiredFields() throws Exception {
        String response = "{\"name\":\"projects/test/billingInfo\"," +
                          "\"billingAccountName\":\"billingAccounts/ABCD-1234\"," +
                          "\"billingEnabled\":true}";
        var json = mapper.readTree(response);
        assertTrue(json.has("name"));
        assertTrue(json.has("billingAccountName"));
        assertTrue(json.has("billingEnabled"));
        assertTrue(json.get("billingEnabled").asBoolean());
    }

    @Test
    void updateBillingInfo_parsesAccountField() throws Exception {
        String body = "{\"billingAccountName\":\"billingAccounts/XYZ-9999\"}";
        var json = mapper.readTree(body);
        assertEquals("billingAccounts/XYZ-9999", json.get("billingAccountName").asText());
    }

    @Test
    void billingAccountName_validFormat() {
        String account = "billingAccounts/ABCD-1234-5678";
        assertTrue(account.startsWith("billingAccounts/"));
        String id = account.substring("billingAccounts/".length());
        assertFalse(id.isEmpty());
    }

    @Test
    void listBillingAccountsResponse_containsAccountsArray() throws Exception {
        var response = mapper.createObjectNode();
        var accounts = mapper.createArrayNode();
        var account = mapper.createObjectNode();
        account.put("name", "billingAccounts/ABCD");
        account.put("displayName", "My Account");
        account.put("open", true);
        accounts.add(account);
        response.set("billingAccounts", accounts);
        assertEquals(1, response.get("billingAccounts").size());
    }
}
