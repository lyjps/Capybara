package com.co.claudecode.demo.mcp.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 零依赖的 JSON 解析/构建工具。
 * <p>
 * 复用 AnthropicProviderClient 中手写 JSON 的模式，提供 MCP 协议所需的
 * JSON 操作能力。支持基本类型（字符串、数字、布尔、null）、对象和数组。
 * <p>
 * <b>限制</b>：不处理深层嵌套的复杂场景，仅满足 JSON-RPC 2.0 协议需要。
 */
public final class SimpleJsonParser {

    private SimpleJsonParser() {
    }

    // ================================================================
    //  JSON 构建
    // ================================================================

    /**
     * 构建 JSON 对象字符串。值支持：String、Number、Boolean、null、
     * 以及已序列化的 JSON 字符串（用 {@link RawJson} 包装）。
     */
    public static String toJsonObject(Map<String, ?> fields) {
        if (fields == null || fields.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : fields.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append('"').append(escapeJson(entry.getKey())).append("\":");
            sb.append(valueToJson(entry.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 构建 JSON 数组字符串。每个元素是已序列化的 JSON 字符串。
     */
    public static String toJsonArray(List<String> jsonElements) {
        if (jsonElements == null || jsonElements.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < jsonElements.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(jsonElements.get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 转义 JSON 字符串中的特殊字符。
     */
    public static String escapeJson(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    // ================================================================
    //  JSON 解析
    // ================================================================

    /**
     * 从 JSON 对象中提取顶层字段值（字符串形式）。
     * 返回去除引号的字符串值，或者原始值（数字/布尔/null）。
     */
    public static String extractField(String json, String key) {
        if (json == null || key == null) return null;
        String marker = "\"" + key + "\"";
        int idx = json.indexOf(marker);
        if (idx == -1) return null;

        int colonIdx = json.indexOf(':', idx + marker.length());
        if (colonIdx == -1) return null;

        // 跳过冒号后的空白
        int valueStart = colonIdx + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= json.length()) return null;

        char firstChar = json.charAt(valueStart);

        // 字符串值
        if (firstChar == '"') {
            return extractQuotedString(json, valueStart);
        }

        // null
        if (json.startsWith("null", valueStart)) {
            return null;
        }

        // 对象或数组 — 返回整个 JSON 片段
        if (firstChar == '{' || firstChar == '[') {
            return extractBracketedValue(json, valueStart);
        }

        // 数字或布尔
        int valueEnd = valueStart;
        while (valueEnd < json.length()) {
            char ch = json.charAt(valueEnd);
            if (ch == ',' || ch == '}' || ch == ']' || Character.isWhitespace(ch)) break;
            valueEnd++;
        }
        return json.substring(valueStart, valueEnd);
    }

    /**
     * 解析扁平 JSON 对象为 Map（所有值转为字符串）。
     * 支持嵌套对象/数组作为 JSON 字符串值保留。
     */
    public static Map<String, String> parseFlat(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return result;

        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return result;

        // 去掉外层花括号
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) return result;

        int pos = 0;
        while (pos < inner.length()) {
            // 跳过空白和逗号
            while (pos < inner.length() && (inner.charAt(pos) == ',' || Character.isWhitespace(inner.charAt(pos)))) {
                pos++;
            }
            if (pos >= inner.length()) break;

            // 读取 key
            if (inner.charAt(pos) != '"') break;
            String key = extractQuotedString(inner, pos);
            if (key == null) break;
            pos += key.length() + 2; // 跳过引号和内容

            // 找到冒号
            while (pos < inner.length() && inner.charAt(pos) != ':') pos++;
            pos++; // 跳过冒号

            // 跳过空白
            while (pos < inner.length() && Character.isWhitespace(inner.charAt(pos))) pos++;
            if (pos >= inner.length()) break;

            // 读取值
            char ch = inner.charAt(pos);
            if (ch == '"') {
                String val = extractQuotedString(inner, pos);
                result.put(unescapeJson(key), val != null ? unescapeJson(val) : "");
                pos += (val != null ? val.length() + 2 : 2);
            } else if (ch == '{' || ch == '[') {
                String val = extractBracketedValue(inner, pos);
                result.put(unescapeJson(key), val != null ? val : "");
                pos += (val != null ? val.length() : 1);
            } else if (inner.startsWith("null", pos)) {
                result.put(unescapeJson(key), "");
                pos += 4;
            } else {
                // 数字或布尔
                int end = pos;
                while (end < inner.length() && inner.charAt(end) != ',' && inner.charAt(end) != '}'
                        && !Character.isWhitespace(inner.charAt(end))) {
                    end++;
                }
                result.put(unescapeJson(key), inner.substring(pos, end));
                pos = end;
            }
        }
        return result;
    }

    /**
     * 解析 JSON 数组，每个元素作为原始 JSON 字符串返回。
     */
    public static List<String> parseArrayRaw(String json) {
        List<String> result = new ArrayList<>();
        if (json == null || json.isBlank()) return result;

        String trimmed = json.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return result;

        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) return result;

        int pos = 0;
        while (pos < inner.length()) {
            // 跳过空白和逗号
            while (pos < inner.length() && (inner.charAt(pos) == ',' || Character.isWhitespace(inner.charAt(pos)))) {
                pos++;
            }
            if (pos >= inner.length()) break;

            char ch = inner.charAt(pos);
            if (ch == '{' || ch == '[') {
                String val = extractBracketedValue(inner, pos);
                if (val != null) {
                    result.add(val);
                    pos += val.length();
                } else {
                    break;
                }
            } else if (ch == '"') {
                String val = extractQuotedString(inner, pos);
                result.add("\"" + (val != null ? val : "") + "\"");
                pos += (val != null ? val.length() + 2 : 2);
            } else if (inner.startsWith("null", pos)) {
                result.add("null");
                pos += 4;
            } else {
                // 数字或布尔
                int end = pos;
                while (end < inner.length() && inner.charAt(end) != ',' && inner.charAt(end) != ']'
                        && !Character.isWhitespace(inner.charAt(end))) {
                    end++;
                }
                result.add(inner.substring(pos, end));
                pos = end;
            }
        }
        return result;
    }

    /**
     * 从 JSON 对象中提取嵌套字段值。
     * 例如 extractNestedField(json, "content_block", "type") 从
     * {"content_block":{"type":"text"}} 中提取 "text"。
     */
    public static String extractNestedField(String json, String outerKey, String innerKey) {
        String outerValue = extractField(json, outerKey);
        if (outerValue == null || !outerValue.trim().startsWith("{")) return null;
        return extractField(outerValue, innerKey);
    }

    // ================================================================
    //  内部工具方法
    // ================================================================

    private static String valueToJson(Object value) {
        if (value == null) return "null";
        if (value instanceof RawJson raw) return raw.json();
        if (value instanceof String s) return "\"" + escapeJson(s) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, ?> typedMap = (Map<String, ?>) map;
            return toJsonObject(typedMap);
        }
        if (value instanceof List<?> list) {
            List<String> elements = new ArrayList<>();
            for (Object item : list) {
                elements.add(valueToJson(item));
            }
            return toJsonArray(elements);
        }
        return "\"" + escapeJson(value.toString()) + "\"";
    }

    /**
     * 从指定位置提取带引号的字符串内容（不含引号本身）。
     * 处理转义字符。
     */
    private static String extractQuotedString(String json, int startQuote) {
        if (startQuote >= json.length() || json.charAt(startQuote) != '"') return null;
        int pos = startQuote + 1;
        StringBuilder sb = new StringBuilder();
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == '\\' && pos + 1 < json.length()) {
                sb.append(c);
                sb.append(json.charAt(pos + 1));
                pos += 2;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
                pos++;
            }
        }
        return sb.toString(); // 未闭合的字符串
    }

    /**
     * 从指定位置提取用括号包围的值（对象 {} 或数组 []）。
     * 处理嵌套和字符串中的括号。
     */
    private static String extractBracketedValue(String json, int start) {
        if (start >= json.length()) return null;
        char open = json.charAt(start);
        char close = (open == '{') ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        int pos = start;
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (inString) {
                if (c == '\\' && pos + 1 < json.length()) {
                    pos += 2;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
            } else {
                if (c == '"') {
                    inString = true;
                } else if (c == open) {
                    depth++;
                } else if (c == close) {
                    depth--;
                    if (depth == 0) {
                        return json.substring(start, pos + 1);
                    }
                }
            }
            pos++;
        }
        return json.substring(start); // 未闭合
    }

    /**
     * 反转义 JSON 字符串。
     */
    public static String unescapeJson(String value) {
        if (value == null || !value.contains("\\")) return value;
        StringBuilder sb = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                switch (next) {
                    case '"' -> { sb.append('"'); i += 2; }
                    case '\\' -> { sb.append('\\'); i += 2; }
                    case 'n' -> { sb.append('\n'); i += 2; }
                    case 'r' -> { sb.append('\r'); i += 2; }
                    case 't' -> { sb.append('\t'); i += 2; }
                    case 'b' -> { sb.append('\b'); i += 2; }
                    case 'f' -> { sb.append('\f'); i += 2; }
                    case 'u' -> {
                        if (i + 5 < value.length()) {
                            String hex = value.substring(i + 2, i + 6);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 6;
                            } catch (NumberFormatException e) {
                                sb.append(c);
                                i++;
                            }
                        } else {
                            sb.append(c);
                            i++;
                        }
                    }
                    default -> { sb.append(c); i++; }
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * 包装器，标记一个字符串为已序列化的 JSON（不再转义）。
     */
    public record RawJson(String json) {
    }
}
