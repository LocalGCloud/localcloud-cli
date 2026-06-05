package com.localcloud.emulators.workflows.expression;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionEvaluatorTest {
    private Map<String, Object> vars;
    private Map<String, Function<List<Object>, Object>> funcs;

    @BeforeEach
    void setUp() {
        vars = new LinkedHashMap<>();
        vars.put("x", 10);
        vars.put("name", "Alice");
        vars.put("flag", true);
        vars.put("items", List.of(1, 2, 3));
        vars.put("user", new LinkedHashMap<>(Map.of(
                "name", "Bob",
                "age", 30,
                "address", Map.of("city", "NYC")
        )));
        vars.put("empty_list", List.of());
        vars.put("pi", 3.14);

        funcs = new HashMap<>();
        funcs.put("len", args -> {
            Object val = args.get(0);
            if (val instanceof List<?> l) return l.size();
            if (val instanceof String s) return s.length();
            if (val instanceof Map<?, ?> m) return m.size();
            return 0;
        });
        funcs.put("text.to_upper", args -> String.valueOf(args.get(0)).toUpperCase());
    }

    private Object eval(String expr) {
        return new ExpressionEvaluator(vars, funcs).evaluateExpression(expr);
    }

    private Object evalTemplate(String template) {
        return new ExpressionEvaluator(vars, funcs).evaluateTemplate(template);
    }

    // --- Literals ---

    @Test
    void testIntegerLiteral() {
        assertEquals(42, eval("42"));
    }

    @Test
    void testFloatLiteral() {
        assertEquals(3.14, eval("3.14"));
    }

    @Test
    void testStringLiteralDouble() {
        assertEquals("hello", eval("\"hello\""));
    }

    @Test
    void testStringLiteralSingle() {
        assertEquals("world", eval("'world'"));
    }

    @Test
    void testBooleanTrue() {
        assertEquals(true, eval("true"));
    }

    @Test
    void testBooleanFalse() {
        assertEquals(false, eval("false"));
    }

    @Test
    void testNull() {
        assertNull(eval("null"));
    }

    // --- Variables ---

    @Test
    void testVariableInteger() {
        assertEquals(10, eval("x"));
    }

    @Test
    void testVariableString() {
        assertEquals("Alice", eval("name"));
    }

    @Test
    void testVariableBoolean() {
        assertEquals(true, eval("flag"));
    }

    @Test
    void testUndefinedVariable() {
        assertThrows(ExpressionException.class, () -> eval("unknown"));
    }

    // --- Arithmetic ---

    @Test
    void testAddition() {
        assertEquals(15, eval("x + 5"));
    }

    @Test
    void testSubtraction() {
        assertEquals(7, eval("x - 3"));
    }

    @Test
    void testMultiplication() {
        assertEquals(30, eval("x * 3"));
    }

    @Test
    void testDivision() {
        assertEquals(2.0, eval("x / 5"));
    }

    @Test
    void testIntegerDivision() {
        assertEquals(3, eval("x // 3"));
    }

    @Test
    void testModulo() {
        assertEquals(1, eval("x % 3"));
    }

    @Test
    void testDivisionByZero() {
        assertThrows(ExpressionException.class, () -> eval("x / 0"));
    }

    @Test
    void testNegation() {
        assertEquals(-10, eval("-x"));
    }

    @Test
    void testStringConcat() {
        assertEquals("Alice Smith", eval("name + \" Smith\""));
    }

    @Test
    void testNumberStringConcat() {
        assertEquals("x=10", eval("\"x=\" + x"));
    }

    // --- Comparison ---

    @Test
    void testEqual() {
        assertEquals(true, eval("x == 10"));
    }

    @Test
    void testNotEqual() {
        assertEquals(true, eval("x != 5"));
    }

    @Test
    void testLessThan() {
        assertEquals(true, eval("x < 20"));
    }

    @Test
    void testGreaterThan() {
        assertEquals(true, eval("x > 5"));
    }

    @Test
    void testLessOrEqual() {
        assertEquals(true, eval("x <= 10"));
    }

    @Test
    void testGreaterOrEqual() {
        assertEquals(true, eval("x >= 10"));
    }

    // --- Logical ---

    @Test
    void testAnd() {
        assertEquals(true, eval("true and true"));
    }

    @Test
    void testOr() {
        assertEquals(true, eval("false or true"));
    }

    @Test
    void testNot() {
        assertEquals(false, eval("not true"));
    }

    @Test
    void testAndShortCircuit() {
        // false and <anything> should short-circuit and return false without evaluating right side
        assertEquals(false, eval("false and unknown_var"));
    }

    // --- Membership ---

    @Test
    void testInList() {
        assertEquals(true, eval("2 in items"));
    }

    @Test
    void testNotInList() {
        assertEquals(false, eval("5 in items"));
    }

    @Test
    void testInMap() {
        assertEquals(true, eval("\"name\" in user"));
    }

    @Test
    void testNotInMap() {
        assertEquals(false, eval("\"email\" in user"));
    }

    // --- Map/List access ---

    @Test
    void testMapDotAccess() {
        assertEquals("Bob", eval("user.name"));
    }

    @Test
    void testMapBracketAccess() {
        assertEquals(30, eval("user[\"age\"]"));
    }

    @Test
    void testNestedMapAccess() {
        assertEquals("NYC", eval("user.address.city"));
    }

    @Test
    void testListIndexAccess() {
        assertEquals(1, eval("items[0]"));
    }

    @Test
    void testListIndexOutOfBounds() {
        assertThrows(ExpressionException.class, () -> eval("items[99]"));
    }

    // --- Function calls ---

    @Test
    void testFunctionCall() {
        assertEquals(3, eval("len(items)"));
    }

    @Test
    void testNamespacedFunction() {
        assertEquals("ALICE", eval("text.to_upper(name)"));
    }

    @Test
    void testUnknownFunction() {
        assertThrows(ExpressionException.class, () -> eval("unknown_func(1)"));
    }

    // --- Operator precedence ---

    @Test
    void testPrecedenceMultOverAdd() {
        // 2 + 3 * 4 = 2 + 12 = 14
        assertEquals(14, eval("2 + 3 * 4"));
    }

    @Test
    void testParentheses() {
        assertEquals(20, eval("(2 + 3) * 4"));
    }

    @Test
    void testComplexPrecedence() {
        assertEquals(true, eval("x > 5 and x < 20"));
    }

    // --- Template evaluation ---

    @Test
    void testSingleExpression() {
        assertEquals(10, evalTemplate("${x}"));
    }

    @Test
    void testStringInterpolation() {
        assertEquals("Hello Alice!", evalTemplate("Hello ${name}!"));
    }

    @Test
    void testPlainString() {
        assertEquals("no expressions", evalTemplate("no expressions"));
    }

    // --- Edge cases ---

    @Test
    void testEmptyString() {
        assertEquals("", eval("''"));
    }

    @Test
    void testListLiteral() {
        assertEquals(List.of(1, 2, 3), eval("[1, 2, 3]"));
    }

    @Test
    void testMapLiteral() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("a", 1);
        expected.put("b", 10);
        assertEquals(expected, eval("{\"a\": 1, b: x}"));
    }

    @Test
    void testMapLiteralAccess() {
        assertEquals(3, eval("{sum: 3}.sum"));
    }

    @Test
    void testExpressionTooLong() {
        // New limit is 2048 chars
        String longExpr = "x " + "+ 1 ".repeat(600); // > 2048 chars
        assertThrows(ExpressionException.class, () -> eval(longExpr));
    }

    // --- Additional edge cases ---

    @Test
    void testOrShortCircuit() {
        // true or <anything> should short-circuit and return true without evaluating right side
        assertEquals(true, eval("true or unknown_var"));
    }

    @Test
    void testChainedComparisons() {
        assertEquals(false, eval("x == 5"));
        assertEquals(true, eval("x == 10"));
    }

    @Test
    void testFloatVariable() {
        assertEquals(3.14, eval("pi"));
    }

    @Test
    void testEmptyListVariable() {
        assertEquals(List.of(), eval("empty_list"));
    }

    @Test
    void testIntegerDivisionFloor() {
        // 7 // 2 = 3 (floor division)
        assertEquals(3, eval("7 // 2"));
    }

    @Test
    void testModuloWithInteger() {
        assertEquals(0, eval("10 % 2"));
    }

    @Test
    void testNotFalse() {
        assertEquals(true, eval("not false"));
    }

    @Test
    void testNullTemplate() {
        assertNull(new ExpressionEvaluator(vars, funcs).evaluateTemplate(null));
    }

    @Test
    void testMultipleInterpolations() {
        vars.put("greeting", "Hi");
        assertEquals("Hi Alice, you are 10!", evalTemplate("${greeting} ${name}, you are ${x}!"));
    }

    @Test
    void testListSecondElement() {
        assertEquals(2, eval("items[1]"));
    }

    @Test
    void testListThirdElement() {
        assertEquals(3, eval("items[2]"));
    }
}
