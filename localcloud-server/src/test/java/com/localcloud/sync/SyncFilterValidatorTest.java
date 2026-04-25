package com.localcloud.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SyncFilterValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"name", "created_at", "user_id", "_private", "col123"})
    void validColumn_passes(String col) {
        assertDoesNotThrow(() -> SyncFilterValidator.validateColumn(col));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1=1); DROP TABLE", "col name", "col;", "col--", "123col", ""})
    void invalidColumn_throws(String col) {
        assertThrows(IllegalArgumentException.class,
                () -> SyncFilterValidator.validateColumn(col));
    }

    @Test
    void nullColumn_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> SyncFilterValidator.validateColumn(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"=", "!=", ">", "<", ">=", "<=", "LIKE", "IN", "BETWEEN"})
    void validOperator_passes(String op) {
        assertDoesNotThrow(() -> SyncFilterValidator.validateOperator(op));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DROP", "OR", "AND", ";", "= 1 OR 1=1 --", ""})
    void invalidOperator_throws(String op) {
        assertThrows(IllegalArgumentException.class,
                () -> SyncFilterValidator.validateOperator(op));
    }

    @Test
    void nullOperator_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> SyncFilterValidator.validateOperator(null));
    }

    @Test
    void validateFilter_validFilter_passes() {
        SyncFilter filter = new SyncFilter("created_at", ">=", "2026-01-01", "TIMESTAMP");
        assertDoesNotThrow(() -> SyncFilterValidator.validate(filter));
    }

    @Test
    void validateFilter_invalidColumn_throws() {
        SyncFilter filter = new SyncFilter("1=1); DROP TABLE", "=", "x", "STRING");
        assertThrows(IllegalArgumentException.class, () -> SyncFilterValidator.validate(filter));
    }
}
