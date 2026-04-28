package com.localcloud.admin.bigtablesql;

import com.localcloud.admin.bigtablesql.SqlAstNode.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles GROUP BY grouping and aggregate function evaluation for the
 * Bigtable SQL engine.
 *
 * <p>This class provides:
 * <ul>
 *   <li>Expression evaluation against a row (for WHERE, GROUP BY keys, HAVING)</li>
 *   <li>GROUP BY grouping with aggregate computation</li>
 *   <li>All standard SQL aggregate functions: COUNT, SUM, AVG, MIN, MAX,
 *       STRING_AGG, ARRAY_AGG</li>
 * </ul>
 *
 * <p>Note: As the AST node hierarchy is extended (FunctionCall, CastExpr,
 * CaseExpr, BracketAccess, etc.), the switch expressions in this class
 * should be updated to handle the new types explicitly. Until then,
 * the {@code default} branch provides graceful fallback.
 */
public final class SqlAggregator {

    private static final Set<String> AGGREGATE_FUNCTIONS = Set.of(
            "COUNT", "SUM", "AVG", "MIN", "MAX", "STRING_AGG", "ARRAY_AGG", "COUNT_DISTINCT"
    );

    private SqlAggregator() {} // utility class

    // ─── Aggregate detection ─────────────────────────────────────────────

    /**
     * Returns true if the given function name is a known aggregate function.
     */
    public static boolean isAggregate(String name) {
        return name != null && AGGREGATE_FUNCTIONS.contains(name.toUpperCase());
    }

    // ─── Expression evaluation ───────────────────────────────────────────

    /**
     * Evaluate a scalar expression against a single row.
     * Returns the computed value (String, Long, Double, Boolean, or null).
     *
     * <p>Aggregate function calls in a non-aggregate context return null.
     * For aggregate-aware evaluation, use
     * {@link #evaluateExprWithAggregates(Expression, List)}.
     */
    public static Object evaluateExpr(Expression expr, Map<String, Object> row) {
        if (expr == null) return null;
        return switch (expr) {
            case StringLiteral s -> s.value();
            case NumberLiteral n -> n.value();
            case NullLiteral ignored -> null;
            case ColumnRefExpr ref -> {
                if ("rowkey".equalsIgnoreCase(ref.qualifier()) && ref.family() == null) {
                    yield row.get("rowKey");
                }
                if (ref.family() != null) {
                    yield row.get(ref.family() + ":" + ref.qualifier());
                }
                // Bare qualifier -- try direct lookup, then scan for family:qualifier match
                Object direct = row.get(ref.qualifier());
                if (direct != null) yield direct;
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    if (e.getKey().endsWith(":" + ref.qualifier())) {
                        yield e.getValue();
                    }
                }
                yield null;
            }
            case BinaryOp op -> evaluateBinaryOp(op, row);
            case BetweenExpr be -> {
                Object val = evaluateExpr(be.expr(), row);
                Object lo = evaluateExpr(be.low(), row);
                Object hi = evaluateExpr(be.high(), row);
                Integer cmpLo = compareValues(val, lo);
                Integer cmpHi = compareValues(val, hi);
                yield cmpLo != null && cmpHi != null && cmpLo >= 0 && cmpHi <= 0;
            }
            case LikeExpr le -> {
                Object val = evaluateExpr(le.expr(), row);
                if (val == null) yield false;
                String regex = le.pattern()
                        .replace(".", "\\.")
                        .replace("%", ".*")
                        .replace("_", ".");
                yield String.valueOf(val).matches(regex);
            }
            case InExpr ie -> {
                Object val = evaluateExpr(ie.expr(), row);
                yield ie.values().stream()
                        .anyMatch(v -> Objects.equals(asString(val), asString(evaluateExpr(v, row))));
            }
            case BooleanLiteral b -> b.value();
            case FloatLiteral f -> f.value();
            case StarExpr ignored -> "*";
            case BracketAccess ba -> {
                // cf['col'] → lookup family:qualifier in row
                if (ba.object() instanceof ColumnRefExpr ref && ba.key() instanceof StringLiteral key) {
                    String family = ref.family() != null ? ref.family() : ref.qualifier();
                    yield row.get(family + ":" + key.value());
                }
                yield null;
            }
            case FunctionCall fc -> {
                if (isAggregate(fc.name())) yield null; // handled in groupAndAggregate
                List<Object> args = fc.args().stream()
                        .map(a -> evaluateExpr(a, row))
                        .collect(java.util.stream.Collectors.toList());
                yield SqlFunctions.evaluate(fc.name(), args);
            }
            case CastExpr ce -> {
                Object val = evaluateExpr(ce.expr(), row);
                yield SqlTypes.cast(val, SqlTypes.SqlType.valueOf(ce.targetType().toUpperCase()));
            }
            case CaseExpr c -> {
                for (var when : c.whens()) {
                    Object cond = evaluateExpr(when.condition(), row);
                    if (toBool(cond)) yield evaluateExpr(when.result(), row);
                }
                yield c.elseExpr() != null ? evaluateExpr(c.elseExpr(), row) : null;
            }
            case IsNullExpr isn -> {
                Object val = evaluateExpr(isn.expr(), row);
                yield isn.negated() ? val != null : val == null;
            }
            default -> null;
        };
    }

    /**
     * Evaluate a binary operation (+, -, *, /, %, comparisons, AND, OR, ||).
     */
    private static Object evaluateBinaryOp(BinaryOp op, Map<String, Object> row) {
        String operator = op.operator().toUpperCase();

        // String concatenation: ||
        if ("||".equals(op.operator())) {
            Object left = evaluateExpr(op.left(), row);
            Object right = evaluateExpr(op.right(), row);
            return asString(left) + asString(right);
        }

        // Short-circuit logical operators
        if ("AND".equals(operator)) {
            Object left = evaluateExpr(op.left(), row);
            if (!toBool(left)) return false;
            Object right = evaluateExpr(op.right(), row);
            return toBool(right);
        }
        if ("OR".equals(operator)) {
            Object left = evaluateExpr(op.left(), row);
            if (toBool(left)) return true;
            Object right = evaluateExpr(op.right(), row);
            return toBool(right);
        }

        Object left = evaluateExpr(op.left(), row);
        Object right = evaluateExpr(op.right(), row);

        return switch (op.operator()) {
            case "+", "-", "*", "/", "%" -> arithmeticOp(op.operator(), left, right);
            case "=", "==" -> Objects.equals(asString(left), asString(right));
            case "!=", "<>" -> !Objects.equals(asString(left), asString(right));
            case "<" -> {
                Integer c = compareValues(left, right);
                yield c != null && c < 0;
            }
            case ">" -> {
                Integer c = compareValues(left, right);
                yield c != null && c > 0;
            }
            case "<=" -> {
                Integer c = compareValues(left, right);
                yield c != null && c <= 0;
            }
            case ">=" -> {
                Integer c = compareValues(left, right);
                yield c != null && c >= 0;
            }
            default -> null;
        };
    }

    /**
     * Perform arithmetic on two values, coercing to double.
     * Returns null if either operand is null or division by zero.
     */
    private static Object arithmeticOp(String op, Object left, Object right) {
        if (left == null || right == null) return null;
        double l = toDouble(left);
        double r = toDouble(right);
        double result = switch (op) {
            case "+" -> l + r;
            case "-" -> l - r;
            case "*" -> l * r;
            case "/" -> {
                if (r == 0) yield Double.NaN;
                yield l / r;
            }
            case "%" -> {
                if (r == 0) yield Double.NaN;
                yield l % r;
            }
            default -> Double.NaN;
        };
        if (Double.isNaN(result)) return null;
        // Return integer if both operands were integral and result is integral
        if (isIntegral(left) && isIntegral(right) && result == Math.floor(result)
                && !Double.isInfinite(result)) {
            return (long) result;
        }
        return result;
    }

    // ─── GROUP BY + Aggregate processing ─────────────────────────────────

    /**
     * Group rows by the given GROUP BY expressions, compute aggregates
     * for each group's SELECT columns, and apply an optional HAVING filter.
     *
     * @param rows           the input rows (each row is a column-name to value map)
     * @param groupByExprs   the GROUP BY expressions (null if no GROUP BY)
     * @param selectColumns  the SELECT columns with expressions and aliases
     * @param having         the HAVING filter expression (null if no HAVING)
     * @return the aggregated result rows
     */
    public static List<Map<String, Object>> groupAndAggregate(
            List<Map<String, Object>> rows,
            List<Expression> groupByExprs,
            List<SelectColumn> selectColumns,
            Expression having) {

        // 1. Group rows by GROUP BY key values
        Map<List<Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (var row : rows) {
            List<Object> key;
            if (groupByExprs != null && !groupByExprs.isEmpty()) {
                key = groupByExprs.stream()
                        .map(e -> evaluateExpr(e, row))
                        .collect(Collectors.toList());
            } else {
                key = List.of("__all__"); // no GROUP BY = single group
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        // 2. For each group, compute SELECT columns (with aggregate awareness)
        List<Map<String, Object>> result = new ArrayList<>();
        boolean hasGroupBy = groupByExprs != null && !groupByExprs.isEmpty();

        for (var entry : groups.entrySet()) {
            List<Map<String, Object>> groupRows = entry.getValue();
            Map<String, Object> outputRow = new LinkedHashMap<>();

            for (var col : selectColumns) {
                String alias = col.alias();
                Object value = evaluateSelectColumn(col.expr(), groupRows, hasGroupBy);

                // Generate alias if not provided
                if (alias == null) {
                    alias = exprToAlias(col.expr());
                }
                outputRow.put(alias, value);
            }

            // 3. Apply HAVING filter (aggregate-aware)
            if (having != null) {
                Object havingResult = evaluateExprWithAggregates(having, groupRows);
                if (!toBool(havingResult)) continue;
            }

            result.add(outputRow);
        }
        return result;
    }

    /**
     * Evaluate a SELECT column expression, dispatching to aggregate computation
     * when the expression is an aggregate function call.
     */
    private static Object evaluateSelectColumn(Expression expr, List<Map<String, Object>> groupRows, boolean hasGroupBy) {
        // Check if this expression contains aggregate functions
        if (containsAggregate(expr)) {
            return evaluateExprWithAggregates(expr, groupRows);
        }
        // Non-aggregate: use first row's value (GROUP BY guarantees same value across group)
        return groupRows.isEmpty() ? null : evaluateExpr(expr, groupRows.get(0));
    }

    /**
     * Evaluate an expression in aggregate context, where aggregate function calls
     * are resolved over the group rows. Non-aggregate sub-expressions use the
     * first row of the group.
     *
     * <p>This handles HAVING conditions like {@code COUNT(*) > 1} by recursively
     * evaluating aggregate calls within binary operations.
     */
    public static Object evaluateExprWithAggregates(Expression expr, List<Map<String, Object>> groupRows) {
        if (expr == null || groupRows == null || groupRows.isEmpty()) return null;

        return switch (expr) {
            case StringLiteral s -> s.value();
            case NumberLiteral n -> n.value();
            case NullLiteral ignored -> null;
            case ColumnRefExpr ref -> evaluateExpr(ref, groupRows.get(0));
            case BinaryOp op -> {
                String operator = op.operator().toUpperCase();

                // Short-circuit logical operators
                if ("AND".equals(operator)) {
                    Object left = evaluateExprWithAggregates(op.left(), groupRows);
                    if (!toBool(left)) yield false;
                    Object right = evaluateExprWithAggregates(op.right(), groupRows);
                    yield toBool(right);
                }
                if ("OR".equals(operator)) {
                    Object left = evaluateExprWithAggregates(op.left(), groupRows);
                    if (toBool(left)) yield true;
                    Object right = evaluateExprWithAggregates(op.right(), groupRows);
                    yield toBool(right);
                }

                Object left = evaluateExprWithAggregates(op.left(), groupRows);
                Object right = evaluateExprWithAggregates(op.right(), groupRows);

                yield switch (op.operator()) {
                    case "+", "-", "*", "/", "%" -> arithmeticOp(op.operator(), left, right);
                    case "=", "==" -> Objects.equals(asString(left), asString(right));
                    case "!=", "<>" -> !Objects.equals(asString(left), asString(right));
                    case "<" -> {
                        Integer c = compareValues(left, right);
                        yield c != null && c < 0;
                    }
                    case ">" -> {
                        Integer c = compareValues(left, right);
                        yield c != null && c > 0;
                    }
                    case "<=" -> {
                        Integer c = compareValues(left, right);
                        yield c != null && c <= 0;
                    }
                    case ">=" -> {
                        Integer c = compareValues(left, right);
                        yield c != null && c >= 0;
                    }
                    case "||" -> asString(left) + asString(right);
                    default -> null;
                };
            }
            case BetweenExpr be -> {
                Object val = evaluateExprWithAggregates(be.expr(), groupRows);
                Object lo = evaluateExprWithAggregates(be.low(), groupRows);
                Object hi = evaluateExprWithAggregates(be.high(), groupRows);
                Integer cmpLo = compareValues(val, lo);
                Integer cmpHi = compareValues(val, hi);
                yield cmpLo != null && cmpHi != null && cmpLo >= 0 && cmpHi <= 0;
            }
            case InExpr ie -> {
                Object val = evaluateExprWithAggregates(ie.expr(), groupRows);
                yield ie.values().stream()
                        .anyMatch(v -> Objects.equals(asString(val),
                                asString(evaluateExprWithAggregates(v, groupRows))));
            }
            case FunctionCall fc -> {
                if (isAggregate(fc.name())) {
                    yield computeAggregate(fc.name(), fc.args(), groupRows);
                }
                // Scalar function — evaluate args with aggregate awareness
                List<Object> args = fc.args().stream()
                        .map(a -> evaluateExprWithAggregates(a, groupRows))
                        .collect(java.util.stream.Collectors.toList());
                yield SqlFunctions.evaluate(fc.name(), args);
            }
            case CastExpr ce -> {
                Object val = evaluateExprWithAggregates(ce.expr(), groupRows);
                yield SqlTypes.cast(val, SqlTypes.SqlType.valueOf(ce.targetType().toUpperCase()));
            }
            case CaseExpr c -> {
                for (var when : c.whens()) {
                    Object cond = evaluateExprWithAggregates(when.condition(), groupRows);
                    if (toBool(cond)) yield evaluateExprWithAggregates(when.result(), groupRows);
                }
                yield c.elseExpr() != null ? evaluateExprWithAggregates(c.elseExpr(), groupRows) : null;
            }
            case BracketAccess ba -> evaluateExpr(ba, groupRows.get(0));
            case IsNullExpr isn -> evaluateExpr(isn, groupRows.get(0));
            case BooleanLiteral b -> b.value();
            case FloatLiteral f -> f.value();
            default -> evaluateExpr(expr, groupRows.get(0));
        };
    }

    /**
     * Check whether an expression tree contains any aggregate function calls.
     * Currently checks BinaryOp children recursively; will be extended when
     * FunctionCall is added to SqlAstNode.
     */
    public static boolean containsAggregate(Expression expr) {
        if (expr == null) return false;
        return switch (expr) {
            case FunctionCall fc -> isAggregate(fc.name())
                    || fc.args().stream().anyMatch(SqlAggregator::containsAggregate);
            case CastExpr ce -> containsAggregate(ce.expr());
            case BinaryOp op -> containsAggregate(op.left()) || containsAggregate(op.right());
            case BetweenExpr be -> containsAggregate(be.expr())
                    || containsAggregate(be.low()) || containsAggregate(be.high());
            case InExpr ie -> containsAggregate(ie.expr())
                    || ie.values().stream().anyMatch(SqlAggregator::containsAggregate);
            // Future: case FunctionCall fc -> isAggregate(fc.name()) || fc.args().stream()...
            default -> false;
        };
    }

    // ─── Aggregate computation ───────────────────────────────────────────

    /**
     * Compute an aggregate function over a group of rows.
     *
     * <p>Supported aggregates: COUNT, SUM, AVG, MIN, MAX, STRING_AGG, ARRAY_AGG.
     *
     * @param name      the aggregate function name (case-insensitive)
     * @param argExprs  the argument expressions
     * @param rows      the group of rows to aggregate over
     * @return the aggregate result
     */
    public static Object computeAggregate(String name, List<Expression> argExprs, List<Map<String, Object>> rows) {
        String upper = name.toUpperCase();
        return switch (upper) {
            case "COUNT" -> {
                // COUNT(*) — count all rows
                if (argExprs.size() == 1 && isStarRef(argExprs.get(0))) {
                    yield (long) rows.size();
                }
                // COUNT(expr) — count non-null values
                yield rows.stream()
                        .map(r -> evaluateExpr(argExprs.get(0), r))
                        .filter(Objects::nonNull)
                        .count();
            }
            case "COUNT_DISTINCT" -> {
                if (argExprs.isEmpty()) throw new BigtableSqlException("COUNT_DISTINCT requires an argument");
                yield rows.stream()
                        .map(r -> evaluateExpr(argExprs.get(0), r))
                        .filter(Objects::nonNull)
                        .map(String::valueOf)
                        .distinct()
                        .count();
            }
            case "SUM" -> {
                if (argExprs.isEmpty()) throw new BigtableSqlException("SUM requires an argument");
                double sum = rows.stream()
                        .map(r -> evaluateExpr(argExprs.get(0), r))
                        .filter(Objects::nonNull)
                        .mapToDouble(SqlAggregator::toDouble)
                        .sum();
                // Return long if all values are integral
                boolean allIntegral = rows.stream()
                        .map(r -> evaluateExpr(argExprs.get(0), r))
                        .filter(Objects::nonNull)
                        .allMatch(SqlAggregator::isIntegral);
                yield allIntegral && sum == Math.floor(sum) ? (long) sum : sum;
            }
            case "AVG" -> {
                if (argExprs.isEmpty()) throw new BigtableSqlException("AVG requires an argument");
                double[] values = rows.stream()
                        .map(r -> evaluateExpr(argExprs.get(0), r))
                        .filter(Objects::nonNull)
                        .mapToDouble(SqlAggregator::toDouble)
                        .toArray();
                yield values.length == 0 ? null : Arrays.stream(values).average().orElse(0);
            }
            case "MIN" -> {
                if (argExprs.isEmpty()) throw new BigtableSqlException("MIN requires an argument");
                yield rows.stream()
                        .map(r -> evaluateExpr(argExprs.get(0), r))
                        .filter(Objects::nonNull)
                        .min((a, b) -> {
                            Integer cmp = compareValues(a, b);
                            return cmp != null ? cmp : 0;
                        })
                        .orElse(null);
            }
            case "MAX" -> {
                if (argExprs.isEmpty()) throw new BigtableSqlException("MAX requires an argument");
                yield rows.stream()
                        .map(r -> evaluateExpr(argExprs.get(0), r))
                        .filter(Objects::nonNull)
                        .max((a, b) -> {
                            Integer cmp = compareValues(a, b);
                            return cmp != null ? cmp : 0;
                        })
                        .orElse(null);
            }
            case "STRING_AGG" -> {
                if (argExprs.isEmpty()) throw new BigtableSqlException("STRING_AGG requires at least one argument");
                String sep = argExprs.size() > 1
                        ? String.valueOf(evaluateExpr(argExprs.get(1), rows.get(0)))
                        : ",";
                yield rows.stream()
                        .map(r -> evaluateExpr(argExprs.get(0), r))
                        .filter(Objects::nonNull)
                        .map(String::valueOf)
                        .collect(Collectors.joining(sep));
            }
            case "ARRAY_AGG" -> {
                if (argExprs.isEmpty()) throw new BigtableSqlException("ARRAY_AGG requires an argument");
                List<String> items = rows.stream()
                        .map(r -> evaluateExpr(argExprs.get(0), r))
                        .filter(Objects::nonNull)
                        .map(String::valueOf)
                        .collect(Collectors.toList());
                yield items.toString();
            }
            default -> throw new BigtableSqlException("Unknown aggregate function: " + name);
        };
    }

    /**
     * Check if an expression is the star wildcard ({@code *}).
     */
    private static boolean isStarRef(Expression expr) {
        return expr instanceof StarExpr;
    }

    // ─── Column alias generation ─────────────────────────────────────────

    /**
     * Generate a display alias from an expression when no explicit AS alias
     * is provided. Produces human-readable names like "cf:col" or "COUNT".
     */
    public static String exprToAlias(Expression expr) {
        if (expr == null) return "expr";
        return switch (expr) {
            case ColumnRefExpr ref -> ref.family() != null
                    ? ref.family() + ":" + ref.qualifier()
                    : ref.qualifier();
            case StringLiteral s -> "'" + s.value() + "'";
            case NumberLiteral n -> String.valueOf(n.value());
            case BinaryOp op -> exprToAlias(op.left()) + " " + op.operator() + " " + exprToAlias(op.right());
            // Future: case FunctionCall fc -> fc.name();
            // Future: case CastExpr ce -> "CAST";
            // Future: case BracketAccess ba -> family:key format
            default -> "expr";
        };
    }

    // ─── Type coercion and comparison helpers ────────────────────────────

    /**
     * Compare two values, returning negative/zero/positive like Comparator,
     * or null if the values are not comparable.
     * Attempts numeric comparison first, then falls back to string comparison.
     */
    @SuppressWarnings("unchecked")
    public static Integer compareValues(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;

        // Both numeric
        if (isNumeric(a) && isNumeric(b)) {
            return Double.compare(toDouble(a), toDouble(b));
        }

        // Try parsing as numbers
        Double da = tryParseDouble(a);
        Double db = tryParseDouble(b);
        if (da != null && db != null) {
            return Double.compare(da, db);
        }

        // Fall back to string comparison
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    /**
     * Cast a value to the given SQL type name (STRING, INT64, FLOAT64, BOOL, BYTES).
     * Used for CAST expressions; inlined here since SqlTypes.java does not exist yet.
     */
    public static Object castValue(Object val, String targetType) {
        if (val == null) return null;
        String type = targetType.toUpperCase();
        return switch (type) {
            case "STRING" -> String.valueOf(val);
            case "INT64", "INTEGER", "INT" -> {
                if (val instanceof Number n) yield n.longValue();
                try { yield Long.parseLong(String.valueOf(val).trim()); }
                catch (NumberFormatException e) {
                    try { yield (long) Double.parseDouble(String.valueOf(val).trim()); }
                    catch (NumberFormatException e2) { yield null; }
                }
            }
            case "FLOAT64", "FLOAT", "DOUBLE" -> {
                if (val instanceof Number n) yield n.doubleValue();
                try { yield Double.parseDouble(String.valueOf(val).trim()); }
                catch (NumberFormatException e) { yield null; }
            }
            case "BOOL", "BOOLEAN" -> toBool(val);
            case "BYTES" -> {
                if (val instanceof byte[] b) yield b;
                yield String.valueOf(val).getBytes();
            }
            default -> String.valueOf(val);
        };
    }

    // ─── Private helpers ─────────────────────────────────────────────────

    /**
     * Coerce a value to boolean. Null, empty string, "0", "false", "null"
     * are false; everything else is true.
     */
    static boolean toBool(Object val) {
        if (val == null) return false;
        if (val instanceof Boolean b) return b;
        if (val instanceof Number n) return n.doubleValue() != 0;
        String s = String.valueOf(val);
        return !s.isEmpty()
                && !"0".equals(s)
                && !"false".equalsIgnoreCase(s)
                && !"null".equalsIgnoreCase(s);
    }

    /**
     * Coerce a value to double. Returns 0 for null or unparseable strings.
     */
    static double toDouble(Object val) {
        if (val == null) return 0;
        if (val instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(val).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Coerce a value to its string representation, returning null for null input.
     */
    static String asString(Object val) {
        return val == null ? null : String.valueOf(val);
    }

    /**
     * Check whether a value is a numeric type (Number instance).
     */
    private static boolean isNumeric(Object val) {
        return val instanceof Number;
    }

    /**
     * Check whether a value is an integral number (long, int, short, byte)
     * or a string that parses to a long without fraction.
     */
    private static boolean isIntegral(Object val) {
        if (val instanceof Long || val instanceof Integer || val instanceof Short || val instanceof Byte) {
            return true;
        }
        if (val instanceof Double d) return d == Math.floor(d) && !Double.isInfinite(d);
        if (val instanceof Float f) return f == Math.floor(f) && !Float.isInfinite(f);
        if (val instanceof String s) {
            try {
                Long.parseLong(s.trim());
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Try to parse a value as a Double. Returns null if not parseable.
     */
    private static Double tryParseDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(val).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
