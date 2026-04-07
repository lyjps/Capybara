package com.co.claudecode.demo.mcp.protocol;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimpleJsonParser 单元测试。
 */
class SimpleJsonParserTest {

    // ---- escapeJson ----

    @Test
    void escapeJson_nullReturnsEmpty() {
        assertEquals("", SimpleJsonParser.escapeJson(null));
    }

    @Test
    void escapeJson_plainTextUnchanged() {
        assertEquals("hello world", SimpleJsonParser.escapeJson("hello world"));
    }

    @Test
    void escapeJson_specialCharsEscaped() {
        assertEquals("line1\\nline2", SimpleJsonParser.escapeJson("line1\nline2"));
        assertEquals("tab\\there", SimpleJsonParser.escapeJson("tab\there"));
        assertEquals("quote\\\"here", SimpleJsonParser.escapeJson("quote\"here"));
        assertEquals("back\\\\slash", SimpleJsonParser.escapeJson("back\\slash"));
    }

    @Test
    void escapeJson_controlCharsEscaped() {
        String input = "\u0001\u0002";
        String escaped = SimpleJsonParser.escapeJson(input);
        assertTrue(escaped.contains("\\u0001"));
        assertTrue(escaped.contains("\\u0002"));
    }

    // ---- unescapeJson ----

    @Test
    void unescapeJson_nullReturnsNull() {
        assertNull(SimpleJsonParser.unescapeJson(null));
    }

    @Test
    void unescapeJson_noEscapesUnchanged() {
        assertEquals("hello", SimpleJsonParser.unescapeJson("hello"));
    }

    @Test
    void unescapeJson_commonEscapes() {
        assertEquals("line1\nline2", SimpleJsonParser.unescapeJson("line1\\nline2"));
        assertEquals("tab\there", SimpleJsonParser.unescapeJson("tab\\there"));
        assertEquals("quote\"here", SimpleJsonParser.unescapeJson("quote\\\"here"));
    }

    @Test
    void unescapeJson_unicodeEscape() {
        assertEquals("A", SimpleJsonParser.unescapeJson("\\u0041"));
    }

    // ---- toJsonObject ----

    @Test
    void toJsonObject_nullReturnsEmptyObject() {
        assertEquals("{}", SimpleJsonParser.toJsonObject(null));
    }

    @Test
    void toJsonObject_emptyReturnsEmptyObject() {
        assertEquals("{}", SimpleJsonParser.toJsonObject(Map.of()));
    }

    @Test
    void toJsonObject_stringValues() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", "test");
        map.put("value", "hello");
        String json = SimpleJsonParser.toJsonObject(map);
        assertTrue(json.contains("\"name\":\"test\""));
        assertTrue(json.contains("\"value\":\"hello\""));
    }

    @Test
    void toJsonObject_mixedTypes() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("str", "hello");
        map.put("num", 42);
        map.put("bool", true);
        map.put("nil", null);
        String json = SimpleJsonParser.toJsonObject(map);
        assertTrue(json.contains("\"str\":\"hello\""));
        assertTrue(json.contains("\"num\":42"));
        assertTrue(json.contains("\"bool\":true"));
        assertTrue(json.contains("\"nil\":null"));
    }

    @Test
    void toJsonObject_rawJsonValue() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("data", new SimpleJsonParser.RawJson("{\"nested\":true}"));
        String json = SimpleJsonParser.toJsonObject(map);
        assertTrue(json.contains("\"data\":{\"nested\":true}"));
    }

    @Test
    void toJsonObject_nestedMap() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("key", "val");
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("nested", inner);
        String json = SimpleJsonParser.toJsonObject(outer);
        assertTrue(json.contains("\"nested\":{\"key\":\"val\"}"));
    }

    // ---- toJsonArray ----

    @Test
    void toJsonArray_nullReturnsEmptyArray() {
        assertEquals("[]", SimpleJsonParser.toJsonArray(null));
    }

    @Test
    void toJsonArray_elementsJoined() {
        List<String> elements = List.of("\"a\"", "\"b\"", "42");
        String json = SimpleJsonParser.toJsonArray(elements);
        assertEquals("[\"a\",\"b\",42]", json);
    }

    // ---- extractField ----

    @Test
    void extractField_nullInputReturnsNull() {
        assertNull(SimpleJsonParser.extractField(null, "key"));
        assertNull(SimpleJsonParser.extractField("{}", null));
    }

    @Test
    void extractField_stringValue() {
        assertEquals("hello", SimpleJsonParser.extractField("{\"name\":\"hello\"}", "name"));
    }

    @Test
    void extractField_numberValue() {
        assertEquals("42", SimpleJsonParser.extractField("{\"count\":42}", "count"));
    }

    @Test
    void extractField_booleanValue() {
        assertEquals("true", SimpleJsonParser.extractField("{\"active\":true}", "active"));
    }

    @Test
    void extractField_nullValue() {
        assertNull(SimpleJsonParser.extractField("{\"data\":null}", "data"));
    }

    @Test
    void extractField_objectValue() {
        String json = "{\"info\":{\"name\":\"test\",\"id\":1}}";
        String result = SimpleJsonParser.extractField(json, "info");
        assertNotNull(result);
        assertTrue(result.startsWith("{"));
        assertTrue(result.contains("\"name\":\"test\""));
    }

    @Test
    void extractField_arrayValue() {
        String json = "{\"items\":[1,2,3]}";
        String result = SimpleJsonParser.extractField(json, "items");
        assertNotNull(result);
        assertTrue(result.startsWith("["));
    }

    @Test
    void extractField_missingKeyReturnsNull() {
        assertNull(SimpleJsonParser.extractField("{\"a\":1}", "b"));
    }

    // ---- parseFlat ----

    @Test
    void parseFlat_emptyObject() {
        assertTrue(SimpleJsonParser.parseFlat("{}").isEmpty());
    }

    @Test
    void parseFlat_stringValues() {
        Map<String, String> result = SimpleJsonParser.parseFlat("{\"a\":\"x\",\"b\":\"y\"}");
        assertEquals("x", result.get("a"));
        assertEquals("y", result.get("b"));
    }

    @Test
    void parseFlat_nestedObjectAsString() {
        Map<String, String> result = SimpleJsonParser.parseFlat("{\"obj\":{\"k\":\"v\"}}");
        String nested = result.get("obj");
        assertNotNull(nested);
        assertTrue(nested.contains("\"k\":\"v\""));
    }

    // ---- parseArrayRaw ----

    @Test
    void parseArrayRaw_emptyArray() {
        assertTrue(SimpleJsonParser.parseArrayRaw("[]").isEmpty());
    }

    @Test
    void parseArrayRaw_objects() {
        String json = "[{\"a\":1},{\"b\":2}]";
        List<String> result = SimpleJsonParser.parseArrayRaw(json);
        assertEquals(2, result.size());
        assertTrue(result.get(0).contains("\"a\":1"));
        assertTrue(result.get(1).contains("\"b\":2"));
    }

    @Test
    void parseArrayRaw_strings() {
        String json = "[\"hello\",\"world\"]";
        List<String> result = SimpleJsonParser.parseArrayRaw(json);
        assertEquals(2, result.size());
    }

    @Test
    void parseArrayRaw_mixedTypes() {
        String json = "[\"str\",42,true,null]";
        List<String> result = SimpleJsonParser.parseArrayRaw(json);
        assertEquals(4, result.size());
    }

    // ---- extractNestedField ----

    @Test
    void extractNestedField_works() {
        String json = "{\"outer\":{\"inner\":\"value\"}}";
        assertEquals("value", SimpleJsonParser.extractNestedField(json, "outer", "inner"));
    }

    @Test
    void extractNestedField_missingOuter() {
        assertNull(SimpleJsonParser.extractNestedField("{\"other\":1}", "outer", "inner"));
    }

    @Test
    void extractNestedField_missingInner() {
        assertNull(SimpleJsonParser.extractNestedField("{\"outer\":{\"x\":1}}", "outer", "inner"));
    }
}
