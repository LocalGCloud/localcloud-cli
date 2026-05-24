package com.localcloud.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.linecorp.armeria.common.HttpStatus;
import com.localcloud.config.LocalCloudConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

class MetadataServerServiceTest {

    private MetadataServerService service;

    @BeforeEach
    void setUp() {
        LocalCloudConfig config = mock(LocalCloudConfig.class);
        when(config.getProjectId()).thenReturn("local-project");
        service = new MetadataServerService(config);
    }

    @Test
    void projectIdReturnsGoogleMetadataFlavor() {
        var response = service.projectId().aggregate().join();

        assertEquals(HttpStatus.OK, response.status());
        assertEquals("local-project", response.contentUtf8());
        assertEquals("Google", response.headers().get("Metadata-Flavor"));
    }

    @Test
    void tokenReturnsLocalBearerToken() {
        var response = service.defaultServiceAccountToken().aggregate().join();

        assertEquals(HttpStatus.OK, response.status());
        assertEquals("Google", response.headers().get("Metadata-Flavor"));
        assertTrue(response.contentUtf8().contains("\"access_token\":\"localcloud-dev-token\""));
        assertTrue(response.contentUtf8().contains("\"token_type\":\"Bearer\""));
    }

    @Test
    void regionAndZoneUseLocalDefaults() {
        assertTrue(service.region().aggregate().join().contentUtf8().endsWith("/regions/us-central1"));
        assertTrue(service.zone().aggregate().join().contentUtf8().endsWith("/zones/us-central1-a"));
    }
}
