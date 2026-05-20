package com.localcloud.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutateServiceTest {

    @Test
    void extractDdlErrorSummarizesMalformedArrayWithoutEchoingFullDdl() {
        String message = "Error parsing Spanner DDL statement: CREATE TABLE Transactions ("
                + " ShardId STRING(2) NOT NULL,"
                + " ComplianceFlags ARRAY<<STRING(20)>"
                + ") PRIMARY KEY (ShardId)";

        String extracted = MutateService.extractDdlError(message);

        assertTrue(extracted.contains("ARRAY types use one '<'"));
        assertTrue(extracted.contains("ARRAY<STRING(MAX)>"));
        assertFalse(extracted.contains("CREATE TABLE Transactions"));
    }

    @Test
    void extractDdlErrorDoesNotReturnLongStatementWhenOnlyParserPrefixIsAvailable() {
        String message = "Error parsing Spanner DDL statement: CREATE TABLE Transactions ("
                + " Description STRING(500), Metadata JSON, SpannerCommitTS TIMESTAMP NOT NULL"
                + " OPTIONS (allow_commit_timestamp=true)) PRIMARY KEY (ShardId, CustomerId)";

        String extracted = MutateService.extractDdlError(message);

        assertTrue(extracted.startsWith("Error parsing DDL statement."));
        assertFalse(extracted.contains("CREATE TABLE Transactions"));
    }

    @Test
    void extractDdlErrorKeepsSpecificSyntaxLocation() {
        String extracted = MutateService.extractDdlError(
                "Error parsing Spanner DDL statement: CREATE TABLE Bad (...) : Syntax error on line 6, column 32: Unexpected token");

        assertTrue(extracted.contains("Syntax error on line 6, column 32"));
        assertFalse(extracted.contains("CREATE TABLE Bad"));
    }
}
