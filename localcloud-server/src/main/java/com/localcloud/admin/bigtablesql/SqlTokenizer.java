package com.localcloud.admin.bigtablesql;

import com.localcloud.admin.bigtablesql.SqlToken.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lexer for the Bigtable SQL dialect. Produces a stream of {@link SqlToken}s.
 */
public final class SqlTokenizer {

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("select", TokenType.SELECT),
            Map.entry("from", TokenType.FROM),
            Map.entry("where", TokenType.WHERE),
            Map.entry("limit", TokenType.LIMIT),
            Map.entry("insert", TokenType.INSERT),
            Map.entry("into", TokenType.INTO),
            Map.entry("values", TokenType.VALUES),
            Map.entry("update", TokenType.UPDATE),
            Map.entry("set", TokenType.SET),
            Map.entry("delete", TokenType.DELETE),
            Map.entry("drop", TokenType.DROP),
            Map.entry("table", TokenType.TABLE),
            Map.entry("create", TokenType.CREATE),
            Map.entry("alter", TokenType.ALTER),
            Map.entry("add", TokenType.ADD),
            Map.entry("family", TokenType.FAMILY),
            Map.entry("and", TokenType.AND),
            Map.entry("or", TokenType.OR),
            Map.entry("not", TokenType.NOT),
            Map.entry("between", TokenType.BETWEEN),
            Map.entry("like", TokenType.LIKE),
            Map.entry("in", TokenType.IN),
            Map.entry("null", TokenType.NULL),
            Map.entry("as", TokenType.AS),
            Map.entry("show", TokenType.SHOW),
            Map.entry("tables", TokenType.TABLES),
            Map.entry("describe", TokenType.DESCRIBE),
            Map.entry("order", TokenType.ORDER),
            Map.entry("by", TokenType.BY),
            Map.entry("asc", TokenType.ASC),
            Map.entry("desc", TokenType.DESC),
            Map.entry("distinct", TokenType.DISTINCT),
            Map.entry("offset", TokenType.OFFSET),
            Map.entry("having", TokenType.HAVING),
            Map.entry("group", TokenType.GROUP),
            Map.entry("case", TokenType.CASE),
            Map.entry("when", TokenType.WHEN),
            Map.entry("then", TokenType.THEN),
            Map.entry("else", TokenType.ELSE),
            Map.entry("end", TokenType.END),
            Map.entry("is", TokenType.IS),
            Map.entry("cast", TokenType.CAST),
            Map.entry("true", TokenType.TRUE),
            Map.entry("false", TokenType.FALSE),
            Map.entry("if", TokenType.IF),
            Map.entry("coalesce", TokenType.COALESCE),
            Map.entry("nullif", TokenType.NULLIF)
    );

    private final String input;
    private int pos;

    public SqlTokenizer(String input) {
        this.input = input;
        this.pos = 0;
    }

    public List<SqlToken> tokenize() {
        List<SqlToken> tokens = new ArrayList<>();
        while (pos < input.length()) {
            skipWhitespace();
            if (pos >= input.length()) break;

            char c = input.charAt(pos);

            // Single-line comment
            if (c == '-' && pos + 1 < input.length() && input.charAt(pos + 1) == '-') {
                while (pos < input.length() && input.charAt(pos) != '\n') pos++;
                continue;
            }

            // Multi-line comment /* ... */
            if (c == '/' && pos + 1 < input.length() && input.charAt(pos + 1) == '*') {
                int commentStart = pos;
                pos += 2;
                boolean closed = false;
                while (pos + 1 < input.length()) {
                    if (input.charAt(pos) == '*' && input.charAt(pos + 1) == '/') {
                        pos += 2;
                        closed = true;
                        break;
                    }
                    pos++;
                }
                if (!closed) {
                    throw new BigtableSqlException("Unterminated block comment", commentStart);
                }
                continue;
            }

            // String literal (single-quoted)
            if (c == '\'') {
                tokens.add(readString());
                continue;
            }

            // Double-quoted identifier
            if (c == '"') {
                tokens.add(readQuotedIdentifier('"'));
                continue;
            }

            // Backtick-quoted identifier
            if (c == '`') {
                tokens.add(readQuotedIdentifier('`'));
                continue;
            }

            // Number
            if (Character.isDigit(c)) {
                tokens.add(readNumber());
                continue;
            }

            // Identifier or keyword
            if (isIdentStart(c)) {
                tokens.add(readIdentifierOrKeyword());
                continue;
            }

            // Operators and delimiters
            tokens.add(readOperator());
        }

        tokens.add(new SqlToken(TokenType.EOF, "", pos));
        return tokens;
    }

    private SqlToken readString() {
        int start = pos;
        pos++; // skip opening '
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '\'') {
                // Check for escaped single quote ''
                if (pos + 1 < input.length() && input.charAt(pos + 1) == '\'') {
                    sb.append('\'');
                    pos += 2;
                } else {
                    pos++; // skip closing '
                    return new SqlToken(TokenType.STRING, sb.toString(), start);
                }
            } else if (c == '\\') {
                pos++;
                if (pos < input.length()) {
                    sb.append(input.charAt(pos));
                    pos++;
                }
            } else {
                sb.append(c);
                pos++;
            }
        }
        throw new BigtableSqlException("Unterminated string literal", start);
    }

    private SqlToken readQuotedIdentifier(char quote) {
        int start = pos;
        pos++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == quote) {
                pos++;
                return new SqlToken(TokenType.QUOTED_IDENTIFIER, sb.toString(), start);
            }
            sb.append(c);
            pos++;
        }
        throw new BigtableSqlException("Unterminated quoted identifier", start);
    }

    private SqlToken readNumber() {
        int start = pos;
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        // Check for float: digits followed by '.' and more digits
        if (pos < input.length() && input.charAt(pos) == '.'
                && pos + 1 < input.length() && Character.isDigit(input.charAt(pos + 1))) {
            pos++; // skip '.'
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            return new SqlToken(TokenType.FLOAT, input.substring(start, pos), start);
        }
        return new SqlToken(TokenType.NUMBER, input.substring(start, pos), start);
    }

    private SqlToken readIdentifierOrKeyword() {
        int start = pos;
        while (pos < input.length() && isIdentPart(input.charAt(pos))) pos++;
        String word = input.substring(start, pos);

        // Two-word keyword: MAX_VERSIONS
        if ("max_versions".equalsIgnoreCase(word)) {
            return new SqlToken(TokenType.MAX_VERSIONS, word, start);
        }

        TokenType kw = KEYWORDS.get(word.toLowerCase());
        if (kw != null) {
            return new SqlToken(kw, word, start);
        }
        return new SqlToken(TokenType.IDENTIFIER, word, start);
    }

    private SqlToken readOperator() {
        int start = pos;
        char c = input.charAt(pos);
        pos++;

        return switch (c) {
            case '(' -> new SqlToken(TokenType.LPAREN, "(", start);
            case ')' -> new SqlToken(TokenType.RPAREN, ")", start);
            case ',' -> new SqlToken(TokenType.COMMA, ",", start);
            case '.' -> new SqlToken(TokenType.DOT, ".", start);
            case ':' -> new SqlToken(TokenType.COLON, ":", start);
            case ';' -> new SqlToken(TokenType.SEMICOLON, ";", start);
            case '[' -> new SqlToken(TokenType.LBRACKET, "[", start);
            case ']' -> new SqlToken(TokenType.RBRACKET, "]", start);
            case '*' -> new SqlToken(TokenType.STAR, "*", start);
            case '+' -> new SqlToken(TokenType.PLUS, "+", start);
            case '-' -> new SqlToken(TokenType.MINUS, "-", start);
            case '/' -> new SqlToken(TokenType.SLASH, "/", start);
            case '%' -> new SqlToken(TokenType.PERCENT, "%", start);
            case '=' -> new SqlToken(TokenType.EQ, "=", start);
            case '<' -> {
                if (pos < input.length()) {
                    if (input.charAt(pos) == '=') { pos++; yield new SqlToken(TokenType.LTE, "<=", start); }
                    if (input.charAt(pos) == '>') { pos++; yield new SqlToken(TokenType.NE, "<>", start); }
                }
                yield new SqlToken(TokenType.LT, "<", start);
            }
            case '>' -> {
                if (pos < input.length() && input.charAt(pos) == '=') {
                    pos++;
                    yield new SqlToken(TokenType.GTE, ">=", start);
                }
                yield new SqlToken(TokenType.GT, ">", start);
            }
            case '!' -> {
                if (pos < input.length() && input.charAt(pos) == '=') {
                    pos++;
                    yield new SqlToken(TokenType.NE, "!=", start);
                }
                throw new BigtableSqlException("Unexpected character: !", start);
            }
            default -> throw new BigtableSqlException("Unexpected character: " + c, start);
        };
    }

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }
}
