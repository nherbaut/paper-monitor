package top.nextnet.paper.monitor.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonCodec {

    private JsonCodec() {
    }

    public static String stringify(Object value) {
        StringBuilder builder = new StringBuilder();
        writeValue(builder, value);
        return builder.toString();
    }

    public static Object parse(String json) {
        return new Parser(json).parse();
    }

    private static void writeValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String string) {
            writeString(builder, string);
        } else if (value instanceof Number || value instanceof Boolean) {
            builder.append(value);
        } else if (value instanceof Map<?, ?> map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeString(builder, String.valueOf(entry.getKey()));
                builder.append(':');
                writeValue(builder, entry.getValue());
            }
            builder.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            builder.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeValue(builder, item);
            }
            builder.append(']');
        } else {
            writeString(builder, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder builder, String value) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        builder.append('"');
    }

    private static final class Parser {
        private final String json;
        private int index;

        private Parser(String json) {
            this.json = json;
        }

        private Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (index != json.length()) {
                throw new IllegalArgumentException("Unexpected trailing content in JSON");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= json.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char ch = json.charAt(index);
            return switch (ch) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            index++;
            Map<String, Object> object = new LinkedHashMap<>();
            skipWhitespace();
            if (json.charAt(index) == '}') {
                index++;
                return object;
            }
            while (true) {
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                object.put(key, value);
                skipWhitespace();
                if (json.charAt(index) == '}') {
                    index++;
                    return object;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            index++;
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (json.charAt(index) == ']') {
                index++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (json.charAt(index) == ']') {
                    index++;
                    return list;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (index < json.length()) {
                char ch = json.charAt(index++);
                if (ch == '"') {
                    return builder.toString();
                }
                if (ch == '\\') {
                    char escaped = json.charAt(index++);
                    switch (escaped) {
                        case '"', '\\', '/' -> builder.append(escaped);
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'u' -> {
                            String hex = json.substring(index, index + 4);
                            builder.append((char) Integer.parseInt(hex, 16));
                            index += 4;
                        }
                        default -> throw new IllegalArgumentException("Invalid JSON escape sequence");
                    }
                } else {
                    builder.append(ch);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private Object parseLiteral(String literal, Object value) {
            if (!json.startsWith(literal, index)) {
                throw new IllegalArgumentException("Invalid JSON literal");
            }
            index += literal.length();
            return value;
        }

        private Number parseNumber() {
            int start = index;
            while (index < json.length()) {
                char ch = json.charAt(index);
                if ((ch >= '0' && ch <= '9') || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E') {
                    index++;
                } else {
                    break;
                }
            }
            String number = json.substring(start, index);
            if (number.contains(".") || number.contains("e") || number.contains("E")) {
                return Double.parseDouble(number);
            }
            return Long.parseLong(number);
        }

        private void expect(char expected) {
            skipWhitespace();
            if (index >= json.length() || json.charAt(index) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' in JSON");
            }
            index++;
        }

        private void skipWhitespace() {
            while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
                index++;
            }
        }
    }
}
