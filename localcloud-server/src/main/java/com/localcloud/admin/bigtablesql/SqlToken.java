package com.localcloud.admin.bigtablesql;

/**
 * Token produced by {@link SqlTokenizer} for the Bigtable SQL dialect.
 */
public record SqlToken(TokenType type, String value, int position) {

    public enum TokenType {
        // Literals
        NUMBER, FLOAT, STRING, IDENTIFIER, QUOTED_IDENTIFIER,

        // Keywords
        SELECT, FROM, WHERE, LIMIT, INSERT, INTO, VALUES, UPDATE, SET,
        DELETE, DROP, TABLE, CREATE, ALTER, ADD, FAMILY, AND, OR, NOT,
        BETWEEN, LIKE, IN, NULL, AS, MAX_VERSIONS, SHOW, TABLES, DESCRIBE,
        ORDER, BY, ASC, DESC, DISTINCT, OFFSET, HAVING, GROUP,
        CASE, WHEN, THEN, ELSE, END, IS, CAST, TRUE, FALSE,
        IF, COALESCE, NULLIF,

        // Operators
        EQ, NE, LT, GT, LTE, GTE, PLUS, MINUS, STAR, SLASH, PERCENT,

        // Delimiters
        LPAREN, RPAREN, COMMA, DOT, COLON, SEMICOLON, LBRACKET, RBRACKET,

        // Special
        EOF
    }

    @Override
    public String toString() {
        return type + "(" + value + ")@" + position;
    }
}
