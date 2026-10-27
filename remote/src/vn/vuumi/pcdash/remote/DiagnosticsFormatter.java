package vn.vuumi.pcdash.remote;

import java.util.ArrayList;
import java.util.List;

public class DiagnosticsFormatter {

    public static String format(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            return "Chưa có thông tin chẩn đoán (pcnet chưa khởi động).";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== CHẨN ĐOÁN KẾT NỐI MẠNG PCDASH ===\n");

        String backend = extractString(rawJson, "backendState");
        if (backend == null || backend.isEmpty()) {
            backend = extractString(rawJson, "state");
            if (backend == null || backend.isEmpty()) {
                backend = "Không rõ";
            }
        }
        boolean selfOnline = extractBoolean(rawJson, "selfOnline");
        sb.append("• Trạng thái tsnet: ").append(backend)
          .append(selfOnline ? " (Self: Online)" : " (Self: Chưa kết nối)")
          .append("\n");

        String tailnetState = extractString(rawJson, "state");
        if (tailnetState != null && !tailnetState.isEmpty()) {
            if ("needs_login".equalsIgnoreCase(tailnetState)) {
                sb.append("• Đăng nhập: Cần đăng nhập Tailscale\n");
            } else if ("running".equalsIgnoreCase(tailnetState)) {
                sb.append("• Đăng nhập: Đã đăng nhập\n");
            }
        }
        List<String> tailnetIps = extractArrayStrings(rawJson, "tailnetIps");
        if (!tailnetIps.isEmpty()) {
            sb.append("• IP Tailnet thiết bị: ").append(tailnetIps.toString()).append("\n");
        }
        int localPort = extractInt(rawJson, "localPort", 0);
        if (localPort > 0) {
            sb.append("• Cổng cục bộ: ").append(localPort).append("\n");
        }

        // Peer host info
        String peerHostObj = extractObject(rawJson, "peerHost");
        if (peerHostObj != null && !peerHostObj.isEmpty()) {
            boolean peerOnline = extractBoolean(peerHostObj, "online");
            String curAddr = extractString(peerHostObj, "curAddr");
            String relay = extractString(peerHostObj, "relay");
            String lastHandshake = extractString(peerHostObj, "lastHandshake");

            sb.append("• Đường đi tới PC: ");
            if (curAddr != null && !curAddr.isEmpty()) {
                sb.append("Trực tiếp (Direct: ").append(curAddr).append(")");
            } else if (relay != null && !relay.isEmpty()) {
                sb.append("Tiếp sóng DERP (Vùng ").append(relay).append(")");
            } else if (peerOnline) {
                sb.append("Đã kết nối (đang dò đường)");
            } else {
                sb.append("Chưa kết nối tới PC");
            }
            if (lastHandshake != null && !lastHandshake.isEmpty()) {
                sb.append("  [Bắt tay: ").append(lastHandshake).append("]");
            }
            sb.append("\n");
        } else {
            sb.append("• Đường đi tới PC: Chưa tìm thấy host trong tailnet\n");
        }

        // Preferred DERP & latency
        int preferredDerp = extractInt(rawJson, "preferredDERP", -1);
        int derpLat = extractDerpLatency(rawJson, preferredDerp);
        sb.append("• DERP gần nhất: ");
        if (preferredDerp > 0) {
            sb.append("Vùng ").append(preferredDerp);
            if (derpLat >= 0) {
                sb.append(" (Độ trễ: ").append(derpLat).append(" ms)");
            }
        } else {
            sb.append("Chưa xác định");
        }
        sb.append("\n");

        // UDP
        boolean udp = extractBoolean(rawJson, "udp");
        String netcheckObj = extractObject(rawJson, "netcheck");
        String globalV4 = netcheckObj != null ? extractString(netcheckObj, "globalV4") : null;
        sb.append("• UDP STUN: ").append(udp ? "Có thông" : "Không thông (bị chặn/CGNAT)")
          .append(globalV4 != null && !globalV4.isEmpty() ? " [IP ngoài: " + globalV4 + "]" : "")
          .append("\n");

        // Last dial error
        String dialErr = extractString(rawJson, "lastDialError");
        String dialTime = extractString(rawJson, "lastDialErrorTime");
        sb.append("• Lỗi dial cuối: ");
        if (dialErr != null && !dialErr.isEmpty()) {
            sb.append(dialErr);
            if (dialTime != null && !dialTime.isEmpty()) {
                sb.append(" (").append(dialTime).append(")");
            }
        } else {
            sb.append("Không có lỗi");
        }
        sb.append("\n");

        // Interfaces
        List<String> ifaces = extractInterfaces(rawJson);
        sb.append("• Giao diện mạng Android:\n");
        if (ifaces.isEmpty()) {
            sb.append("  (Không có giao diện nào)\n");
        } else {
            for (String iface : ifaces) {
                sb.append("  - ").append(iface).append("\n");
            }
        }

        // Health warnings
        List<String> warnings = extractArrayStrings(rawJson, "healthWarnings");
        if (!warnings.isEmpty()) {
            sb.append("• Cảnh báo sức khỏe: ");
            for (int i = 0; i < warnings.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(warnings.get(i));
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private static String extractString(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    private static boolean extractBoolean(String json, String key) {
        if (json == null) return false;
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return false;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return false;
        int end = colon + 1;
        while (end < json.length() && Character.isWhitespace(json.charAt(end))) {
            end++;
        }
        return json.startsWith("true", end);
    }

    private static int extractInt(String json, String key, int defVal) {
        if (json == null) return defVal;
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return defVal;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return defVal;
        int start = colon + 1;
        while (start < json.length() && (Character.isWhitespace(json.charAt(start)))) {
            start++;
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (start < end) {
            try {
                return Integer.parseInt(json.substring(start, end));
            } catch (NumberFormatException ignored) {}
        }
        return defVal;
    }

    private static int extractDerpLatency(String json, int regionId) {
        if (json == null || regionId <= 0) return -1;
        int latIdx = json.indexOf("\"derpLatency\"");
        if (latIdx < 0) return -1;
        String key = "\"" + regionId + "\"";
        int keyIdx = json.indexOf(key, latIdx);
        if (keyIdx < 0) return -1;
        int colon = json.indexOf(':', keyIdx + key.length());
        if (colon < 0) return -1;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (start < end) {
            try {
                return Integer.parseInt(json.substring(start, end));
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private static String extractObject(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int braceStart = json.indexOf('{', idx + needle.length());
        if (braceStart < 0) return null;
        int depth = 0;
        for (int i = braceStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(braceStart, i + 1);
                }
            }
        }
        return null;
    }

    private static List<String> extractArrayStrings(String json, String key) {
        List<String> list = new ArrayList<String>();
        if (json == null) return list;
        String needle = "\"" + key + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return list;
        int bracketStart = json.indexOf('[', idx + needle.length());
        if (bracketStart < 0) return list;
        int bracketEnd = json.indexOf(']', bracketStart);
        if (bracketEnd < 0) return list;
        String inner = json.substring(bracketStart + 1, bracketEnd);
        int pos = 0;
        while (pos < inner.length()) {
            int q1 = inner.indexOf('"', pos);
            if (q1 < 0) break;
            int q2 = inner.indexOf('"', q1 + 1);
            if (q2 < 0) break;
            list.add(inner.substring(q1 + 1, q2));
            pos = q2 + 1;
        }
        return list;
    }

    private static List<String> extractInterfaces(String json) {
        List<String> list = new ArrayList<String>();
        if (json == null) return list;
        int ifaceIdx = json.indexOf("\"interfaces\"");
        if (ifaceIdx < 0) return list;
        int bracketStart = json.indexOf('[', ifaceIdx);
        if (bracketStart < 0) return list;
        int bracketEnd = -1;
        int depth = 0;
        for (int i = bracketStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    bracketEnd = i;
                    break;
                }
            }
        }
        if (bracketEnd < 0) return list;
        String arrStr = json.substring(bracketStart + 1, bracketEnd);

        int pos = 0;
        while (pos < arrStr.length()) {
            int objStart = arrStr.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = arrStr.indexOf('}', objStart);
            if (objEnd < 0) break;
            String obj = arrStr.substring(objStart, objEnd + 1);
            String name = extractString(obj, "name");
            boolean up = extractBoolean(obj, "up");
            List<String> addrs = extractArrayStrings(obj, "addrs");

            StringBuilder item = new StringBuilder();
            item.append(name != null ? name : "unknown")
                .append(up ? " [UP]" : " [DOWN]");
            if (!addrs.isEmpty()) {
                item.append(" ").append(addrs.toString());
            }
            list.add(item.toString());
            pos = objEnd + 1;
        }
        return list;
    }
}
