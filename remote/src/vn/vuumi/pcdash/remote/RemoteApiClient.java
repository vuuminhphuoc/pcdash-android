package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.i18n.L10n;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class RemoteApiClient {

    public static class DeviceRevokedException extends vn.vuumi.pcdash.net.PcnetBridge.DeviceRevokedException {
        public DeviceRevokedException(String message) {
            super(message);
        }
    }

    public static class UnsupportedRouteException extends IOException {
        public UnsupportedRouteException(String message) {
            super(message);
        }

        public UnsupportedRouteException(String message, Throwable cause) {
            super(message, cause);
        }

        /** What the user sees when the host is too old for a route. */
        public static String hostNeedsUpdateMessage() {
            return L10n.t("The home phone does not support this yet. Update PCDash host",
                    "Máy ở nhà chưa hỗ trợ, hãy cập nhật PCDash host");
        }
    }

    public static class RemoteStatus {
        public final boolean online;
        public final Integer port;
        public final long rttMs;
        public final String rawJson;

        public RemoteStatus(boolean online, Integer port, long rttMs, String rawJson) {
            this.online = online;
            this.port = port;
            this.rttMs = rttMs;
            this.rawJson = rawJson;
        }

        @Override
        public String toString() {
            return "RemoteStatus[online=" + online + ", port=" + port + ", rtt=" + rttMs + "ms]";
        }
    }

    public static class RemoteCommandResult {
        public final boolean ok;
        public final String action;
        public final String detail;
        public final String rawJson;

        public RemoteCommandResult(boolean ok, String action, String detail, String rawJson) {
            this.ok = ok;
            this.action = action;
            this.detail = detail;
            this.rawJson = rawJson;
        }

        @Override
        public String toString() {
            return "RemoteCommandResult[ok=" + ok + ", action=" + action + ", detail=" + detail + "]";
        }
    }

    // The PC that status, metrics, history, quota and actions are about, when the host has several;
    // null asks about the host's first PC (and is all an older host understands).
    private volatile String pcId;

    public void setPcId(String id) {
        this.pcId = id != null && id.matches("^[A-Za-z0-9_-]{1,24}$") ? id : null;
    }

    public String getPcId() {
        return pcId;
    }

    // "?pc=..." or "&pc=..." (after other parameters), or nothing.
    private String pcParam(boolean first) {
        String id = pcId;
        return id == null ? "" : (first ? "?pc=" : "&pc=") + id;
    }

    /** Every PC of the host with its state; UnsupportedRouteException from a host with one PC only. */
    public String getPcs(int localPort, String token, int timeoutMs) throws IOException {
        String urlStr = "http://127.0.0.1:" + localPort + "/v1/pcs";
        return sendHttp("GET", urlStr, token, Math.min(timeoutMs, DEFAULT_CONNECT_TIMEOUT_MS), timeoutMs);
    }

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 20000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 20000;

    public RemoteStatus getStatus(int localPort, String token) throws IOException {
        return getStatus(localPort, token, DEFAULT_READ_TIMEOUT_MS);
    }

    /** With a shorter wait, for the status the screen polls (the next poll is the retry). */
    public RemoteStatus getStatus(int localPort, String token, int timeoutMs) throws IOException {
        String urlStr = "http://127.0.0.1:" + localPort + "/v1/status" + pcParam(true);
        String body = sendHttp("GET", urlStr, token, Math.min(timeoutMs, DEFAULT_CONNECT_TIMEOUT_MS), timeoutMs);

        boolean online = body.contains("\"online\":true") || body.contains("\"online\": true");
        Integer port = null;
        int portIdx = body.indexOf("\"port\"");
        if (portIdx >= 0) {
            int colon = body.indexOf(':', portIdx);
            if (colon >= 0) {
                int end = colon + 1;
                while (end < body.length() && (body.charAt(end) == ' ' || Character.isDigit(body.charAt(end)))) {
                    end++;
                }
                String pStr = body.substring(colon + 1, end).trim();
                if (!pStr.isEmpty() && !"null".equals(pStr)) {
                    try {
                        port = Integer.parseInt(pStr);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        long rtt = 0;
        int rttIdx = body.indexOf("\"rtt_ms\"");
        if (rttIdx >= 0) {
            int colon = body.indexOf(':', rttIdx);
            if (colon >= 0) {
                int end = colon + 1;
                while (end < body.length() && (body.charAt(end) == ' ' || Character.isDigit(body.charAt(end)))) {
                    end++;
                }
                String rStr = body.substring(colon + 1, end).trim();
                try {
                    rtt = Long.parseLong(rStr);
                } catch (NumberFormatException ignored) {}
            }
        }

        return new RemoteStatus(online, port, rtt, body);
    }
    public String getMetrics(int localPort, String token) throws IOException {
        String urlStr = "http://127.0.0.1:" + localPort + "/v1/metrics" + pcParam(true);
        return sendHttp("GET", urlStr, token);
    }

    public String getLog(int localPort, String token, int limit) throws IOException {
        String urlStr = "http://127.0.0.1:" + localPort + "/v1/log" + (limit > 0 ? ("?limit=" + limit) : "");
        return sendHttp("GET", urlStr, token);
    }

    public String getLog(int localPort, String token) throws IOException {
        return getLog(localPort, token, 50);
    }

    public String getHistory(int localPort, String token, int minutes) throws IOException {
        String urlStr = "http://127.0.0.1:" + localPort + "/v1/history"
                + (minutes > 0 ? ("?minutes=" + minutes + pcParam(false)) : pcParam(true));
        return sendHttp("GET", urlStr, token);
    }

    public String getHistory(int localPort, String token) throws IOException {
        return getHistory(localPort, token, 60);
    }

    public String getQuota(int localPort, String token, boolean refresh) throws IOException {
        String urlStr = "http://127.0.0.1:" + localPort + "/v1/quota" + (refresh ? "?refresh=1" + pcParam(false) : pcParam(true));
        return sendHttp("GET", urlStr, token);
    }

    public String getQuota(int localPort, String token) throws IOException {
        return getQuota(localPort, token, false);
    }

    public String getUpdateInfo(int localPort, String token) throws IOException {
        String urlStr = "http://127.0.0.1:" + localPort + "/v1/update/info";
        return sendHttp("GET", urlStr, token);
    }

    public HttpURLConnection openUpdateApk(int localPort, String token) throws IOException {
        String urlStr = "http://127.0.0.1:" + localPort + "/v1/update/apk";
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setUseCaches(false);
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        int code = conn.getResponseCode();
        if (code == 401) {
            conn.disconnect();
            throw new DeviceRevokedException("HTTP 401 Unauthorized");
        }
        if (code != 200) {
            conn.disconnect();
            throw new IOException("HTTP " + code);
        }
        return conn;
    }


    public RemoteCommandResult postAction(int localPort, String token, String action) throws IOException {
        if (action == null || (!"wake".equals(action) && !"sleep".equals(action)
                && !"hibernate".equals(action) && !"shutdown".equals(action) && !"abort".equals(action))) {
            throw new IllegalArgumentException("Action not permitted: " + action);
        }

        String urlStr = "http://127.0.0.1:" + localPort + "/v1/" + action + pcParam(true);
        String body = sendHttp("POST", urlStr, token);

        boolean ok = body.contains("\"ok\":true") || body.contains("\"ok\": true");
        String act = extractString(body, "action");
        String detail = extractString(body, "detail");

        return new RemoteCommandResult(ok, act != null ? act : action, detail != null ? detail : "", body);
    }

    private String sendHttp(String method, String urlStr, String token) throws IOException {
        return sendHttp(method, urlStr, token, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    private String sendHttp(String method, String urlStr, String token, int connectTimeoutMs, int readTimeoutMs)
            throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setUseCaches(false);
            conn.setRequestProperty("Connection", "close");
            // The host words action results and errors in this language.
            conn.setRequestProperty("Accept-Language", L10n.language());
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            if ("POST".equalsIgnoreCase(method)) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(0);
                conn.getOutputStream().close();
            }

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) {
                if (code == 401) {
                    throw new DeviceRevokedException("HTTP 401 Unauthorized");
                }
                if (code == 404) {
                    throw new UnsupportedRouteException("HTTP 404 Not Found");
                }
                throw new IOException("HTTP " + code + " (no body)");
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            if (code == 401) {
                throw new DeviceRevokedException("HTTP 401: " + sb.toString());
            }
            if (code == 404) {
                throw new UnsupportedRouteException("HTTP 404: " + sb.toString());
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static String extractString(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }
}
