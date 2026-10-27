package ru.andrey1859.npcskin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser: objects, arrays, strings, numbers, booleans, null.
 * Returns Map, List, String, Double, Long, Boolean or null.
 */
final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    static Object parse(String text) {
        Json parser = new Json(text);
        Object value = parser.readValue();
        parser.skipWs();
        if (parser.pos != text.length()) {
            throw new IllegalArgumentException("Unexpected trailing characters");
        }
        return value;
    }

    private Object readValue() {
        skipWs();
        char c = peek();
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't', 'f' -> readBool();
            case 'n' -> readNull();
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            String key = readString();
            skipWs();
            expect(':');
            map.put(key, readValue());
            skipWs();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Expected ',' or '}' at position " + (pos - 1));
            }
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWs();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(readValue());
            skipWs();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Expected ',' or ']' at position " + (pos - 1));
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char esc = next();
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) {
                            throw new IllegalArgumentException("Bad unicode escape");
                        }
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new IllegalArgumentException("Bad escape character: " + esc);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Object readNumber() {
        int start = pos;
        if (pos < src.length() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) {
            pos++;
        }
        while (pos < src.length() && "0123456789.eE+-".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        String text = src.substring(start, pos);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Expected a number at position " + start);
        }
        if (text.contains(".") || text.contains("e") || text.contains("E")) {
            return Double.parseDouble(text);
        }
        return Long.parseLong(text);
    }

    private Object readBool() {
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Expected a boolean at position " + pos);
    }

    private Object readNull() {
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw new IllegalArgumentException("Expected null at position " + pos);
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON");
        }
        return src.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char expected) {
        char got = next();
        if (got != expected) {
            throw new IllegalArgumentException("Expected '" + expected + "' at position " + (pos - 1));
        }
    }
}
