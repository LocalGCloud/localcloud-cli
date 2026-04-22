package com.localcloud.emulators.workflows.stdlib;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class StdlibRegistryTest {
    private StdlibRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new StdlibRegistry();
    }

    private Object call(String name, Object... args) {
        var func = registry.get(name);
        assertNotNull(func, "Function not found: " + name);
        return func.apply(new ArrayList<>(Arrays.asList(args)));
    }

    // --- json ---

    @Test
    void testJsonDecode() {
        Object result = call("json.decode", "{\"a\":1}");
        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(1, map.get("a"));
    }

    @Test
    void testJsonEncodeMap() {
        // Jackson serializes Map.of("a", 1) — key order may vary, check by round-tripping
        String json = (String) call("json.encode", Map.of("a", 1));
        assertNotNull(json);
        assertTrue(json.contains("\"a\""));
        assertTrue(json.contains("1"));
    }

    @Test
    void testJsonDecodeList() {
        Object result = call("json.decode", "[1,2,3]");
        assertTrue(result instanceof List);
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) result;
        assertEquals(3, list.size());
        assertEquals(1, list.get(0));
    }

    @Test
    void testJsonEncodeString() {
        assertEquals("\"hello\"", call("json.encode", "hello"));
    }

    @Test
    void testJsonDecodeInvalid() {
        assertThrows(RuntimeException.class, () -> call("json.decode", "not json"));
    }

    // --- base64 ---

    @Test
    void testBase64Encode() {
        assertEquals("SGVsbG8=", call("base64.encode", "Hello"));
    }

    @Test
    void testBase64Decode() {
        assertEquals("Hello", call("base64.decode", "SGVsbG8="));
    }

    @Test
    void testBase64RoundTrip() {
        String original = "Cloud Workflows Emulator";
        String encoded = (String) call("base64.encode", original);
        String decoded = (String) call("base64.decode", encoded);
        assertEquals(original, decoded);
    }

    @Test
    void testBase64DecodeInvalid() {
        assertThrows(RuntimeException.class, () -> call("base64.decode", "!!invalid!!"));
    }

    // --- math ---

    @Test
    void testMathAbsNegative() {
        assertEquals(5, call("math.abs", -5));
    }

    @Test
    void testMathAbsPositive() {
        assertEquals(5, call("math.abs", 5));
    }

    @Test
    void testMathAbsDouble() {
        assertEquals(3.5, call("math.abs", -3.5));
    }

    @Test
    void testMathCeil() {
        assertEquals(4, call("math.ceil", 3.2));
    }

    @Test
    void testMathCeilExact() {
        assertEquals(3, call("math.ceil", 3.0));
    }

    @Test
    void testMathFloor() {
        assertEquals(3, call("math.floor", 3.9));
    }

    @Test
    void testMathFloorNegative() {
        assertEquals(-4, call("math.floor", -3.1));
    }

    @Test
    void testMathRound() {
        assertEquals(4, call("math.round", 3.6));
    }

    @Test
    void testMathRoundDown() {
        assertEquals(3, call("math.round", 3.4));
    }

    @Test
    void testMathMax() {
        assertEquals(10, call("math.max", 5, 10));
    }

    @Test
    void testMathMaxFirstLarger() {
        assertEquals(10, call("math.max", 10, 5));
    }

    @Test
    void testMathMin() {
        assertEquals(5, call("math.min", 5, 10));
    }

    @Test
    void testMathMinSecondSmaller() {
        assertEquals(3, call("math.min", 7, 3));
    }

    // --- text ---

    @Test
    void testTextToUpper() {
        assertEquals("HELLO", call("text.to_upper", "hello"));
    }

    @Test
    void testTextToLower() {
        assertEquals("hello", call("text.to_lower", "HELLO"));
    }

    @Test
    void testTextSplit() {
        assertEquals(Arrays.asList("a", "b", "c"), call("text.split", "a,b,c", ","));
    }

    @Test
    void testTextSubstring() {
        assertEquals("ell", call("text.substring", "hello", 1, 4));
    }

    @Test
    void testTextSubstringFull() {
        assertEquals("hello", call("text.substring", "hello", 0, 5));
    }

    @Test
    void testTextReplaceAll() {
        assertEquals("h-ll-", call("text.replace_all", "hello", "[eo]", "-"));
    }

    @Test
    void testTextMatchRegexTrue() {
        assertEquals(true, call("text.match_regex", "hello123", ".*\\d+"));
    }

    @Test
    void testTextMatchRegexFalse() {
        assertEquals(false, call("text.match_regex", "hello", ".*\\d+"));
    }

    @Test
    void testTextFindAll() {
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) call("text.find_all", "abc123def456", "\\d+");
        assertEquals(List.of("123", "456"), result);
    }

    @Test
    void testTextFindAllNoMatch() {
        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) call("text.find_all", "abc", "\\d+");
        assertTrue(result.isEmpty());
    }

    @Test
    void testTextUrlEncode() {
        assertEquals("hello+world", call("text.url_encode", "hello world"));
    }

    @Test
    void testTextUrlDecode() {
        assertEquals("hello world", call("text.url_decode", "hello+world"));
    }

    @Test
    void testTextUrlEncodeSpecialChars() {
        String encoded = (String) call("text.url_encode", "a=1&b=2");
        assertNotNull(encoded);
        assertFalse(encoded.contains("&") && encoded.contains("=") && !encoded.contains("%"));
    }

    // --- list ---

    @Test
    void testListConcat() {
        assertEquals(List.of(1, 2, 3, 4),
                call("list.concat", new ArrayList<>(List.of(1, 2)), new ArrayList<>(List.of(3, 4))));
    }

    @Test
    void testListConcatEmpty() {
        assertEquals(List.of(1, 2),
                call("list.concat", new ArrayList<>(List.of(1, 2)), new ArrayList<>()));
    }

    @Test
    void testListPrepend() {
        assertEquals(List.of(0, 1, 2),
                call("list.prepend", new ArrayList<>(List.of(1, 2)), 0));
    }

    @Test
    void testListSort() {
        assertEquals(List.of(1, 2, 3),
                call("list.sort", new ArrayList<>(List.of(3, 1, 2))));
    }

    @Test
    void testListSortStrings() {
        assertEquals(List.of("apple", "banana", "cherry"),
                call("list.sort", new ArrayList<>(List.of("cherry", "apple", "banana"))));
    }

    @Test
    void testLen() {
        assertEquals(3, call("len", List.of(1, 2, 3)));
    }

    @Test
    void testLenString() {
        assertEquals(5, call("len", "hello"));
    }

    @Test
    void testLenMap() {
        assertEquals(2, call("len", Map.of("a", 1, "b", 2)));
    }

    @Test
    void testLenEmpty() {
        assertEquals(0, call("len", List.of()));
    }

    // --- map ---

    @Test
    void testMapGet() {
        assertEquals(1, call("map.get", new LinkedHashMap<>(Map.of("a", 1)), "a"));
    }

    @Test
    void testMapGetDefault() {
        assertEquals(99, call("map.get", new LinkedHashMap<>(Map.of("a", 1)), "b", 99));
    }

    @Test
    void testMapGetMissingNoDefault() {
        assertNull(call("map.get", new LinkedHashMap<>(Map.of("a", 1)), "missing"));
    }

    @Test
    void testMapKeys() {
        @SuppressWarnings("unchecked")
        List<String> keys = (List<String>) call("map.keys", new LinkedHashMap<>(Map.of("a", 1, "b", 2)));
        assertEquals(2, keys.size());
        assertTrue(keys.containsAll(List.of("a", "b")));
    }

    @Test
    void testMapValues() {
        @SuppressWarnings("unchecked")
        List<Object> values = (List<Object>) call("map.values", new LinkedHashMap<>(Map.of("a", 1, "b", 2)));
        assertEquals(2, values.size());
        assertTrue(values.containsAll(List.of(1, 2)));
    }

    @Test
    void testMapMerge() {
        Map<String, Object> m1 = new LinkedHashMap<>(Map.of("a", 1, "b", 2));
        Map<String, Object> m2 = new LinkedHashMap<>(Map.of("c", 3));
        Object result = call("map.merge", m1, m2);
        assertTrue(result instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = (Map<String, Object>) result;
        assertEquals(3, merged.size());
        assertEquals(1, merged.get("a"));
        assertEquals(2, merged.get("b"));
        assertEquals(3, merged.get("c"));
    }

    @Test
    void testMapMergeOverwrite() {
        Map<String, Object> m1 = new LinkedHashMap<>(Map.of("a", 1));
        Map<String, Object> m2 = new LinkedHashMap<>(Map.of("a", 99));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) call("map.merge", m1, m2);
        assertEquals(99, result.get("a"));
    }

    // --- type cast ---

    @Test
    void testIntCastDouble() {
        assertEquals(3, call("int", 3.7));
    }

    @Test
    void testIntCastString() {
        assertEquals(42, call("int", "42"));
    }

    @Test
    void testIntCastBoolean() {
        assertEquals(1, call("int", true));
        assertEquals(0, call("int", false));
    }

    @Test
    void testDoubleCastInt() {
        assertEquals(3.0, call("double", 3));
    }

    @Test
    void testDoubleCastString() {
        assertEquals(3.14, call("double", "3.14"));
    }

    @Test
    void testStringCast() {
        assertEquals("42", call("string", 42));
    }

    @Test
    void testStringCastDouble() {
        assertEquals("3.14", call("string", 3.14));
    }

    @Test
    void testStringCastBoolean() {
        assertEquals("true", call("string", true));
    }

    // --- sys ---

    @Test
    void testSysNow() {
        String now = (String) call("sys.now");
        assertNotNull(now);
        assertTrue(now.contains("T"), "sys.now should return ISO-8601 format containing 'T'");
        assertTrue(now.contains("Z") || now.contains("+"), "sys.now should contain timezone info");
    }

    @Test
    void testSysGetEnvNull() {
        Object result = call("sys.get_env", "NONEXISTENT_VAR_XYZ_12345");
        assertNull(result);
    }

    @Test
    void testSysLog() {
        assertDoesNotThrow(() -> call("sys.log", "test message"));
    }

    @Test
    void testSysLogWithSeverity() {
        assertDoesNotThrow(() -> call("sys.log", "warning message", "WARNING"));
        assertDoesNotThrow(() -> call("sys.log", "error message", "ERROR"));
    }

    // --- registry ---

    @Test
    void testRegistryHasJsonDecode() {
        assertTrue(registry.has("json.decode"));
    }

    @Test
    void testRegistryHasJsonEncode() {
        assertTrue(registry.has("json.encode"));
    }

    @Test
    void testRegistryHasMathAbs() {
        assertTrue(registry.has("math.abs"));
    }

    @Test
    void testRegistryHasTextToUpper() {
        assertTrue(registry.has("text.to_upper"));
    }

    @Test
    void testRegistryHasSysNow() {
        assertTrue(registry.has("sys.now"));
    }

    @Test
    void testRegistryMissing() {
        assertFalse(registry.has("nonexistent.func"));
    }

    @Test
    void testRegistryGetNull() {
        assertNull(registry.get("nonexistent.func"));
    }

    // --- hash ---

    @Test
    void testHashComputeChecksumSha256() {
        String result = (String) call("hash.compute_checksum", "hello", "SHA-256");
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", result);
    }

    @Test
    void testHashComputeChecksumMd5() {
        String result = (String) call("hash.compute_checksum", "hello", "MD5");
        assertEquals("5d41402abc4b2a76b9719d911017c592", result);
    }

    @Test
    void testHashComputeHmacSha256() {
        String result = (String) call("hash.compute_hmac", "hello", "secret", "SHA-256");
        assertNotNull(result);
        assertEquals(64, result.length());
    }

    @Test
    void testHashComputeChecksumUnsupportedAlgorithm() {
        assertThrows(RuntimeException.class, () -> call("hash.compute_checksum", "hello", "UNSUPPORTED"));
    }

    // --- time ---

    @Test
    void testTimeFormatDefault() {
        String result = (String) call("time.format", 0.0);
        assertTrue(result.startsWith("1970-01-01T00:00:00"));
    }

    @Test
    void testTimeFormatWithTimezone() {
        String result = (String) call("time.format", 0.0, "America/New_York");
        assertTrue(result.contains("1969-12-31") || result.contains("1970-01-01"));
    }

    @Test
    void testTimeParse() {
        Object result = call("time.parse", "2026-04-22T12:00:00Z");
        assertTrue(result instanceof Number);
        double epoch = ((Number) result).doubleValue();
        assertTrue(epoch > 1_700_000_000);
    }

    @Test
    void testTimeRoundTrip() {
        double now = System.currentTimeMillis() / 1000.0;
        String formatted = (String) call("time.format", now);
        double parsed = ((Number) call("time.parse", formatted)).doubleValue();
        assertEquals(now, parsed, 1.0);
    }
}
