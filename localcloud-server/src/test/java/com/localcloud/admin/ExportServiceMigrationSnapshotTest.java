package com.localcloud.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExportServiceMigrationSnapshotTest {
    @Test
    void decodesBigQueryWireRowsIntoSeedCompatibleObjects() {
        Map<String, Object> schema = Map.of("fields", List.of(
                Map.of("name", "id", "type", "INTEGER"),
                Map.of("name", "name", "type", "STRING"),
                Map.of("name", "tags", "type", "STRING", "mode", "REPEATED"),
                Map.of("name", "address", "type", "RECORD", "fields", List.of(
                        Map.of("name", "city", "type", "STRING")))));
        Map<String, Object> nested = Map.of("f", List.of(Map.of("v", "London")));
        List<Map<String, Object>> cells = List.of(
                Map.of("v", "7"),
                Map.of("v", "Ada"),
                Map.of("v", List.of(Map.of("v", "spark"), Map.of("v", "gcs"))),
                Map.of("v", nested));
        List<Map<String, Object>> rows = List.of(Map.of("f", cells));

        Map<String, Object> decoded = ExportService.decodeBigQueryRows(rows, schema).get(0);
        assertEquals("7", decoded.get("id"));
        assertEquals("Ada", decoded.get("name"));
        assertEquals(List.of("spark", "gcs"), decoded.get("tags"));
        assertEquals(Map.of("city", "London"), decoded.get("address"));
    }

    @Test
    void preservesNullCells() {
        Map<String, Object> schema = Map.of("fields", List.of(Map.of("name", "optional", "type", "STRING")));
        Map<String, Object> cell = new java.util.LinkedHashMap<>();
        cell.put("v", null);
        Map<String, Object> decoded = ExportService.decodeBigQueryRows(
                List.of(Map.of("f", List.of(cell))), schema).get(0);
        assertNull(decoded.get("optional"));
    }
}
