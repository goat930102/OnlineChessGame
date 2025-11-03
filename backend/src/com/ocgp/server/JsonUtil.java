package com.ocgp.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser and serializer tailored for predictable API payloads.
 * It supports objects, arrays, strings, numbers, booleans and null values.
 */
public final class JsonUtil {

    private JsonUtil() {
    }

    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> cast = (Map<String, Object>) value;
        return cast;
    }

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.isEnd()) {
            throw new IllegalArgumentException("Unexpected trailing characters in JSON");
        }
        return value;
    }

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value.toString());
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, map);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list);
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, String.valueOf(entry.getKey()));
            sb.append(':');
            writeValue(sb, entry.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list) {
        sb.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, item);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String value) {
        sb.append('"');
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        private final String text;
        private int index;

        Parser(String text) {
            this.text = text;
            this.index = 0;
        }

        Object parseValue() {
            skipWhitespace();
            if (isEnd()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            char ch = text.charAt(index);
            return switch (ch) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> {
                    if (ch == '-' || Character.isDigit(ch)) {
                        yield parseNumber();
                    }
                    throw new IllegalArgumentException("Unexpected character in JSON: " + ch);
                }
            };
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> result = new HashMap<>();
            expect('{');
            skipWhitespace();
            if (peek('}')) {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    break;
                }
                expect(',');
            }
            return result;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek(']')) {
                index++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    break;
                }
                expect(',');
            }
            return list;
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (!isEnd()) {
                char ch = text.charAt(index++);
                if (ch == '"') {
                    return sb.toString();
                }
                if (ch == '\\') {
                    if (isEnd()) {
                        throw new IllegalArgumentException("Invalid escape sequence in string");
                    }
                    char esc = text.charAt(index++);
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
                            if (index + 4 > text.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape in string");
                            }
                            String hex = text.substring(index, index + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            index += 4;
                        }
                        default -> throw new IllegalArgumentException("Invalid escape character: " + esc);
                    }
                } else {
                    sb.append(ch);
                }
            }
            throw new IllegalArgumentException("Unterminated string literal");
        }

        private Object parseNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            if (peek('0')) {
                index++;
            } else if (Character.isDigit(current())) {
                while (!isEnd() && Character.isDigit(current())) {
                    index++;
                }
            } else {
                throw new IllegalArgumentException("Invalid number format");
            }
            if (peek('.')) {
                index++;
                if (isEnd() || !Character.isDigit(current())) {
                    throw new IllegalArgumentException("Invalid number format");
                }
                while (!isEnd() && Character.isDigit(current())) {
                    index++;
                }
            }
            if (peek('e') || peek('E')) {
                index++;
                if (peek('+') || peek('-')) {
                    index++;
                }
                if (isEnd() || !Character.isDigit(current())) {
                    throw new IllegalArgumentException("Invalid exponent in number");
                }
                while (!isEnd() && Character.isDigit(current())) {
                    index++;
                }
            }
            String number = text.substring(start, index);
            if (number.contains(".") || number.contains("e") || number.contains("E")) {
                return Double.parseDouble(number);
            }
            try {
                return Integer.parseInt(number);
            } catch (NumberFormatException ex) {
                return Long.parseLong(number);
            }
        }

        private Object parseLiteral(String literal, Object value) {
            for (int i = 0; i < literal.length(); i++) {
                if (isEnd() || text.charAt(index++) != literal.charAt(i)) {
                    throw new IllegalArgumentException("Unexpected literal");
                }
            }
            return value;
        }

        private void skipWhitespace() {
            while (!isEnd()) {
                char ch = text.charAt(index);
                if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                    index++;
                } else {
                    break;
                }
            }
        }

        private void expect(char ch) {
            if (isEnd() || text.charAt(index) != ch) {
                throw new IllegalArgumentException("Expected '" + ch + "'");
            }
            index++;
        }

        private char current() {
            return text.charAt(index);
        }

        private boolean peek(char ch) {
            return !isEnd() && text.charAt(index) == ch;
        }

        private boolean isEnd() {
            return index >= text.length();
        }
    }
}
