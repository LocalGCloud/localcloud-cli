package com.localcloud.emulators.workflows.expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ExpressionTokenizer {
    private static final Set<String> KEYWORDS = Set.of("and", "or", "not", "in", "true", "false", "null");

    private final String input;
    private int pos;
    private final List<Token> tokens = new ArrayList<>();

    public ExpressionTokenizer(String input) {
        this.input = input;
        this.pos = 0;
    }

    public List<Token> tokenize() {
        tokens.clear();
        pos = 0;
        while (pos < input.length()) {
            skipWhitespace();
            if (pos >= input.length()) break;
            char c = input.charAt(pos);

            if (Character.isDigit(c)) { readNumber(); }
            else if (c == '"' || c == '\'') { readString(c); }
            else if (Character.isLetter(c) || c == '_') { readIdentifier(); }
            else if (c == '+') { addToken(Token.TokenType.PLUS, "+"); pos++; }
            else if (c == '-') { addToken(Token.TokenType.MINUS, "-"); pos++; }
            else if (c == '*') { addToken(Token.TokenType.STAR, "*"); pos++; }
            else if (c == '%') { addToken(Token.TokenType.PERCENT, "%"); pos++; }
            else if (c == '/') {
                if (pos + 1 < input.length() && input.charAt(pos + 1) == '/') {
                    addToken(Token.TokenType.DOUBLE_SLASH, "//"); pos += 2;
                } else {
                    addToken(Token.TokenType.SLASH, "/"); pos++;
                }
            }
            else if (c == '=' && pos + 1 < input.length() && input.charAt(pos + 1) == '=') {
                addToken(Token.TokenType.EQ, "=="); pos += 2;
            }
            else if (c == '!' && pos + 1 < input.length() && input.charAt(pos + 1) == '=') {
                addToken(Token.TokenType.NEQ, "!="); pos += 2;
            }
            else if (c == '<') {
                if (pos + 1 < input.length() && input.charAt(pos + 1) == '=') {
                    addToken(Token.TokenType.LTE, "<="); pos += 2;
                } else {
                    addToken(Token.TokenType.LT, "<"); pos++;
                }
            }
            else if (c == '>') {
                if (pos + 1 < input.length() && input.charAt(pos + 1) == '=') {
                    addToken(Token.TokenType.GTE, ">="); pos += 2;
                } else {
                    addToken(Token.TokenType.GT, ">"); pos++;
                }
            }
            else if (c == '(') { addToken(Token.TokenType.LPAREN, "("); pos++; }
            else if (c == ')') { addToken(Token.TokenType.RPAREN, ")"); pos++; }
            else if (c == '[') { addToken(Token.TokenType.LBRACKET, "["); pos++; }
            else if (c == ']') { addToken(Token.TokenType.RBRACKET, "]"); pos++; }
            else if (c == '{') { addToken(Token.TokenType.LBRACE, "{"); pos++; }
            else if (c == '}') { addToken(Token.TokenType.RBRACE, "}"); pos++; }
            else if (c == '.') { addToken(Token.TokenType.DOT, "."); pos++; }
            else if (c == ',') { addToken(Token.TokenType.COMMA, ","); pos++; }
            else if (c == ':') { addToken(Token.TokenType.COLON, ":"); pos++; }
            else {
                throw new ExpressionException("Unexpected character '" + c + "' at position " + pos);
            }
        }
        addToken(Token.TokenType.EOF, "");
        return tokens;
    }

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
    }

    private void readNumber() {
        int start = pos;
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        if (pos < input.length() && input.charAt(pos) == '.' && pos + 1 < input.length() && Character.isDigit(input.charAt(pos + 1))) {
            pos++; // skip '.'
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        }
        addToken(Token.TokenType.NUMBER, input.substring(start, pos));
    }

    private void readString(char quote) {
        int start = pos;
        pos++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < input.length() && input.charAt(pos) != quote) {
            if (input.charAt(pos) == '\\' && pos + 1 < input.length()) {
                pos++;
                char escaped = input.charAt(pos);
                switch (escaped) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '\\' -> sb.append('\\');
                    case '\'' -> sb.append('\'');
                    case '"' -> sb.append('"');
                    default -> { sb.append('\\'); sb.append(escaped); }
                }
            } else {
                sb.append(input.charAt(pos));
            }
            pos++;
        }
        if (pos >= input.length()) throw new ExpressionException("Unterminated string starting at position " + start);
        pos++; // skip closing quote
        addToken(Token.TokenType.STRING, sb.toString());
    }

    private void readIdentifier() {
        int start = pos;
        while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) pos++;
        String word = input.substring(start, pos);
        Token.TokenType type = switch (word) {
            case "and" -> Token.TokenType.AND;
            case "or" -> Token.TokenType.OR;
            case "not" -> Token.TokenType.NOT;
            case "in" -> Token.TokenType.IN;
            case "true", "false" -> Token.TokenType.BOOLEAN;
            case "null" -> Token.TokenType.NULL;
            default -> Token.TokenType.IDENTIFIER;
        };
        addToken(type, word);
    }

    private void addToken(Token.TokenType type, String value) {
        tokens.add(new Token(type, value, pos));
    }
}
