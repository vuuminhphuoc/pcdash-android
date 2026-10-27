package vn.vuumi.pcdash.remote;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QuotaParser {

    private static final int MAX_JSON_LEN = 256 * 1024; // 256 KB
    private static final int MAX_DEPTH = 16;
    private static final int MAX_STRING_LEN = 1024;

    private static final int MAX_PROVIDERS = 50;
    private static final int MAX_GROUPS = 50;
    private static final int MAX_WINDOWS = 50;
    private static final int MAX_ACCOUNTS = 100;

    private QuotaParser() {}

    public static QuotaSnapshot parse(String json) throws IOException {
        if (json == null) {
            throw new IOException("JSON payload is null");
        }
        if (json.length() > MAX_JSON_LEN) {
            throw new IOException("JSON payload exceeds maximum length of 256 KB (" + json.length() + " bytes)");
        }
        String trimmed = json.trim();
        if (trimmed.isEmpty()) {
            throw new IOException("JSON payload is empty");
        }

        Object rootObj;
        try {
            int[] pos = new int[]{0};
            rootObj = parseValue(json, pos, 0);
            skipWhitespace(json, pos);
            if (pos[0] < json.length()) {
                throw new IOException("Trailing characters after JSON payload at position " + pos[0]);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Malformed JSON: " + e.getMessage(), e);
        }

        if (!(rootObj instanceof Map)) {
            throw new IOException("Root element must be a JSON object");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) rootObj;
        return buildSnapshot(root);
    }

    private static QuotaSnapshot buildSnapshot(Map<String, Object> root) throws IOException {
        long fetchedAt = optLong(root, "fetchedAt", 0L);
        boolean stale = optBoolean(root, "stale", false);
        boolean loading = optBoolean(root, "loading", false);
        String error = optString(root, "error", null);

        Object providersObj = root.get("providers");
        List<QuotaSnapshot.Provider> providers;
        if (providersObj == null) {
            providers = Collections.emptyList();
        } else if (providersObj instanceof List) {
            List<?> rawList = (List<?>) providersObj;
            if (rawList.size() > MAX_PROVIDERS) {
                throw new IOException("Number of providers (" + rawList.size() + ") exceeds maximum limit of " + MAX_PROVIDERS);
            }
            providers = new ArrayList<QuotaSnapshot.Provider>(rawList.size());
            for (Object item : rawList) {
                if (!(item instanceof Map)) {
                    throw new IOException("Provider item must be an object");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> pMap = (Map<String, Object>) item;
                providers.add(buildProvider(pMap));
            }
        } else {
            throw new IOException("Field 'providers' must be an array");
        }

        return new QuotaSnapshot(fetchedAt, stale, loading, error, providers);
    }

    private static QuotaSnapshot.Provider buildProvider(Map<String, Object> map) throws IOException {
        String id = reqString(map, "id");
        String name = optString(map, "name", id);

        Object groupsObj = map.get("groups");
        List<QuotaSnapshot.Group> groups;
        if (groupsObj == null) {
            groups = Collections.emptyList();
        } else if (groupsObj instanceof List) {
            List<?> rawList = (List<?>) groupsObj;
            if (rawList.size() > MAX_GROUPS) {
                throw new IOException("Number of groups (" + rawList.size() + ") exceeds limit of " + MAX_GROUPS);
            }
            groups = new ArrayList<QuotaSnapshot.Group>(rawList.size());
            for (Object item : rawList) {
                if (!(item instanceof Map)) {
                    throw new IOException("Group item must be an object");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> gMap = (Map<String, Object>) item;
                groups.add(buildGroup(gMap));
            }
        } else {
            throw new IOException("Field 'groups' in provider must be an array");
        }

        return new QuotaSnapshot.Provider(id, name, groups);
    }

    private static QuotaSnapshot.Group buildGroup(Map<String, Object> map) throws IOException {
        String name = optString(map, "name", "");

        Object windowsObj = map.get("windows");
        List<QuotaSnapshot.Window> windows;
        if (windowsObj == null) {
            windows = Collections.emptyList();
        } else if (windowsObj instanceof List) {
            List<?> rawList = (List<?>) windowsObj;
            if (rawList.size() > MAX_WINDOWS) {
                throw new IOException("Number of windows (" + rawList.size() + ") exceeds limit of " + MAX_WINDOWS);
            }
            windows = new ArrayList<QuotaSnapshot.Window>(rawList.size());
            for (Object item : rawList) {
                if (!(item instanceof Map)) {
                    throw new IOException("Window item must be an object");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> wMap = (Map<String, Object>) item;
                windows.add(buildWindow(wMap));
            }
        } else {
            throw new IOException("Field 'windows' in group must be an array");
        }

        return new QuotaSnapshot.Group(name, windows);
    }

    private static QuotaSnapshot.Window buildWindow(Map<String, Object> map) throws IOException {
        String id = optString(map, "id", "");
        String label = optString(map, "label", id);

        int pooledRemainingPct = reqPercent(map, "pooledRemainingPct");
        int accountsWithQuota = optInt(map, "accountsWithQuota", 0);
        int accountsTotal = optInt(map, "accountsTotal", 0);
        Long resetsAt = optLongObj(map, "resetsAt");

        Object accountsObj = map.get("accounts");
        List<QuotaSnapshot.Account> accounts;
        if (accountsObj == null) {
            accounts = Collections.emptyList();
        } else if (accountsObj instanceof List) {
            List<?> rawList = (List<?>) accountsObj;
            if (rawList.size() > MAX_ACCOUNTS) {
                throw new IOException("Number of accounts (" + rawList.size() + ") exceeds limit of " + MAX_ACCOUNTS);
            }
            accounts = new ArrayList<QuotaSnapshot.Account>(rawList.size());
            for (Object item : rawList) {
                if (!(item instanceof Map)) {
                    throw new IOException("Account item must be an object");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> aMap = (Map<String, Object>) item;
                accounts.add(buildAccount(aMap));
            }
        } else {
            throw new IOException("Field 'accounts' in window must be an array");
        }

        return new QuotaSnapshot.Window(id, label, pooledRemainingPct, accountsWithQuota, accountsTotal, resetsAt, accounts);
    }

    private static QuotaSnapshot.Account buildAccount(Map<String, Object> map) throws IOException {
        String label = optString(map, "label", "");
        int remainingPct = reqPercent(map, "remainingPct");
        Long resetsAt = optLongObj(map, "resetsAt");

        return new QuotaSnapshot.Account(label, remainingPct, resetsAt);
    }

    private static int reqPercent(Map<String, Object> map, String key) throws IOException {
        Object val = map.get(key);
        if (!(val instanceof Number)) {
            throw new IOException("Field '" + key + "' is missing or not a number");
        }
        int pct = ((Number) val).intValue();
        if (pct < 0 || pct > 100) {
            throw new IOException("Field '" + key + "' must be between 0 and 100, got: " + pct);
        }
        return pct;
    }

    private static String reqString(Map<String, Object> map, String key) throws IOException {
        Object val = map.get(key);
        if (val == null) {
            return "";
        }
        if (!(val instanceof String)) {
            throw new IOException("Field '" + key + "' must be a string");
        }
        return (String) val;
    }

    private static String optString(Map<String, Object> map, String key, String def) throws IOException {
        Object val = map.get(key);
        if (val == null) {
            return def;
        }
        if (!(val instanceof String)) {
            throw new IOException("Field '" + key + "' must be a string");
        }
        return (String) val;
    }

    private static boolean optBoolean(Map<String, Object> map, String key, boolean def) throws IOException {
        Object val = map.get(key);
        if (val == null) {
            return def;
        }
        if (!(val instanceof Boolean)) {
            throw new IOException("Field '" + key + "' must be a boolean");
        }
        return (Boolean) val;
    }

    private static long optLong(Map<String, Object> map, String key, long def) throws IOException {
        Object val = map.get(key);
        if (val == null) {
            return def;
        }
        if (!(val instanceof Number)) {
            throw new IOException("Field '" + key + "' must be a number");
        }
        return ((Number) val).longValue();
    }

    private static Long optLongObj(Map<String, Object> map, String key) throws IOException {
        Object val = map.get(key);
        if (val == null) {
            return null;
        }
        if (!(val instanceof Number)) {
            throw new IOException("Field '" + key + "' must be a number or null");
        }
        return ((Number) val).longValue();
    }

    private static int optInt(Map<String, Object> map, String key, int def) throws IOException {
        Object val = map.get(key);
        if (val == null) {
            return def;
        }
        if (!(val instanceof Number)) {
            throw new IOException("Field '" + key + "' must be a number");
        }
        return ((Number) val).intValue();
    }

    // =========================================================================
    // Minimal Handwritten JSON Parsing Methods (Safe recursive descent)
    // =========================================================================

    private static Object parseValue(String text, int[] pos, int depth) throws Exception {
        if (depth > MAX_DEPTH) {
            throw new IOException("Max JSON depth of " + MAX_DEPTH + " exceeded");
        }
        int len = text.length();
        skipWhitespace(text, pos);
        if (pos[0] >= len) {
            throw new IOException("Unexpected end of JSON input");
        }

        char c = text.charAt(pos[0]);
        if (c == '{') {
            return parseObject(text, pos, depth);
        } else if (c == '[') {
            return parseArray(text, pos, depth);
        } else if (c == '"') {
            return parseString(text, pos);
        } else if (c == '-' || (c >= '0' && c <= '9')) {
            return parseNumber(text, pos);
        } else if (c == 't' || c == 'f') {
            return parseBoolean(text, pos);
        } else if (c == 'n') {
            return parseNull(text, pos);
        } else {
            throw new IOException("Unexpected character '" + c + "' at position " + pos[0]);
        }
    }

    private static Map<String, Object> parseObject(String text, int[] pos, int depth) throws Exception {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        int len = text.length();
        pos[0]++; // skip '{'
        skipWhitespace(text, pos);
        if (pos[0] < len && text.charAt(pos[0]) == '}') {
            pos[0]++;
            return map;
        }

        while (pos[0] < len) {
            skipWhitespace(text, pos);
            if (pos[0] >= len || text.charAt(pos[0]) != '"') {
                throw new IOException("Expected string key in object at position " + pos[0]);
            }
            String key = parseString(text, pos);
            skipWhitespace(text, pos);
            if (pos[0] >= len || text.charAt(pos[0]) != ':') {
                throw new IOException("Expected ':' after key at position " + pos[0]);
            }
            pos[0]++; // skip ':'

            Object value = parseValue(text, pos, depth + 1);
            map.put(key, value);

            skipWhitespace(text, pos);
            if (pos[0] >= len) {
                throw new IOException("Unclosed object in JSON");
            }
            char sep = text.charAt(pos[0]);
            if (sep == '}') {
                pos[0]++;
                return map;
            } else if (sep == ',') {
                pos[0]++;
            } else {
                throw new IOException("Expected ',' or '}' in object, got '" + sep + "' at position " + pos[0]);
            }
        }
        throw new IOException("Unclosed object in JSON");
    }

    private static List<Object> parseArray(String text, int[] pos, int depth) throws Exception {
        List<Object> list = new ArrayList<Object>();
        int len = text.length();
        pos[0]++; // skip '['
        skipWhitespace(text, pos);
        if (pos[0] < len && text.charAt(pos[0]) == ']') {
            pos[0]++;
            return list;
        }

        while (pos[0] < len) {
            Object value = parseValue(text, pos, depth + 1);
            list.add(value);

            skipWhitespace(text, pos);
            if (pos[0] >= len) {
                throw new IOException("Unclosed array in JSON");
            }
            char sep = text.charAt(pos[0]);
            if (sep == ']') {
                pos[0]++;
                return list;
            } else if (sep == ',') {
                pos[0]++;
            } else {
                throw new IOException("Expected ',' or ']' in array, got '" + sep + "' at position " + pos[0]);
            }
        }
        throw new IOException("Unclosed array in JSON");
    }

    private static String parseString(String text, int[] pos) throws IOException {
        int len = text.length();
        pos[0]++; // skip initial '"'
        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        while (pos[0] < len) {
            char c = text.charAt(pos[0]++);
            if (c == '"') {
                return sb.toString();
            } else if (c == '\\') {
                if (pos[0] >= len) break;
                char esc = text.charAt(pos[0]++);
                char decoded;
                switch (esc) {
                    case '"': decoded = '"'; break;
                    case '\\': decoded = '\\'; break;
                    case '/': decoded = '/'; break;
                    case 'b': decoded = '\b'; break;
                    case 'f': decoded = '\f'; break;
                    case 'n': decoded = '\n'; break;
                    case 'r': decoded = '\r'; break;
                    case 't': decoded = '\t'; break;
                    case 'u':
                        if (pos[0] + 4 <= len) {
                            String hex = text.substring(pos[0], pos[0] + 4);
                            pos[0] += 4;
                            try {
                                decoded = (char) Integer.parseInt(hex, 16);
                            } catch (NumberFormatException nfe) {
                                decoded = '?';
                            }
                        } else {
                            decoded = '?';
                        }
                        break;
                    default:
                        decoded = esc;
                        break;
                }
                if (!truncated) {
                    if (sb.length() < MAX_STRING_LEN) {
                        sb.append(decoded);
                    } else {
                        truncated = true;
                    }
                }
            } else {
                if (!truncated) {
                    if (sb.length() < MAX_STRING_LEN) {
                        sb.append(c);
                    } else {
                        truncated = true;
                    }
                }
            }
        }
        throw new IOException("Unterminated string in JSON");
    }

    private static Number parseNumber(String text, int[] pos) {
        int len = text.length();
        int start = pos[0];
        boolean hasDotOrExp = false;
        if (pos[0] < len && text.charAt(pos[0]) == '-') {
            pos[0]++;
        }
        while (pos[0] < len) {
            char c = text.charAt(pos[0]);
            if (c >= '0' && c <= '9') {
                pos[0]++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                hasDotOrExp = true;
                pos[0]++;
            } else {
                break;
            }
        }
        String numStr = text.substring(start, pos[0]);
        if (hasDotOrExp) {
            try {
                return Double.parseDouble(numStr);
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            try {
                return Long.parseLong(numStr);
            } catch (NumberFormatException e) {
                try {
                    return Double.parseDouble(numStr);
                } catch (NumberFormatException e2) {
                    return null;
                }
            }
        }
    }

    private static Boolean parseBoolean(String text, int[] pos) throws IOException {
        if (text.startsWith("true", pos[0])) {
            pos[0] += 4;
            return Boolean.TRUE;
        } else if (text.startsWith("false", pos[0])) {
            pos[0] += 5;
            return Boolean.FALSE;
        }
        throw new IOException("Invalid boolean literal at position " + pos[0]);
    }

    private static Object parseNull(String text, int[] pos) throws IOException {
        if (text.startsWith("null", pos[0])) {
            pos[0] += 4;
            return null;
        }
        throw new IOException("Invalid null literal at position " + pos[0]);
    }

    private static void skipWhitespace(String text, int[] pos) {
        int len = text.length();
        while (pos[0] < len) {
            char c = text.charAt(pos[0]);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos[0]++;
            } else {
                break;
            }
        }
    }
}
