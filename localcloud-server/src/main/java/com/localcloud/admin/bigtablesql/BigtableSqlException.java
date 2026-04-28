package com.localcloud.admin.bigtablesql;

/**
 * Exception thrown for Bigtable SQL parsing or execution errors.
 */
public class BigtableSqlException extends RuntimeException {

    private final int position;

    public BigtableSqlException(String message) {
        super(message);
        this.position = -1;
    }

    public BigtableSqlException(String message, int position) {
        super(message + (position >= 0 ? " (at position " + position + ")" : ""));
        this.position = position;
    }

    public int getPosition() {
        return position;
    }
}
