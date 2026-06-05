package com.localcloud.emulators.workflows.expression;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates parsed AST nodes against a variable context and function registry.
 */
public class ExpressionEvaluator {

    private static final Pattern EXPR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final Map<String, Object> variables;
    private final Map<String, Function<List<Object>, Object>> functions;

    public ExpressionEvaluator(Map<String, Object> variables, Map<String, Function<List<Object>, Object>> functions) {
        this.variables = variables;
        this.functions = functions != null ? functions : Collections.emptyMap();
    }

    /**
     * Evaluate a string that may contain ${...} expressions.
     * If the entire string is a single ${...}, return the raw evaluated value.
     * If mixed with text, perform string interpolation.
     */
    public Object evaluateTemplate(String template) {
        if (template == null) return null;
        String trimmed = template.trim();

        // Find expression boundaries using brace counting (respects strings)
        List<int[]> exprRanges = findExpressionRanges(trimmed);

        if (exprRanges.isEmpty()) return template;

        // If entire string is a single expression, return raw value
        if (exprRanges.size() == 1 && exprRanges.get(0)[0] == 0 && exprRanges.get(0)[1] == trimmed.length()) {
            String inner = trimmed.substring(2, trimmed.length() - 1);
            return evaluateExpression(inner);
        }

        // String interpolation
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;
        for (int[] range : exprRanges) {
            sb.append(template, lastEnd, range[0]);
            String inner = template.substring(range[0] + 2, range[1] - 1);
            Object val = evaluateExpression(inner);
            sb.append(stringify(val));
            lastEnd = range[1];
        }
        sb.append(template, lastEnd, template.length());
        return sb.toString();
    }

    /**
     * Find ${...} expression ranges using brace counting, respecting string literals.
     * Returns list of [start, end) pairs where start is index of '$' and end is index after '}'.
     */
    private List<int[]> findExpressionRanges(String text) {
        List<int[]> ranges = new java.util.ArrayList<>();
        int i = 0;
        while (i < text.length() - 1) {
            if (text.charAt(i) == '$' && text.charAt(i + 1) == '{') {
                int start = i;
                int depth = 1;
                int j = i + 2;
                boolean inSingleQuote = false, inDoubleQuote = false;
                while (j < text.length() && depth > 0) {
                    char c = text.charAt(j);
                    if (inSingleQuote) {
                        if (c == '\\' && j + 1 < text.length()) { j += 2; continue; }
                        if (c == '\'') inSingleQuote = false;
                    } else if (inDoubleQuote) {
                        if (c == '\\' && j + 1 < text.length()) { j += 2; continue; }
                        if (c == '"') inDoubleQuote = false;
                    } else {
                        if (c == '\'') inSingleQuote = true;
                        else if (c == '"') inDoubleQuote = true;
                        else if (c == '{') depth++;
                        else if (c == '}') depth--;
                    }
                    j++;
                }
                if (depth == 0) {
                    ranges.add(new int[]{start, j});
                    i = j;
                } else {
                    i++;
                }
            } else {
                i++;
            }
        }
        return ranges;
    }

    /**
     * Evaluate a raw expression string (without ${} delimiters).
     */
    public Object evaluateExpression(String expr) {
        if (expr == null || expr.isBlank()) return null;
        if (expr.length() > 2048) {
            throw new ExpressionException("Expression exceeds maximum length of 2048 characters");
        }
        List<Token> tokens = new ExpressionTokenizer(expr.trim()).tokenize();
        AstNode ast = new ExpressionParser(tokens).parse();
        return evaluate(ast);
    }

    /**
     * Evaluate an AST node.
     */
    @SuppressWarnings("unchecked")
    public Object evaluate(AstNode node) {
        return switch (node) {
            case AstNode.NumberLiteral n -> {
                // Return integer if whole number
                if (n.value() == Math.floor(n.value()) && !Double.isInfinite(n.value())) {
                    long l = (long) n.value();
                    if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) yield (int) l;
                    yield l;
                }
                yield n.value();
            }
            case AstNode.StringLiteral s -> s.value();
            case AstNode.BooleanLiteral b -> b.value();
            case AstNode.NullLiteral ignored -> null;
            case AstNode.Variable v -> {
                if (!variables.containsKey(v.name())) {
                    throw new ExpressionException("Undefined variable: " + v.name());
                }
                yield variables.get(v.name());
            }
            case AstNode.ListLiteral l -> {
                List<Object> list = new ArrayList<>();
                for (AstNode elem : l.elements()) list.add(evaluate(elem));
                yield list;
            }
            case AstNode.MapLiteral m -> {
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < m.keys().size(); i++) {
                    map.put(m.keys().get(i), evaluate(m.values().get(i)));
                }
                yield map;
            }
            case AstNode.UnaryOp u -> evaluateUnary(u);
            case AstNode.BinaryOp b -> evaluateBinary(b);
            case AstNode.FunctionCall f -> {
                Function<List<Object>, Object> func = functions.get(f.name());
                if (func == null) {
                    throw new ExpressionException("Unknown function: " + f.name());
                }
                List<Object> args = new ArrayList<>();
                for (AstNode arg : f.arguments()) args.add(evaluate(arg));
                yield func.apply(args);
            }
            case AstNode.MemberAccess ma -> {
                Object obj = evaluate(ma.object());
                if (obj instanceof Map<?, ?> map) {
                    if (!map.containsKey(ma.field())) {
                        throw new ExpressionException("Key not found: " + ma.field());
                    }
                    yield map.get(ma.field());
                }
                throw new ExpressionException("Cannot access field '" + ma.field() + "' on " + (obj == null ? "null" : obj.getClass().getSimpleName()));
            }
            case AstNode.IndexAccess ia -> {
                Object obj = evaluate(ia.object());
                Object idx = evaluate(ia.index());
                if (obj instanceof List<?> list) {
                    int i = toInt(idx);
                    if (i < 0 || i >= list.size()) throw new ExpressionException("Index out of bounds: " + i);
                    yield list.get(i);
                }
                if (obj instanceof Map<?, ?> map) {
                    yield map.get(stringify(idx));
                }
                throw new ExpressionException("Cannot index into " + (obj == null ? "null" : obj.getClass().getSimpleName()));
            }
        };
    }

    private Object evaluateUnary(AstNode.UnaryOp u) {
        Object val = evaluate(u.operand());
        return switch (u.operator()) {
            case "not" -> !toBool(val);
            case "-" -> {
                if (val instanceof Integer i) yield -i;
                if (val instanceof Long l) yield -l;
                yield -toDouble(val);
            }
            default -> throw new ExpressionException("Unknown unary operator: " + u.operator());
        };
    }

    @SuppressWarnings("unchecked")
    private Object evaluateBinary(AstNode.BinaryOp b) {
        // Short-circuit for logical operators
        if ("and".equals(b.operator())) {
            Object left = evaluate(b.left());
            if (!toBool(left)) return false;
            return toBool(evaluate(b.right()));
        }
        if ("or".equals(b.operator())) {
            Object left = evaluate(b.left());
            if (toBool(left)) return true;
            return toBool(evaluate(b.right()));
        }

        Object left = evaluate(b.left());
        Object right = evaluate(b.right());

        return switch (b.operator()) {
            case "+" -> {
                if (left instanceof String || right instanceof String) {
                    yield stringify(left) + stringify(right);
                }
                if (left instanceof Integer li && right instanceof Integer ri) yield li + ri;
                yield toDouble(left) + toDouble(right);
            }
            case "-" -> {
                if (left instanceof Integer li && right instanceof Integer ri) yield li - ri;
                yield toDouble(left) - toDouble(right);
            }
            case "*" -> {
                if (left instanceof Integer li && right instanceof Integer ri) yield li * ri;
                yield toDouble(left) * toDouble(right);
            }
            case "/" -> {
                double r = toDouble(right);
                if (r == 0) throw new ExpressionException("Division by zero");
                if (left instanceof Integer li && right instanceof Integer ri) yield (double) li / ri;
                yield toDouble(left) / r;
            }
            case "//" -> {
                double r = toDouble(right);
                if (r == 0) throw new ExpressionException("Division by zero");
                long result = (long) Math.floor(toDouble(left) / r);
                if (result >= Integer.MIN_VALUE && result <= Integer.MAX_VALUE) yield (int) result;
                yield result;
            }
            case "%" -> {
                double r = toDouble(right);
                if (r == 0) throw new ExpressionException("Division by zero");
                if (left instanceof Integer li && right instanceof Integer ri) yield li % ri;
                yield toDouble(left) % r;
            }
            case "==" -> Objects.equals(left, right);
            case "!=" -> !Objects.equals(left, right);
            case "<" -> compareValues(left, right) < 0;
            case ">" -> compareValues(left, right) > 0;
            case "<=" -> compareValues(left, right) <= 0;
            case ">=" -> compareValues(left, right) >= 0;
            case "in" -> {
                if (right instanceof List<?> list) yield list.contains(left);
                if (right instanceof Map<?, ?> map) yield map.containsKey(stringify(left));
                throw new ExpressionException("'in' operator requires a list or map on the right side");
            }
            default -> throw new ExpressionException("Unknown operator: " + b.operator());
        };
    }

    // --- Type coercion helpers ---

    private double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) {
            try { return Double.parseDouble(s); }
            catch (NumberFormatException e) { throw new ExpressionException("Cannot convert '" + s + "' to number"); }
        }
        throw new ExpressionException("Cannot convert " + (val == null ? "null" : val.getClass().getSimpleName()) + " to number");
    }

    private int toInt(Object val) {
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); }
            catch (NumberFormatException e) { throw new ExpressionException("Cannot convert '" + s + "' to integer"); }
        }
        throw new ExpressionException("Cannot convert to integer");
    }

    private boolean toBool(Object val) {
        if (val instanceof Boolean b) return b;
        if (val == null) return false;
        if (val instanceof Number n) return n.doubleValue() != 0;
        if (val instanceof String s) return !s.isEmpty();
        if (val instanceof List<?> l) return !l.isEmpty();
        if (val instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }

    public static String stringify(Object val) {
        if (val == null) return "null";
        if (val instanceof String s) return s;
        if (val instanceof Double d) {
            if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d.doubleValue());
            return String.valueOf(d);
        }
        return String.valueOf(val);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareValues(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue());
        }
        if (left instanceof String ls && right instanceof String rs) {
            return ls.compareTo(rs);
        }
        if (left instanceof Comparable c && right != null) {
            try { return c.compareTo(right); }
            catch (ClassCastException e) { /* fall through */ }
        }
        throw new ExpressionException("Cannot compare " +
            (left == null ? "null" : left.getClass().getSimpleName()) + " and " +
            (right == null ? "null" : right.getClass().getSimpleName()));
    }
}
