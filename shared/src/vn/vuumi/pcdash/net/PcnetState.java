package vn.vuumi.pcdash.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PcnetState {

    public final String state;
    public final String authUrl;
    public final List<String> tailnetIps;
    public final int localPort;
    public final String error;

    public PcnetState(String state, String authUrl, List<String> tailnetIps, int localPort, String error) {
        this.state = state != null ? state : "stopped";
        this.authUrl = authUrl != null ? authUrl : "";
        this.tailnetIps = tailnetIps != null
                ? Collections.unmodifiableList(new ArrayList<String>(tailnetIps))
                : Collections.<String>emptyList();
        this.localPort = localPort;
        this.error = error != null ? error : "";
    }

    public boolean isRunning() {
        return "running".equals(state);
    }

    public boolean isStopped() {
        return "stopped".equals(state);
    }

    public boolean isStarting() {
        return "starting".equals(state);
    }

    public boolean isNeedsLogin() {
        return "needs_login".equals(state);
    }

    public boolean isError() {
        return "error".equals(state);
    }

    @Override
    public String toString() {
        return "PcnetState[state=" + state
                + ", authUrl=" + authUrl
                + ", tailnetIps=" + tailnetIps
                + ", localPort=" + localPort
                + ", error=" + error + "]";
    }

    public static PcnetState parse(String json) {
        if (json == null) {
            return new PcnetState("error", "", Collections.<String>emptyList(), 0, "JSON is null");
        }
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return new PcnetState("error", "", Collections.<String>emptyList(), 0, "Malformed JSON: " + json);
        }

        String state = extractString(trimmed, "state");
        String authUrl = extractString(trimmed, "authUrl");
        String error = extractString(trimmed, "error");
        int localPort = extractInt(trimmed, "localPort", 0);
        List<String> ips = extractStringList(trimmed, "tailnetIps");

        if (state == null) {
            state = "stopped";
        }
        return new PcnetState(state, authUrl != null ? authUrl : "", ips, localPort, error != null ? error : "");
    }

    private static int findKeyColon(String json, String key) {
        String pattern = "\"" + key + "\"";
        int start = 0;
        while (start < json.length()) {
            int idx = json.indexOf(pattern, start);
            if (idx < 0) return -1;
            int p = idx + pattern.length();
            while (p < json.length() && (json.charAt(p) == ' ' || json.charAt(p) == '\t' || json.charAt(p) == '\r' || json.charAt(p) == '\n')) {
                p++;
            }
            if (p < json.length() && json.charAt(p) == ':') {
                return p;
            }
            start = idx + 1;
        }
        return -1;
    }

    private static String extractString(String json, String key) {
        int colon = findKeyColon(json, key);
        if (colon < 0) return null;
        int q1 = -1;
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                q1 = i;
                break;
            } else if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                return null;
            }
        }
        if (q1 < 0) return null;
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = q1 + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                if (c == '"') sb.append('"');
                else if (c == '\\') sb.append('\\');
                else if (c == '/') sb.append('/');
                else if (c == 'b') sb.append('\b');
                else if (c == 'f') sb.append('\f');
                else if (c == 'n') sb.append('\n');
                else if (c == 'r') sb.append('\r');
                else if (c == 't') sb.append('\t');
                else sb.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    private static List<String> extractStringList(String json, String key) {
        int colon = findKeyColon(json, key);
        if (colon < 0) return Collections.emptyList();
        int b1 = json.indexOf('[', colon);
        if (b1 < 0) return Collections.emptyList();
        int b2 = json.indexOf(']', b1);
        if (b2 < 0) return Collections.emptyList();
        String inner = json.substring(b1 + 1, b2);
        List<String> result = new ArrayList<String>();
        int pos = 0;
        while (pos < inner.length()) {
            int q1 = inner.indexOf('"', pos);
            if (q1 < 0) break;
            int q2 = inner.indexOf('"', q1 + 1);
            if (q2 < 0) break;
            result.add(inner.substring(q1 + 1, q2));
            pos = q2 + 1;
        }
        return result;
    }

    private static int extractInt(String json, String key, int def) {
        int colon = findKeyColon(json, key);
        if (colon < 0) return def;
        StringBuilder sb = new StringBuilder();
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c) || (c == '-' && sb.length() == 0)) {
                sb.append(c);
            } else if (sb.length() > 0) {
                break;
            } else if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                break;
            }
        }
        if (sb.length() > 0) {
            try {
                return Integer.parseInt(sb.toString());
            } catch (NumberFormatException ignored) {}
        }
        return def;
    }
}
