package com.localcloud.emulators.workflows.expression;

public record Token(TokenType type, String value, int position) {
    public enum TokenType {
        NUMBER, STRING, BOOLEAN, NULL, IDENTIFIER,
        PLUS, MINUS, STAR, SLASH, DOUBLE_SLASH, PERCENT,
        EQ, NEQ, LT, GT, LTE, GTE,
        AND, OR, NOT, IN,
        LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE,
        DOT, COMMA, COLON, EOF
    }
}
