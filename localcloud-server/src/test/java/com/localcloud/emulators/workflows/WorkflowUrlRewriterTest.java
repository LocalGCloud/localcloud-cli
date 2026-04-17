package com.localcloud.emulators.workflows;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowUrlRewriterTest {

    @Test
    void detectSingleProxyUrl() {
        String yaml = """
            main:
              steps:
                - callService:
                    call: http.post
                    args:
                      url: http://10.179.131.124/proxy/jay-env/payment-service/api/charge
            """;

        List<WorkflowUrlRewriter.UrlMatch> matches = WorkflowUrlRewriter.detect(yaml);
        assertEquals(1, matches.size());

        var match = matches.get(0);
        assertEquals("jay-env", match.envName);
        assertEquals("payment-service", match.serviceName);
        assertEquals("/api/charge", match.pathSuffix);
        assertEquals("http://10.179.131.124/proxy/jay-env/payment-service", match.proxyBase);
    }

    @Test
    void detectMultipleServices() {
        String yaml = """
            main:
              steps:
                - step1:
                    call: http.post
                    args:
                      url: http://10.179.131.124/proxy/jay-env/payment-service/api/charge
                - step2:
                    call: http.get
                    args:
                      url: http://10.179.131.124/proxy/jay-env/order-service/api/list
            """;

        List<WorkflowUrlRewriter.UrlMatch> matches = WorkflowUrlRewriter.detect(yaml);
        assertEquals(2, matches.size());

        Set<String> serviceNames = matches.stream()
            .map(m -> m.serviceName).collect(Collectors.toSet());
        assertTrue(serviceNames.contains("payment-service"));
        assertTrue(serviceNames.contains("order-service"));
    }

    @Test
    void detectNoProxyUrls() {
        String yaml = """
            main:
              steps:
                - callExternal:
                    call: http.post
                    args:
                      url: https://api.stripe.com/v1/charges
            """;

        List<WorkflowUrlRewriter.UrlMatch> matches = WorkflowUrlRewriter.detect(yaml);
        assertTrue(matches.isEmpty());
    }

    @Test
    void rewriteSingleUrl() {
        String yaml = "url: http://10.179.131.124/proxy/jay-env/payment-service/api/charge";
        String rewritten = WorkflowUrlRewriter.rewrite(yaml);
        assertEquals("url: ${PAYMENT_SERVICE_URL}/api/charge", rewritten);
    }

    @Test
    void rewriteMultipleUrls() {
        String yaml = """
            step1:
              url: http://10.1.2.3/proxy/env1/payment-service/api/charge
            step2:
              url: http://10.1.2.3/proxy/env1/order-service/api/list
            """;
        String rewritten = WorkflowUrlRewriter.rewrite(yaml);
        assertTrue(rewritten.contains("${PAYMENT_SERVICE_URL}/api/charge"));
        assertTrue(rewritten.contains("${ORDER_SERVICE_URL}/api/list"));
    }

    @Test
    void rewritePreservesNonProxyUrls() {
        String yaml = """
            url1: https://api.stripe.com/v1/charges
            url2: http://10.1.2.3/proxy/env/svc/path
            """;
        String rewritten = WorkflowUrlRewriter.rewrite(yaml);
        assertTrue(rewritten.contains("https://api.stripe.com/v1/charges"));
        assertTrue(rewritten.contains("${SVC_URL}/path"));
    }

    @Test
    void rewriteUrlWithNoPathSuffix() {
        String yaml = "url: http://10.1.2.3/proxy/env/service";
        String rewritten = WorkflowUrlRewriter.rewrite(yaml);
        assertEquals("url: ${SERVICE_URL}", rewritten);
    }

    @Test
    void toVarNameFromServiceName() {
        assertEquals("PAYMENT_SERVICE_URL", WorkflowUrlRewriter.toVarName("payment-service"));
        assertEquals("ORDER_PROCESSING_V2_URL", WorkflowUrlRewriter.toVarName("order-processing-v2"));
        assertEquals("NOTIFY_URL", WorkflowUrlRewriter.toVarName("notify"));
    }

    @Test
    void generateEnvVarEntries() {
        String yaml = "url: http://10.1.2.3/proxy/jay/payment-service/api/charge";
        List<WorkflowUrlRewriter.UrlMatch> matches = WorkflowUrlRewriter.detect(yaml);
        List<Map<String, String>> entries = WorkflowUrlRewriter.generateEnvVarEntries(matches);

        assertEquals(3, entries.size()); // one per preset

        // Remote preset has the proxy base URL
        Map<String, String> remote = entries.stream()
            .filter(e -> "remote".equals(e.get("preset"))).findFirst().orElseThrow();
        assertEquals("PAYMENT_SERVICE_URL", remote.get("varName"));
        assertEquals("http://10.1.2.3/proxy/jay/payment-service", remote.get("varValue"));

        // Local and production presets have empty values
        Map<String, String> local = entries.stream()
            .filter(e -> "local".equals(e.get("preset"))).findFirst().orElseThrow();
        assertEquals("", local.get("varValue"));

        Map<String, String> prod = entries.stream()
            .filter(e -> "production".equals(e.get("preset"))).findFirst().orElseThrow();
        assertEquals("", prod.get("varValue"));
    }

    @Test
    void deduplicateSameServiceMultipleOccurrences() {
        String yaml = """
            step1:
              url: http://10.1.2.3/proxy/env/payment-service/api/charge
            step2:
              url: http://10.1.2.3/proxy/env/payment-service/api/refund
            """;

        List<WorkflowUrlRewriter.UrlMatch> matches = WorkflowUrlRewriter.detect(yaml);
        assertEquals(1, matches.size()); // deduplicated by service name

        List<Map<String, String>> entries = WorkflowUrlRewriter.generateEnvVarEntries(matches);
        assertEquals(3, entries.size()); // 3 presets for 1 service
    }

    @Test
    void rewriteAllOccurrencesOfSameService() {
        String yaml = """
            url1: http://10.1.2.3/proxy/env/svc/path1
            url2: http://10.1.2.3/proxy/env/svc/path2
            """;
        String rewritten = WorkflowUrlRewriter.rewrite(yaml);
        assertTrue(rewritten.contains("${SVC_URL}/path1"));
        assertTrue(rewritten.contains("${SVC_URL}/path2"));
        assertFalse(rewritten.contains("10.1.2.3"));
    }

    @Test
    void sameServiceDifferentEnvs() {
        String yaml = """
            url1: http://10.1.2.3/proxy/env-a/payment-service/api/charge
            url2: http://10.1.2.3/proxy/env-b/payment-service/api/refund
            """;

        List<WorkflowUrlRewriter.UrlMatch> matches = WorkflowUrlRewriter.detect(yaml);
        assertEquals(1, matches.size()); // same service, deduplicated

        String rewritten = WorkflowUrlRewriter.rewrite(yaml);
        assertTrue(rewritten.contains("${PAYMENT_SERVICE_URL}/api/charge"));
        assertTrue(rewritten.contains("${PAYMENT_SERVICE_URL}/api/refund"));
    }
}
