/* SPDX-License-Identifier: Apache-2.0 */
package org.teavm.samples.m3viewport;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bound, strict JSON event decoder for a JVM-only CDP adapter.
 * This is not compiled by TeaVM. Browser runtimes use native JSON APIs.
 * Duplicate object keys, invalid escape sequences, excess nesting/fields,
 * oversized payloads, and trailing garbage fail closed.
 */
public final class CdpFrameJson {
    public static final int MAX_TEXT = 12_000_000;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_MEMBERS = 2048;

    public record Event(int acknowledgementId, RemoteFrameInbox.Format format, byte[] image) {
        public Event {
            image = image.clone();
        }
        @Override public byte[] image() { return image.clone(); }
    }

    private CdpFrameJson() { }

    /** Reusable strict, bounded JVM-only protocol JSON object parser. CDP and
     * WebDriver BiDi each own their protocol-specific admission independently. */
    public static Map<?, ?> object(String json) {
        if (json == null || json.length() > MAX_TEXT) {
            throw new IllegalArgumentException("oversized browser protocol message");
        }
        return map(new Parser(json).parse(), "root");
    }

    /** Non-frame messages return null (e.g. command responses or network events). */
    public static Event frame(String json) {
        Map<?, ?> root = object(json);
        if (!"Page.screencastFrame".equals(root.get("method"))) return null;
        Map<?, ?> params = map(root.get("params"), "params");
        Object num = params.get("sessionId");
        if (!(num instanceof Long id) || id < 0 || id > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid CDP screencast sessionId");
        }
        if (!(params.get("data") instanceof String base64) || base64.isEmpty()
                || base64.length() > (long) RemoteFrameInbox.MAX_COMPRESSED_BYTES * 4 / 3 + 8) {
            throw new IllegalArgumentException("invalid CDP image data size");
        }
        byte[] image;
        try { image = Base64.getDecoder().decode(base64); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("invalid CDP frame base64", e); }
        RemoteFrameInbox.Format format;
        if (image.length >= 8 && (image[0] & 255) == 0x89 && image[1] == 0x50
                && image[2] == 0x4e && image[3] == 0x47) {
            format = RemoteFrameInbox.Format.PNG;
        } else if (image.length >= 4 && (image[0] & 255) == 0xff && (image[1] & 255) == 0xd8) {
            format = RemoteFrameInbox.Format.JPEG;
        } else throw new IllegalArgumentException("unsupported CDP image format");
        return new Event(id.intValue(), format, image);
    }

    private static Map<?, ?> map(Object value, String field) {
        if (!(value instanceof Map<?, ?> m)) throw new IllegalArgumentException("expected object: " + field);
        return m;
    }

    private static final class Parser {
        private final String source;
        private int index;
        private int members;

        Parser(String source) { this.source = source; }

        Object parse() {
            Object v = value(0);
            space();
            if (index != source.length()) throw invalid();
            return v;
        }

        private Object value(int depth) {
            if (depth > MAX_DEPTH) throw invalid();
            space();
            if (index >= source.length()) throw invalid();
            return switch (source.charAt(index)) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object(int depth) {
            expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            space();
            if (eat('}')) return out;
            do {
                space();
                if (index >= source.length() || source.charAt(index) != '"') throw invalid();
                String name = string();
                space(); expect(':');
                Object v = value(depth);
                if (++members > MAX_MEMBERS || out.containsKey(name)) throw invalid();
                out.put(name, v);
                space();
                if (eat('}')) return out;
                expect(',');
            } while (true);
        }

        private List<Object> array(int depth) {
            expect('[');
            List<Object> out = new ArrayList<>();
            space();
            if (eat(']')) return out;
            do {
                if (++members > MAX_MEMBERS) throw invalid();
                out.add(value(depth));
                space();
                if (eat(']')) return out;
                expect(',');
            } while (true);
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (index < source.length()) {
                char c = source.charAt(index++);
                if (c == '"') return out.toString();
                if (c < ' ') throw invalid();
                if (c != '\\') { out.append(c); continue; }
                if (index >= source.length()) throw invalid();
                c = source.charAt(index++);
                switch (c) {
                    case '"', '\\', '/' -> out.append(c);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (index + 4 > source.length()) throw invalid();
                        int v = 0;
                        for (int k = 0; k < 4; k++) {
                            int h = Character.digit(source.charAt(index++), 16);
                            if (h < 0) throw invalid();
                            v = v * 16 + h;
                        }
                        out.append((char) v);
                    }
                    default -> throw invalid();
                }
            }
            throw invalid();
        }

        private Number number() {
            int begin = index;
            eat('-');
            if (index >= source.length()) throw invalid();
            if (eat('0')) {
                if (index < source.length() && Character.isDigit(source.charAt(index))) throw invalid();
            } else {
                if (!digit19()) throw invalid();
                while (index < source.length() && digit(source.charAt(index))) index++;
            }
            boolean fraction = false;
            if (eat('.')) {
                fraction = true;
                if (!digitHere()) throw invalid();
                while (digitHere()) index++;
            }
            if (eat('e') || eat('E')) {
                fraction = true;
                if (!eat('+')) eat('-');
                if (!digitHere()) throw invalid();
                while (digitHere()) index++;
            }
            try {
                String num = source.substring(begin, index);
                if (!fraction) return Long.parseLong(num);
                double value = Double.parseDouble(num);
                if (!Double.isFinite(value)) throw invalid();
                return value;
            } catch (NumberFormatException e) { throw invalid(); }
        }

        private boolean digit19() {
            if (index < source.length() && source.charAt(index) >= '1' && source.charAt(index) <= '9') {
                index++; return true;
            }
            return false;
        }
        private boolean digitHere() { return index < source.length() && digit(source.charAt(index)); }
        private static boolean digit(char c) { return c >= '0' && c <= '9'; }
        private Object literal(String word, Object value) {
            if (!source.startsWith(word, index)) throw invalid();
            index += word.length();
            return value;
        }
        private void expect(char ch) { if (!eat(ch)) throw invalid(); }
        private boolean eat(char ch) {
            if (index < source.length() && source.charAt(index) == ch) { index++; return true; }
            return false;
        }
        private void space() {
            while (index < source.length()) {
                char c = source.charAt(index);
                if (c != ' ' && c != '\r' && c != '\n' && c != '\t') break;
                index++;
            }
        }
        private IllegalArgumentException invalid() {
            return new IllegalArgumentException("invalid CDP JSON at " + index);
        }
    }
}
