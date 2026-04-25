package com.localcloud.sync;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SyncOAuthFlowTest {

    @Test
    void buildCallbackHtml_success_containsConnected() throws Exception {
        var method = SyncApiService.class.getDeclaredMethod("buildCallbackHtml", boolean.class, String.class);
        method.setAccessible(true);
        var service = createTestInstance();
        String html = (String) method.invoke(service, true, "Connected to prod-123");
        assertTrue(html.contains("Connected!"));
        assertTrue(html.contains("prod-123"));
        assertTrue(html.contains("#34a853")); // green
    }

    @Test
    void buildCallbackHtml_failure_containsError() throws Exception {
        var method = SyncApiService.class.getDeclaredMethod("buildCallbackHtml", boolean.class, String.class);
        method.setAccessible(true);
        var service = createTestInstance();
        String html = (String) method.invoke(service, false, "Access denied");
        assertTrue(html.contains("Connection Failed"));
        assertTrue(html.contains("Access denied"));
        assertTrue(html.contains("#ea4335")); // red
    }

    private SyncApiService createTestInstance() {
        return new SyncApiService(
            org.mockito.Mockito.mock(SyncService.class),
            org.mockito.Mockito.mock(SyncCredentialRepository.class),
            org.mockito.Mockito.mock(com.localcloud.config.LocalCloudConfig.class)
        );
    }
}
