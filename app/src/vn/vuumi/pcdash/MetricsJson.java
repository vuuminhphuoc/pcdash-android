package vn.vuumi.pcdash;

import java.util.Locale;

public class MetricsJson {

    public static String build(PcMetricsClient pcClient, PhoneMetricsSource phoneSource) {
        return build(System.currentTimeMillis(), pcClient, phoneSource);
    }

    public static String build(long timestampMs, PcMetricsClient pcClient, PhoneMetricsSource phoneSource) {
        String pcJson = pcClient != null ? pcClient.getMetricsJson() : null;
        return build(timestampMs, pcJson, phoneSource);
    }

    public static String build(long timestampMs, String pcJsonFromAgent, PhoneMetricsSource phone) {
        if (phone != null) {
            phone.refresh();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"ts\":").append(timestampMs).append(",");
        sb.append("\"pc\":").append(formatPc(pcJsonFromAgent)).append(",");
        sb.append("\"phone\":").append(formatPhone(phone));
        sb.append("}");
        return sb.toString();
    }

    public static String formatPc(String pcJsonFromAgent) {
        if (pcJsonFromAgent == null) {
            return "{\"online\":false}";
        }
        String trimmed = pcJsonFromAgent.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return "{\"online\":false}";
        }
        if (!isValidJsonObject(trimmed)) {
            return "{\"online\":false}";
        }
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) {
            return "{\"online\":true}";
        }
        if (inner.matches("(?s)^\\s*\"online\"\\s*:\\s*(true|false|null)\\s*(,.*)?$")) {
            inner = inner.replaceFirst("(?s)^\\s*\"online\"\\s*:\\s*(true|false|null)\\s*,?\\s*", "").trim();
        }
        if (inner.isEmpty()) {
            return "{\"online\":true}";
        }
        return "{\"online\":true," + inner + "}";
    }

    public static String formatDouble(Double val) {
        if (val == null || val.isNaN() || val.isInfinite()) {
            return "null";
        }
        return String.format(Locale.ROOT, "%.1f", val);
    }

    public static String formatPhone(PhoneMetricsSource phone) {
        if (phone == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // battery
        sb.append("\"battery\":");
        if (!phone.hasBattery()) {
            sb.append("null,");
        } else {
            sb.append("{")
              .append("\"percent\":").append(phone.batteryPercent() == null ? "null" : phone.batteryPercent()).append(",")
              .append("\"charging\":").append(phone.batteryCharging() == null ? "null" : phone.batteryCharging()).append(",")
              .append("\"tempC\":").append(formatDouble(phone.batteryTempC()))
              .append("},");
        }

        // ram
        sb.append("\"ram\":");
        if (!phone.hasRam()) {
            sb.append("null,");
        } else {
            sb.append("{")
              .append("\"availMB\":").append(phone.ramAvailMB() == null ? "null" : phone.ramAvailMB()).append(",")
              .append("\"totalMB\":").append(phone.ramTotalMB() == null ? "null" : phone.ramTotalMB())
              .append("},");
        }

        // storage
        sb.append("\"storage\":");
        if (!phone.hasStorage()) {
            sb.append("null,");
        } else {
            sb.append("{")
              .append("\"freeGB\":").append(formatDouble(phone.storageFreeGB())).append(",")
              .append("\"totalGB\":").append(formatDouble(phone.storageTotalGB()))
              .append("},");
        }
        // wifi
        sb.append("\"wifi\":");
        if (!phone.hasWifi()) {
            sb.append("null,");
        } else {
            sb.append("{")
              .append("\"rssiDbm\":").append(phone.wifiRssiDbm() == null ? "null" : phone.wifiRssiDbm()).append(",")
              .append("\"linkMbps\":").append(phone.wifiLinkMbps() == null ? "null" : phone.wifiLinkMbps())
              .append("},");
        }

        // net
        sb.append("\"net\":");
        if (!phone.hasNet()) {
            sb.append("null,");
        } else {
            sb.append("{")
              .append("\"downBps\":").append(phone.netDownBps() == null ? "null" : phone.netDownBps()).append(",")
              .append("\"upBps\":").append(phone.netUpBps() == null ? "null" : phone.netUpBps())
              .append("},");
        }

        // tailscale
        sb.append("\"tailscale\":").append(phone.tailscale() == null ? "null" : phone.tailscale()).append(",");

        // thermal
        sb.append("\"thermal\":").append(phone.thermal() == null ? "null" : quote(phone.thermal())).append(",");

        // uptimeSec
        sb.append("\"uptimeSec\":").append(phone.uptimeSec() == null ? "null" : phone.uptimeSec());

        sb.append("}");
        return sb.toString();
    }

    public static String escapeJson(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    public static String quote(String s) {
        if (s == null) return "null";
        return "\"" + escapeJson(s) + "\"";
    }

    public static boolean isValidJsonObject(String json) {
        if (json == null) return false;
        String s = json.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) return false;
        try {
            int[] pos = new int[]{0};
            validateObject(s, pos, s.length());
            skipWhitespace(s, pos, s.length());
            return pos[0] >= s.length();
        } catch (Exception e) {
            return false;
        }
    }

    private static void skipWhitespace(String src, int[] pos, int len) {
        while (pos[0] < len) {
            char c = src.charAt(pos[0]);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos[0]++;
            } else {
                break;
            }
        }
    }

    private static void expect(String src, int[] pos, int len, char expected) {
        skipWhitespace(src, pos, len);
        if (pos[0] >= len || src.charAt(pos[0]) != expected) {
            throw new IllegalArgumentException("Expected '" + expected + "'");
        }
        pos[0]++;
    }

    private static void validateValue(String src, int[] pos, int len) {
        skipWhitespace(src, pos, len);
        if (pos[0] >= len) throw new IllegalArgumentException("Unexpected EOF");
        char c = src.charAt(pos[0]);
        if (c == '{') {
            validateObject(src, pos, len);
        } else if (c == '[') {
            validateArray(src, pos, len);
        } else if (c == '"') {
            validateString(src, pos, len);
        } else if (c == 't') {
            validateKeyword(src, pos, len, "true");
        } else if (c == 'f') {
            validateKeyword(src, pos, len, "false");
        } else if (c == 'n') {
            validateKeyword(src, pos, len, "null");
        } else if (c == '-' || (c >= '0' && c <= '9')) {
            validateNumber(src, pos, len);
        } else {
            throw new IllegalArgumentException("Unexpected char: " + c);
        }
    }

    private static void validateObject(String src, int[] pos, int len) {
        expect(src, pos, len, '{');
        skipWhitespace(src, pos, len);
        if (pos[0] < len && src.charAt(pos[0]) == '}') {
            pos[0]++;
            return;
        }
        while (true) {
            skipWhitespace(src, pos, len);
            if (pos[0] >= len || src.charAt(pos[0]) != '"') throw new IllegalArgumentException("Expected string key");
            validateString(src, pos, len);
            expect(src, pos, len, ':');
            validateValue(src, pos, len);
            skipWhitespace(src, pos, len);
            if (pos[0] >= len) throw new IllegalArgumentException("Unexpected EOF");
            char c = src.charAt(pos[0]);
            if (c == ',') {
                pos[0]++;
                skipWhitespace(src, pos, len);
                if (pos[0] < len && src.charAt(pos[0]) == '}') throw new IllegalArgumentException("Trailing comma");
            } else if (c == '}') {
                pos[0]++;
                break;
            } else {
                throw new IllegalArgumentException("Expected ',' or '}'");
            }
        }
    }

    private static void validateArray(String src, int[] pos, int len) {
        expect(src, pos, len, '[');
        skipWhitespace(src, pos, len);
        if (pos[0] < len && src.charAt(pos[0]) == ']') {
            pos[0]++;
            return;
        }
        while (true) {
            validateValue(src, pos, len);
            skipWhitespace(src, pos, len);
            if (pos[0] >= len) throw new IllegalArgumentException("Unexpected EOF");
            char c = src.charAt(pos[0]);
            if (c == ',') {
                pos[0]++;
                skipWhitespace(src, pos, len);
                if (pos[0] < len && src.charAt(pos[0]) == ']') throw new IllegalArgumentException("Trailing comma");
            } else if (c == ']') {
                pos[0]++;
                break;
            } else {
                throw new IllegalArgumentException("Expected ',' or ']'");
            }
        }
    }

    private static void validateString(String src, int[] pos, int len) {
        expect(src, pos, len, '"');
        while (pos[0] < len) {
            char c = src.charAt(pos[0]++);
            if (c == '"') {
                return;
            }
            if (c == '\\') {
                if (pos[0] >= len) throw new IllegalArgumentException("Unterminated escape");
                char esc = src.charAt(pos[0]++);
                if (esc == '"' || esc == '\\' || esc == '/' || esc == 'b' || esc == 'f' || esc == 'n' || esc == 'r' || esc == 't') {
                    // valid
                } else if (esc == 'u') {
                    for (int i = 0; i < 4; i++) {
                        if (pos[0] >= len) throw new IllegalArgumentException("Unterminated unicode escape");
                        char h = src.charAt(pos[0]++);
                        if (!((h >= '0' && h <= '9') || (h >= 'a' && h <= 'f') || (h >= 'A' && h <= 'F'))) {
                            throw new IllegalArgumentException("Invalid unicode escape");
                        }
                    }
                } else {
                    throw new IllegalArgumentException("Invalid escape");
                }
            } else if (c < 0x20) {
                throw new IllegalArgumentException("Unescaped control character");
            }
        }
        throw new IllegalArgumentException("Unterminated string");
    }

    private static void validateKeyword(String src, int[] pos, int len, String keyword) {
        for (int i = 0; i < keyword.length(); i++) {
            if (pos[0] >= len || src.charAt(pos[0]) != keyword.charAt(i)) {
                throw new IllegalArgumentException("Expected keyword " + keyword);
            }
            pos[0]++;
        }
    }

    private static void validateNumber(String src, int[] pos, int len) {
        skipWhitespace(src, pos, len);
        if (pos[0] < len && src.charAt(pos[0]) == '-') pos[0]++;
        if (pos[0] >= len) throw new IllegalArgumentException("Invalid number");
        char c = src.charAt(pos[0]);
        if (c == '0') {
            pos[0]++;
        } else if (c >= '1' && c <= '9') {
            while (pos[0] < len && src.charAt(pos[0]) >= '0' && src.charAt(pos[0]) <= '9') pos[0]++;
        } else {
            throw new IllegalArgumentException("Invalid number digits");
        }
        if (pos[0] < len && src.charAt(pos[0]) == '.') {
            pos[0]++;
            if (pos[0] >= len || src.charAt(pos[0]) < '0' || src.charAt(pos[0]) > '9') {
                throw new IllegalArgumentException("Digits required after decimal");
            }
            while (pos[0] < len && src.charAt(pos[0]) >= '0' && src.charAt(pos[0]) <= '9') pos[0]++;
        }
        if (pos[0] < len && (src.charAt(pos[0]) == 'e' || src.charAt(pos[0]) == 'E')) {
            pos[0]++;
            if (pos[0] < len && (src.charAt(pos[0]) == '+' || src.charAt(pos[0]) == '-')) pos[0]++;
            if (pos[0] >= len || src.charAt(pos[0]) < '0' || src.charAt(pos[0]) > '9') {
                throw new IllegalArgumentException("Digits required after exponent");
            }
            while (pos[0] < len && src.charAt(pos[0]) >= '0' && src.charAt(pos[0]) <= '9') pos[0]++;
        }
    }
}
