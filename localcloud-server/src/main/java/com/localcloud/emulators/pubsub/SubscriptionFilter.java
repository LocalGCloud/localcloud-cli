package com.localcloud.emulators.pubsub;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple Pub/Sub subscription filter expression parser and evaluator.
 * Supports: =, !=, :, hasPrefix(), hasSuffix(), AND, OR, NOT, parentheses
 */
public class SubscriptionFilter {

    private final String expression;
    private final Node root;

    public SubscriptionFilter(String expression) {
        this.expression = expression;
        this.root = expression != null && !expression.isEmpty() ? parse(expression) : null;
    }

    public boolean matches(Map<String, String> attributes) {
        if (root == null) return true; // no filter = match all
        return root.evaluate(attributes);
    }

    // ---- AST nodes ----

    interface Node {
        boolean evaluate(Map<String, String> attrs);
    }

    record AndNode(List<Node> children) implements Node {
        public boolean evaluate(Map<String, String> attrs) {
            return children.stream().allMatch(c -> c.evaluate(attrs));
        }
    }

    record OrNode(List<Node> children) implements Node {
        public boolean evaluate(Map<String, String> attrs) {
            return children.stream().anyMatch(c -> c.evaluate(attrs));
        }
    }

    record NotNode(Node child) implements Node {
        public boolean evaluate(Map<String, String> attrs) {
            return !child.evaluate(attrs);
        }
    }

    record EqNode(String attr, String value) implements Node {
        public boolean evaluate(Map<String, String> attrs) {
            String v = attrs.get(attr);
            return value.equals(v);
        }
    }

    record NeqNode(String attr, String value) implements Node {
        public boolean evaluate(Map<String, String> attrs) {
            String v = attrs.get(attr);
            return !value.equals(v);
        }
    }

    record ContainsNode(String attr, String value) implements Node {
        public boolean evaluate(Map<String, String> attrs) {
            String v = attrs.get(attr);
            return v != null && v.contains(value);
        }
    }

    record HasPrefixNode(String attr, String prefix) implements Node {
        public boolean evaluate(Map<String, String> attrs) {
            String v = attrs.get(attr);
            return v != null && v.startsWith(prefix);
        }
    }

    record HasSuffixNode(String attr, String suffix) implements Node {
        public boolean evaluate(Map<String, String> attrs) {
            String v = attrs.get(attr);
            return v != null && v.endsWith(suffix);
        }
    }

    // ---- Parser ----

    private int pos;
    private String input;

    private Node parse(String expr) {
        this.input = expr;
        this.pos = 0;
        Node result = parseOr();
        if (pos < input.length()) {
            throw new IllegalArgumentException("Unexpected character at position " + pos + ": " + input.charAt(pos));
        }
        return result;
    }

    private Node parseOr() {
        Node left = parseAnd();
        while (matchKeyword("OR")) {
            if (left instanceof OrNode on) {
                on.children().add(parseAnd());
            } else {
                List<Node> children = new ArrayList<>();
                children.add(left);
                children.add(parseAnd());
                left = new OrNode(children);
            }
        }
        return left;
    }

    private Node parseAnd() {
        Node left = parseNot();
        while (matchKeyword("AND")) {
            if (left instanceof AndNode an) {
                an.children().add(parseNot());
            } else {
                List<Node> children = new ArrayList<>();
                children.add(left);
                children.add(parseNot());
                left = new AndNode(children);
            }
        }
        return left;
    }

    private Node parseNot() {
        if (matchKeyword("NOT")) {
            return new NotNode(parseNot());
        }
        return parsePrimary();
    }

    private Node parsePrimary() {
        skipWhitespace();
        if (pos >= input.length()) throw new IllegalArgumentException("Unexpected end of expression");

        if (input.charAt(pos) == '(') {
            pos++;
            Node inner = parseOr();
            if (pos >= input.length() || input.charAt(pos) != ')') {
                throw new IllegalArgumentException("Expected ')' at position " + pos);
            }
            pos++;
            return inner;
        }

        // Try function call: hasPrefix(attr, val) or hasSuffix(attr, val)
        if (matchKeyword("HASPREFIX")) {
            expect('(');
            String attr = parseAttribute();
            skipWhitespace();
            expect(',');
            String val = parseString();
            skipWhitespace();
            expect(')');
            return new HasPrefixNode(attr, val);
        }
        if (matchKeyword("HASSUFFIX")) {
            expect('(');
            String attr = parseAttribute();
            skipWhitespace();
            expect(',');
            String val = parseString();
            skipWhitespace();
            expect(')');
            return new HasSuffixNode(attr, val);
        }

        // Attribute comparison: attributes.xxx OP "value"  or  attributes.xxx : "value"
        String attr = parseAttribute();
        skipWhitespace();
        if (pos >= input.length()) throw new IllegalArgumentException("Expected operator after attribute");
        String op = parseOperator();
        skipWhitespace();
        String val = parseString();

        return switch (op) {
            case "=" -> new EqNode(attr, val);
            case "!=" -> new NeqNode(attr, val);
            case ":" -> new ContainsNode(attr, val);
            default -> throw new IllegalArgumentException("Unknown operator: " + op);
        };
    }

    private String parseAttribute() {
        skipWhitespace();
        // Expect attributes.xxx
        if (!matchKeyword("ATTRIBUTES")) {
            throw new IllegalArgumentException("Expected 'attributes' at position " + pos);
        }
        expect('.');
        return parseIdentifier();
    }

    private String parseIdentifier() {
        skipWhitespace();
        int start = pos;
        while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos))
                || input.charAt(pos) == '_' || input.charAt(pos) == '-')) {
            pos++;
        }
        if (start == pos) throw new IllegalArgumentException("Expected identifier at position " + pos);
        return input.substring(start, pos);
    }

    private String parseOperator() {
        if (pos + 1 < input.length() && input.substring(pos, pos + 2).equals("!=")) {
            pos += 2;
            return "!=";
        }
        char c = input.charAt(pos);
        pos++;
        return String.valueOf(c);
    }

    private String parseString() {
        skipWhitespace();
        if (pos >= input.length() || input.charAt(pos) != '"') {
            throw new IllegalArgumentException("Expected '\"' at position " + pos);
        }
        pos++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < input.length() && input.charAt(pos) != '"') {
            if (input.charAt(pos) == '\\' && pos + 1 < input.length()) {
                pos++;
                sb.append(input.charAt(pos));
            } else {
                sb.append(input.charAt(pos));
            }
            pos++;
        }
        if (pos >= input.length()) throw new IllegalArgumentException("Unterminated string");
        pos++; // skip closing quote
        return sb.toString();
    }

    private boolean matchKeyword(String keyword) {
        skipWhitespace();
        String upperKeyword = keyword.toUpperCase();
        int end = pos;
        while (end < input.length() && Character.isLetter(input.charAt(end))) {
            end++;
        }
        String word = input.substring(pos, end).toUpperCase();
        if (word.equals(upperKeyword)) {
            pos = end;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        skipWhitespace();
        if (pos >= input.length() || input.charAt(pos) != c) {
            throw new IllegalArgumentException("Expected '" + c + "' at position " + pos);
        }
        pos++;
    }

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
            pos++;
        }
    }
}
