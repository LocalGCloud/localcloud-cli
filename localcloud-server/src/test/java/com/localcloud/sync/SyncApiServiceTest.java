package com.localcloud.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcloud.config.LocalCloudConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncApiServiceTest {

    @Mock SyncService syncService;
    @Mock SyncCredentialRepository credRepo;
    @Mock LocalCloudConfig config;

    SyncApiService api;

    @BeforeEach
    void setUp() {
        api = new SyncApiService(syncService, credRepo, config);
    }

    @Test
    void canInstantiate() {
        assertNotNull(api);
    }

    // -----------------------------------------------------------------------
    // parseFilters — null input
    // -----------------------------------------------------------------------

    @Test
    void parseFilters_null_returnsEmptyList() throws Exception {
        var method = SyncApiService.class.getDeclaredMethod("parseFilters", Object.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SyncFilter> result = (List<SyncFilter>) method.invoke(api, (Object) null);
        assertTrue(result.isEmpty());
    }

    // -----------------------------------------------------------------------
    // parseFilters — valid list with all fields
    // -----------------------------------------------------------------------

    @Test
    void parseFilters_validList_returnsFilters() throws Exception {
        var method = SyncApiService.class.getDeclaredMethod("parseFilters", Object.class);
        method.setAccessible(true);
        List<Map<String, String>> input = List.of(
            Map.of("column", "created_at", "operator", ">=", "value", "2026-01-01", "columnType", "TIMESTAMP")
        );
        @SuppressWarnings("unchecked")
        List<SyncFilter> result = (List<SyncFilter>) method.invoke(api, (Object) input);
        assertEquals(1, result.size());
        assertEquals("created_at", result.get(0).column());
        assertEquals(">=", result.get(0).operator());
        assertEquals("2026-01-01", result.get(0).value());
        assertEquals("TIMESTAMP", result.get(0).columnType());
    }

    // -----------------------------------------------------------------------
    // parseFilters — missing columnType defaults to STRING
    // -----------------------------------------------------------------------

    @Test
    void parseFilters_missingColumnType_defaultsToString() throws Exception {
        var method = SyncApiService.class.getDeclaredMethod("parseFilters", Object.class);
        method.setAccessible(true);
        // Use a mutable map since Map.of doesn't support getOrDefault with missing keys well
        // but actually Map.of works fine with getOrDefault — it just returns the default.
        List<Map<String, String>> input = List.of(
            Map.of("column", "name", "operator", "=", "value", "test")
        );
        @SuppressWarnings("unchecked")
        List<SyncFilter> result = (List<SyncFilter>) method.invoke(api, (Object) input);
        assertEquals(1, result.size());
        assertEquals("STRING", result.get(0).columnType());
    }

    // -----------------------------------------------------------------------
    // parseFilters — empty list
    // -----------------------------------------------------------------------

    @Test
    void parseFilters_emptyList_returnsEmpty() throws Exception {
        var method = SyncApiService.class.getDeclaredMethod("parseFilters", Object.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<SyncFilter> result = (List<SyncFilter>) method.invoke(api, (Object) List.of());
        assertTrue(result.isEmpty());
    }
}
