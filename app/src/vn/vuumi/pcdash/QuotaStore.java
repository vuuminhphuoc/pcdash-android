package vn.vuumi.pcdash;

public class QuotaStore {
    private static final String EMPTY_STALE = "{\"providers\":[],\"stale\":true}";

    private String lastGoodJson = null;

    public synchronized String resolve(String freshJsonOrNull) {
        if (freshJsonOrNull != null && MetricsJson.isValidJsonObject(freshJsonOrNull)) {
            String trimmed = freshJsonOrNull.trim();
            if (hasTopLevelErrorOrLoading(trimmed)) {
                if (lastGoodJson != null) {
                    return setStale(lastGoodJson, true);
                }
                return setStale(trimmed, true);
            }
            lastGoodJson = trimmed;
            return setStale(trimmed, false);
        }
        if (lastGoodJson != null) {
            return setStale(lastGoodJson, true);
        }
        return EMPTY_STALE;
    }

    public synchronized void clear() {
        lastGoodJson = null;
    }

    static boolean hasTopLevelErrorOrLoading(String json) {
        if (json == null) return false;
        String s = json.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) return false;
        int len = s.length();
        int pos = 1;
        while (pos < len) {
            while (pos < len && Character.isWhitespace(s.charAt(pos))) pos++;
            if (pos >= len || s.charAt(pos) == '}') break;
            if (s.charAt(pos) != '"') break;

            pos++;
            StringBuilder keySb = new StringBuilder();
            while (pos < len) {
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\' && pos < len) {
                    keySb.append(s.charAt(pos++));
                } else {
                    keySb.append(c);
                }
            }
            String key = keySb.toString();

            while (pos < len && Character.isWhitespace(s.charAt(pos))) pos++;
            if (pos < len && s.charAt(pos) == ':') pos++;
            while (pos < len && Character.isWhitespace(s.charAt(pos))) pos++;
            if (pos >= len) break;

            if ("error".equals(key)) {
                if (!s.startsWith("null", pos)) {
                    return true;
                }
                pos += 4;
            } else if ("loading".equals(key)) {
                if (s.startsWith("true", pos)) {
                    return true;
                }
            }

            pos = skipValue(s, pos, len);
            while (pos < len && Character.isWhitespace(s.charAt(pos))) pos++;
            if (pos < len && s.charAt(pos) == ',') {
                pos++;
            } else if (pos < len && s.charAt(pos) == '}') {
                break;
            }
        }
        return false;
    }

    private static int skipValue(String s, int pos, int len) {
        if (pos >= len) return pos;
        char c = s.charAt(pos);
        if (c == '"') {
            pos++;
            while (pos < len) {
                char ch = s.charAt(pos++);
                if (ch == '"') break;
                if (ch == '\\' && pos < len) pos++;
            }
            return pos;
        }
        if (c == '{' || c == '[') {
            int depth = 0;
            while (pos < len) {
                char ch = s.charAt(pos++);
                if (ch == '"') {
                    while (pos < len) {
                        char sc = s.charAt(pos++);
                        if (sc == '"') break;
                        if (sc == '\\' && pos < len) pos++;
                    }
                } else if (ch == '{' || ch == '[') {
                    depth++;
                } else if (ch == '}' || ch == ']') {
                    depth--;
                    if (depth == 0) break;
                }
            }
            return pos;
        }
        while (pos < len) {
            char ch = s.charAt(pos);
            if (ch == ',' || ch == '}' || Character.isWhitespace(ch)) {
                break;
            }
            pos++;
        }
        return pos;
    }

    static String setStale(String json, boolean stale) {
        if (json == null) {
            return null;
        }
        String s = json.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) {
            return s;
        }
        if (s.startsWith("{\"stale\":true,")) {
            return "{\"stale\":" + stale + "," + s.substring("{\"stale\":true,".length());
        }
        if (s.startsWith("{\"stale\":false,")) {
            return "{\"stale\":" + stale + "," + s.substring("{\"stale\":false,".length());
        }
        if ("{\"stale\":true}".equals(s) || "{\"stale\":false}".equals(s)) {
            return "{\"stale\":" + stale + "}";
        }
        if (s.contains("\"stale\"")) {
            s = s.replaceAll("\"stale\"\\s*:\\s*(true|false)\\s*,\\s*", "");
            s = s.replaceAll(",\\s*\"stale\"\\s*:\\s*(true|false)", "");
            s = s.replaceAll("\"stale\"\\s*:\\s*(true|false)", "");
            s = s.trim();
        }
        String inner = s.substring(1, s.length() - 1).trim();
        if (inner.isEmpty()) {
            return "{\"stale\":" + stale + "}";
        }
        return "{\"stale\":" + stale + "," + inner + "}";
    }
}
