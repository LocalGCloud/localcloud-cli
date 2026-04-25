package com.localcloud.sync;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates sync filter inputs to prevent SQL injection.
 * Column names must be valid SQL identifiers.
 * Operators must be from a fixed allowlist.
 */
public final class SyncFilterValidator {

    private static final Pattern VALID_COLUMN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    private static final Set<String> VALID_OPERATORS = Set.of(
            "=", "!=", ">", "<", ">=", "<=", "LIKE", "IN", "BETWEEN"
    );

    private SyncFilterValidator() {}

    public static void validate(SyncFilter filter) {
        validateColumn(filter.column());
        validateOperator(filter.operator());
    }

    public static void validateColumn(String column) {
        if (column == null || !VALID_COLUMN.matcher(column).matches()) {
            throw new IllegalArgumentException("Invalid column name: " + column
                    + ". Column names must be alphanumeric identifiers.");
        }
    }

    public static void validateOperator(String operator) {
        if (operator == null || !VALID_OPERATORS.contains(operator.toUpperCase())) {
            throw new IllegalArgumentException("Invalid operator: " + operator
                    + ". Allowed: " + VALID_OPERATORS);
        }
    }
}
