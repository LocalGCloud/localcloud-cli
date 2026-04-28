package com.localcloud.emulators.workflows.expression;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive descent parser for Cloud Workflows expression language.
 * Grammar (precedence low to high):
 *   expression    → logical_or
 *   logical_or    → logical_and ("or" logical_and)*
 *   logical_and   → membership ("and" membership)*
 *   membership    → comparison ("in" comparison)?
 *   comparison    → addition (("=="|"!="|"<"|">"|"<="|">=") addition)?
 *   addition      → multiplication (("+"|"-") multiplication)*
 *   multiplication → unary (("*"|"/"|"//"|"%") unary)*
 *   unary         → ("not"|"-") unary | postfix
 *   postfix       → primary (("." IDENT) | ("[" expression "]") | ("(" arguments ")"))*
 *   primary       → NUMBER | STRING | BOOLEAN | NULL | IDENTIFIER | "(" expression ")" | "[" list "]" | "{" map "}"
 */
public class ExpressionParser {
    private final List<Token> tokens;
    private int pos;

    public ExpressionParser(List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    public AstNode parse() {
        AstNode result = expression();
        if (!isAtEnd()) {
            throw new ExpressionException("Unexpected token: " + peek().value() + " at position " + peek().position());
        }
        return result;
    }

    private AstNode expression() { return logicalOr(); }

    private AstNode logicalOr() {
        AstNode left = logicalAnd();
        while (match(Token.TokenType.OR)) {
            AstNode right = logicalAnd();
            left = new AstNode.BinaryOp("or", left, right);
        }
        return left;
    }

    private AstNode logicalAnd() {
        AstNode left = membership();
        while (match(Token.TokenType.AND)) {
            AstNode right = membership();
            left = new AstNode.BinaryOp("and", left, right);
        }
        return left;
    }

    private AstNode membership() {
        AstNode left = comparison();
        if (match(Token.TokenType.IN)) {
            AstNode right = comparison();
            left = new AstNode.BinaryOp("in", left, right);
        }
        return left;
    }

    private AstNode comparison() {
        AstNode left = addition();
        if (check(Token.TokenType.EQ) || check(Token.TokenType.NEQ) ||
            check(Token.TokenType.LT) || check(Token.TokenType.GT) ||
            check(Token.TokenType.LTE) || check(Token.TokenType.GTE)) {
            String op = advance().value();
            AstNode right = addition();
            left = new AstNode.BinaryOp(op, left, right);
        }
        return left;
    }

    private AstNode addition() {
        AstNode left = multiplication();
        while (check(Token.TokenType.PLUS) || check(Token.TokenType.MINUS)) {
            String op = advance().value();
            AstNode right = multiplication();
            left = new AstNode.BinaryOp(op, left, right);
        }
        return left;
    }

    private AstNode multiplication() {
        AstNode left = unary();
        while (check(Token.TokenType.STAR) || check(Token.TokenType.SLASH) ||
               check(Token.TokenType.DOUBLE_SLASH) || check(Token.TokenType.PERCENT)) {
            String op = advance().value();
            AstNode right = unary();
            left = new AstNode.BinaryOp(op, left, right);
        }
        return left;
    }

    private AstNode unary() {
        if (match(Token.TokenType.NOT)) {
            return new AstNode.UnaryOp("not", unary());
        }
        if (match(Token.TokenType.MINUS)) {
            return new AstNode.UnaryOp("-", unary());
        }
        return postfix();
    }

    private AstNode postfix() {
        AstNode expr = primary();
        while (true) {
            if (match(Token.TokenType.DOT)) {
                Token field = expect(Token.TokenType.IDENTIFIER, "Expected field name after '.'");
                expr = new AstNode.MemberAccess(expr, field.value());
            } else if (match(Token.TokenType.LBRACKET)) {
                AstNode index = expression();
                expect(Token.TokenType.RBRACKET, "Expected ']'");
                expr = new AstNode.IndexAccess(expr, index);
            } else if (check(Token.TokenType.LPAREN) && expr instanceof AstNode.Variable v) {
                advance(); // consume '('
                List<AstNode> args = parseArguments();
                expect(Token.TokenType.RPAREN, "Expected ')'");
                expr = new AstNode.FunctionCall(v.name(), args);
            } else if (check(Token.TokenType.LPAREN) && expr instanceof AstNode.MemberAccess ma) {
                // Handle namespaced function calls like sys.get_env(...)
                advance(); // consume '('
                List<AstNode> args = parseArguments();
                expect(Token.TokenType.RPAREN, "Expected ')'");
                String funcName = flattenMemberAccess(ma);
                expr = new AstNode.FunctionCall(funcName, args);
            } else {
                break;
            }
        }
        return expr;
    }

    private AstNode primary() {
        if (match(Token.TokenType.NUMBER)) {
            String val = previous().value();
            return new AstNode.NumberLiteral(Double.parseDouble(val));
        }
        if (match(Token.TokenType.STRING)) {
            return new AstNode.StringLiteral(previous().value());
        }
        if (match(Token.TokenType.BOOLEAN)) {
            return new AstNode.BooleanLiteral("true".equals(previous().value()));
        }
        if (match(Token.TokenType.NULL)) {
            return new AstNode.NullLiteral();
        }
        if (match(Token.TokenType.IDENTIFIER)) {
            return new AstNode.Variable(previous().value());
        }
        if (match(Token.TokenType.LPAREN)) {
            AstNode expr = expression();
            expect(Token.TokenType.RPAREN, "Expected ')'");
            return expr;
        }
        if (match(Token.TokenType.LBRACKET)) {
            List<AstNode> elements = new ArrayList<>();
            if (!check(Token.TokenType.RBRACKET)) {
                elements.add(expression());
                while (match(Token.TokenType.COMMA)) {
                    elements.add(expression());
                }
            }
            expect(Token.TokenType.RBRACKET, "Expected ']'");
            return new AstNode.ListLiteral(elements);
        }
        if (match(Token.TokenType.LBRACE)) {
            List<String> keys = new ArrayList<>();
            List<AstNode> values = new ArrayList<>();
            if (!check(Token.TokenType.RBRACE)) {
                parseMapEntry(keys, values);
                while (match(Token.TokenType.COMMA)) {
                    parseMapEntry(keys, values);
                }
            }
            expect(Token.TokenType.RBRACE, "Expected '}'");
            return new AstNode.MapLiteral(keys, values);
        }
        throw new ExpressionException("Unexpected token: " + peek().value() + " at position " + peek().position());
    }

    private void parseMapEntry(List<String> keys, List<AstNode> values) {
        String key;
        if (match(Token.TokenType.STRING)) {
            key = previous().value();
        } else if (match(Token.TokenType.IDENTIFIER)) {
            key = previous().value();
        } else {
            throw new ExpressionException("Expected map key at position " + peek().position());
        }
        expect(Token.TokenType.COLON, "Expected ':' after map key");
        keys.add(key);
        values.add(expression());
    }

    private List<AstNode> parseArguments() {
        List<AstNode> args = new ArrayList<>();
        if (!check(Token.TokenType.RPAREN)) {
            args.add(expression());
            while (match(Token.TokenType.COMMA)) {
                args.add(expression());
            }
        }
        return args;
    }

    private String flattenMemberAccess(AstNode.MemberAccess ma) {
        if (ma.object() instanceof AstNode.Variable v) {
            return v.name() + "." + ma.field();
        } else if (ma.object() instanceof AstNode.MemberAccess inner) {
            return flattenMemberAccess(inner) + "." + ma.field();
        }
        return ma.field();
    }

    // --- Utility methods ---

    private boolean match(Token.TokenType type) {
        if (check(type)) { advance(); return true; }
        return false;
    }

    private boolean check(Token.TokenType type) {
        return !isAtEnd() && peek().type() == type;
    }

    private Token advance() {
        if (!isAtEnd()) pos++;
        return previous();
    }

    private Token peek() { return tokens.get(pos); }
    private Token previous() { return tokens.get(pos - 1); }
    private boolean isAtEnd() { return peek().type() == Token.TokenType.EOF; }

    private Token expect(Token.TokenType type, String message) {
        if (check(type)) return advance();
        throw new ExpressionException(message + " (got " + peek().value() + " at position " + peek().position() + ")");
    }
}
