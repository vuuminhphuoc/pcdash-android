package vn.vuumi.pcdash;

import vn.vuumi.pcdash.i18n.L10n;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class PcQuotaClient {
    public static final int DEFAULT_TIMEOUT_MS = 3000;
    public static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private final String url;
    private final String agentToken;
    private final PcSettingsSource settings;
    private final TimeSource clock;
    private volatile String lastError = null;

    public PcQuotaClient() {
        this(resolveSettings(), TimeSource.SYSTEM);
    }

    public PcQuotaClient(PcSettingsSource settings) {
        this(settings, TimeSource.SYSTEM);
    }

    public PcQuotaClient(PcSettingsSource settings, TimeSource clock) {
        this(buildUrl(settings), settings != null ? settings.getAgentToken() : "", clock, settings);
    }

    public PcQuotaClient(String ip, int port) {
        this("http://" + ip + ":" + port + "/quota", "", TimeSource.SYSTEM);
    }

    public PcQuotaClient(String ip, int port, String agentToken) {
        this("http://" + ip + ":" + port + "/quota", agentToken, TimeSource.SYSTEM);
    }

    public PcQuotaClient(String url, TimeSource clock) {
        this(url, "", clock);
    }

    public PcQuotaClient(String url, String agentToken, TimeSource clock) {
        this(url, agentToken, clock, null);
    }

    private PcQuotaClient(String url, String agentToken, TimeSource clock, PcSettingsSource settings) {
        this.settings = settings;
        this.url = url;
        this.agentToken = agentToken != null ? agentToken : "";
        this.clock = clock != null ? clock : TimeSource.SYSTEM;
    }

    private static PcSettingsSource resolveSettings() {
        try {
            java.lang.reflect.Method m = PcConn.class.getMethod("getSettings");
            Object obj = m.invoke(null);
            if (obj instanceof PcSettingsSource) return (PcSettingsSource) obj;
        } catch (Throwable ignored) {}
        return null;
    }

    private static String buildUrl(PcSettingsSource settings) {
        String ip = (settings != null && settings.isConfigured()) ? settings.getPcIp() : "127.0.0.1";
        int port = (settings != null && settings.getAgentPort() > 0) ? settings.getAgentPort() : 8799;
        return "http://" + ip + ":" + port + "/quota";
    }

    // With a settings source the target and token are read on every request, so a change saved in
    // Cài đặt PC applies at once instead of after the service restarts.
    private String currentUrl() {
        return settings != null ? buildUrl(settings) : url;
    }

    protected String currentToken() {
        String t = settings != null ? settings.getAgentToken() : agentToken;
        return t != null ? t : "";
    }

    public String getLastError() {
        return lastError;
    }

    public String getQuotaJson(boolean refresh) {
        String targetUrl = currentUrl();
        if (refresh) {
            targetUrl = targetUrl + (targetUrl.contains("?") ? "&refresh=1" : "?refresh=1");
        }
        try {
            String raw = fetchRaw(targetUrl, DEFAULT_TIMEOUT_MS);
            if (raw == null) {
                return null;
            }
            if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
                return null;
            }
            return raw.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    protected String fetchRaw(String urlStr, int timeoutMs) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = AgentAuth.connect(urlStr, "GET", null, currentToken(), timeoutMs, timeoutMs);

            int code = conn.getResponseCode();
            String refusal = AgentAuth.refusalMessage(code);
            if (refusal != null) {
                lastError = refusal;
                return "{\"error\":\"" + lastError + "\"}";
            }
            if (code != 200) {
                lastError = "HTTP " + code;
                return null;
            }
            lastError = null;

            int contentLength = conn.getContentLength();
            if (contentLength > MAX_RESPONSE_BYTES) {
                return null;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_RESPONSE_BYTES) {
                    return null;
                }
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
