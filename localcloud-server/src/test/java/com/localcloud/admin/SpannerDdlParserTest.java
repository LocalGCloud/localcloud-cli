package com.localcloud.admin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SpannerDdlParserTest {

    @Test
    void parse_skipsTableConstraints() {
        Map<String, Object> parsed = SpannerDdlParser.parse("""
                CREATE TABLE customers (
                  customer_id STRING(36) NOT NULL,
                  name STRING(MAX),
                  metadata JSON,
                  CONSTRAINT fk_customer FOREIGN KEY (customer_id) REFERENCES accounts (customer_id),
                  CHECK (customer_id IS NOT NULL),
                  PRIMARY KEY (customer_id)
                )
                """);

        assertNotNull(parsed);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> columns = (List<Map<String, String>>) parsed.get("columns");
        assertEquals(List.of("customer_id", "name", "metadata"),
                columns.stream().map(col -> col.get("name")).toList());
    }

    @Test
    void parse_skipsHiddenAndTokenlistColumns() {
        Map<String, Object> parsed = SpannerDdlParser.parse("""
                CREATE TABLE articles (
                  id STRING(36) NOT NULL,
                  title STRING(MAX),
                  title_tokens TOKENLIST AS (TOKENIZE_FULLTEXT(title)) HIDDEN,
                  PRIMARY KEY (id)
                )
                """);

        assertNotNull(parsed);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> columns = (List<Map<String, String>>) parsed.get("columns");
        assertEquals(List.of("id", "title"),
                columns.stream().map(col -> col.get("name")).toList());
    }
}
