package com.localcloud.sync;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SyncApiServiceTest {

    @Test
    void canInstantiate() {
        var syncService = mock(SyncService.class);
        var credRepo = mock(SyncCredentialRepository.class);
        var config = mock(com.localcloud.config.LocalCloudConfig.class);
        var api = new SyncApiService(syncService, credRepo, config);
        assertNotNull(api);
    }
}
