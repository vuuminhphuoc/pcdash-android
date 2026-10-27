package vn.vuumi.pcdash.remote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MetricsParser {

    private static final int MAX_JSON_LEN = 128 * 1024; // 128 KB
    private static final int MAX_DEPTH = 16;
    private static final int MAX_STRING_LEN = 1024;

    private MetricsParser() {}

    public static MetricsSnapshot parse(String json) {
        if (json == null || json.length() > MAX_JSON_LEN) {
            return MetricsSnapshot.EMPTY;
        }

        try {
            int[] pos = new int[]{0};
            Object root = parseValue(json, pos, 0);
            if (!(root instanceof Map)) {
                return MetricsSnapshot.EMPTY;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) root;
            return buildSnapshot(map);
        } catch (Exception ignored) {
            return MetricsSnapshot.EMPTY;
        }
    }

    public static Map<String, Object> parseMap(String json) {
        if (json == null || json.length() > MAX_JSON_LEN) {
            return null;
        }
        try {
            int[] pos = new int[]{0};
            Object root = parseValue(json, pos, 0);
            if (!(root instanceof Map)) {
                return null;
            }
            skipWhitespace(json, pos);
            if (pos[0] < json.length()) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) root;
            return map;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static MetricsSnapshot buildSnapshot(Map<String, Object> root) {
        Long ts = optLong(root, "ts");
        MetricsSnapshot.Pc pc = buildPc(optMap(root, "pc"));
        MetricsSnapshot.Phone phone = buildPhone(optMap(root, "phone"));
        return new MetricsSnapshot(ts, pc, phone);
    }

    private static MetricsSnapshot.Pc buildPc(Map<String, Object> map) {
        if (map == null) return null;

        Boolean online = optBoolean(map, "online");
        MetricsSnapshot.Processor cpu = buildProcessor(optMap(map, "cpu"), false);
        MetricsSnapshot.Ram ram = buildPcRam(optMap(map, "ram"));
        MetricsSnapshot.Processor gpu = buildProcessor(optMap(map, "gpu"), true);
        MetricsSnapshot.Net net = buildNet(optMap(map, "net"));
        List<MetricsSnapshot.Storage> disks = buildDisks(optList(map, "disks"));
        Long uptimeSec = optLong(map, "uptimeSec");
        MetricsSnapshot.Sys sys = buildSys(optMap(map, "sys"));

        return new MetricsSnapshot.Pc(online, cpu, ram, gpu, net, disks, uptimeSec, sys);
    }

    private static MetricsSnapshot.Sys buildSys(Map<String, Object> map) {
        if (map == null) return null;
        Double tempC = optDouble(map, "tempC");
        return new MetricsSnapshot.Sys(tempC);
    }

    private static MetricsSnapshot.Processor buildProcessor(Map<String, Object> map, boolean isGpu) {
        if (map == null) return null;
        Double percent = optDouble(map, "percent");
        Double tempC = optDouble(map, "tempC");
        String name = optString(map, "name");
        Long memUsedMB = isGpu ? optLong(map, "memUsedMB") : null;
        Long memTotalMB = isGpu ? optLong(map, "memTotalMB") : null;
        return new MetricsSnapshot.Processor(percent, tempC, memUsedMB, memTotalMB, name);
    }

    private static MetricsSnapshot.Ram buildPcRam(Map<String, Object> map) {
        if (map == null) return null;
        Long usedMB = optLong(map, "usedMB");
        Long totalMB = optLong(map, "totalMB");
        Double percent = optDouble(map, "percent");
        return new MetricsSnapshot.Ram(usedMB, totalMB, percent);
    }

    private static MetricsSnapshot.Net buildNet(Map<String, Object> map) {
        if (map == null) return null;
        Long downBps = optLong(map, "downBps");
        Long upBps = optLong(map, "upBps");
        return new MetricsSnapshot.Net(downBps, upBps);
    }

    private static List<MetricsSnapshot.Storage> buildDisks(List<Object> list) {
        if (list == null) return null;
        List<MetricsSnapshot.Storage> disks = new ArrayList<MetricsSnapshot.Storage>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dMap = (Map<String, Object>) item;
                String name = optString(dMap, "name");
                Double usedGB = optDouble(dMap, "usedGB");
                Double totalGB = optDouble(dMap, "totalGB");
                disks.add(new MetricsSnapshot.Storage(name, usedGB, totalGB));
            }
        }
        return disks;
    }

    private static MetricsSnapshot.Phone buildPhone(Map<String, Object> map) {
        if (map == null) return null;

        MetricsSnapshot.Battery battery = buildBattery(optMap(map, "battery"));
        MetricsSnapshot.Ram ram = buildPhoneRam(optMap(map, "ram"));
        MetricsSnapshot.Storage storage = buildStorage(optMap(map, "storage"));
        MetricsSnapshot.Wifi wifi = buildWifi(optMap(map, "wifi"));
        MetricsSnapshot.Net net = buildNet(optMap(map, "net"));
        Boolean tailscale = optBoolean(map, "tailscale");
        String thermal = optString(map, "thermal");
        Long uptimeSec = optLong(map, "uptimeSec");

        return new MetricsSnapshot.Phone(battery, ram, storage, wifi, net, tailscale, thermal, uptimeSec);
    }

    private static MetricsSnapshot.Battery buildBattery(Map<String, Object> map) {
        if (map == null) return null;
        Double percent = optDouble(map, "percent");
        Boolean charging = optBoolean(map, "charging");
        Double tempC = optDouble(map, "tempC");
        return new MetricsSnapshot.Battery(percent, charging, tempC);
    }

    private static MetricsSnapshot.Ram buildPhoneRam(Map<String, Object> map) {
        if (map == null) return null;
        Long availMB = optLong(map, "availMB");
        Long totalMB = optLong(map, "totalMB");
        Long usedMB = (totalMB != null && availMB != null) ? (totalMB - availMB) : null;
        Double percent = (usedMB != null && totalMB != null && totalMB > 0) ? (usedMB * 100.0 / totalMB) : null;
        return new MetricsSnapshot.Ram(usedMB, totalMB, availMB, percent);
    }

    private static MetricsSnapshot.Storage buildStorage(Map<String, Object> map) {
        if (map == null) return null;
        Double freeGB = optDouble(map, "freeGB");
        Double totalGB = optDouble(map, "totalGB");
        Double usedGB = (totalGB != null && freeGB != null) ? (totalGB - freeGB) : null;
        return new MetricsSnapshot.Storage(null, usedGB, freeGB, totalGB);
    }

    private static MetricsSnapshot.Wifi buildWifi(Map<String, Object> map) {
        if (map == null) return null;
        Integer rssiDbm = optInt(map, "rssiDbm");
        Integer linkMbps = optInt(map, "linkMbps");
        return new MetricsSnapshot.Wifi(rssiDbm, linkMbps);
    }

    // =========================================================================
    // Safe extraction helpers
    // =========================================================================

    private static String optString(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object val = map.get(key);
        return (val instanceof String) ? (String) val : null;
    }

    private static Double optDouble(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        return null;
    }

    private static Long optLong(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        return null;
    }

    private static Integer optInt(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return null;
    }

    private static Boolean optBoolean(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object val = map.get(key);
        return (val instanceof Boolean) ? (Boolean) val : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> optMap(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object val = map.get(key);
        return (val instanceof Map) ? (Map<String, Object>) val : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> optList(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object val = map.get(key);
        return (val instanceof List) ? (List<Object>) val : null;
    }

    // =========================================================================
    // Minimal Handwritten JSON Parsing Methods (No inner class)
    // =========================================================================

    private static Object parseValue(String text, int[] pos, int depth) throws Exception {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("Max JSON depth exceeded");
        }
        int len = text.length();
        skipWhitespace(text, pos);
        if (pos[0] >= len) {
            throw new IllegalArgumentException("Unexpected EOF");
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
            throw new IllegalArgumentException("Unexpected character: " + c);
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
                throw new IllegalArgumentException("Expected string key in object");
            }
            String key = parseString(text, pos);
            skipWhitespace(text, pos);
            if (pos[0] >= len || text.charAt(pos[0]) != ':') {
                throw new IllegalArgumentException("Expected ':' after key");
            }
            pos[0]++; // skip ':'

            Object value = parseValue(text, pos, depth + 1);
            map.put(key, value);

            skipWhitespace(text, pos);
            if (pos[0] >= len) {
                throw new IllegalArgumentException("Unclosed object");
            }
            char sep = text.charAt(pos[0]);
            if (sep == '}') {
                pos[0]++;
                return map;
            } else if (sep == ',') {
                pos[0]++;
            } else {
                throw new IllegalArgumentException("Expected ',' or '}' in object, got " + sep);
            }
        }
        throw new IllegalArgumentException("Unclosed object");
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
                throw new IllegalArgumentException("Unclosed array");
            }
            char sep = text.charAt(pos[0]);
            if (sep == ']') {
                pos[0]++;
                return list;
            } else if (sep == ',') {
                pos[0]++;
            } else {
                throw new IllegalArgumentException("Expected ',' or ']' in array, got " + sep);
            }
        }
        throw new IllegalArgumentException("Unclosed array");
    }

    private static String parseString(String text, int[] pos) {
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
        return sb.toString();
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

    private static Boolean parseBoolean(String text, int[] pos) {
        if (text.startsWith("true", pos[0])) {
            pos[0] += 4;
            return Boolean.TRUE;
        } else if (text.startsWith("false", pos[0])) {
            pos[0] += 5;
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Invalid boolean at position " + pos[0]);
    }

    private static Object parseNull(String text, int[] pos) {
        if (text.startsWith("null", pos[0])) {
            pos[0] += 4;
            return null;
        }
        throw new IllegalArgumentException("Invalid null at position " + pos[0]);
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
