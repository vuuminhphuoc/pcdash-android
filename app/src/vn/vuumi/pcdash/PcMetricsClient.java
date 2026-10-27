package vn.vuumi.pcdash;

import vn.vuumi.pcdash.i18n.L10n;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class PcMetricsClient {
    public static final int DEFAULT_TIMEOUT_MS = 2000;
    public static final long CACHE_TTL_MS = 2000;
    public static final int MAX_RESPONSE_BYTES = 64 * 1024; // 64 KB

    private final String url;
    private final String agentToken;
    private final PcSettingsSource settings;
    private final TimeSource clock;

    private boolean hasCached = false;
    private long lastFetchTime = 0;
    private String cachedJson = null;
    private volatile String lastError = null;

    public PcMetricsClient() {
        this(resolveSettings(), TimeSource.SYSTEM);
    }

    public PcMetricsClient(PcSettingsSource settings) {
        this(settings, TimeSource.SYSTEM);
    }

    public PcMetricsClient(PcSettingsSource settings, TimeSource clock) {
        this(buildUrl(settings), settings != null ? settings.getAgentToken() : "", clock, settings);
    }

    public PcMetricsClient(String ip, int port) {
        this("http://" + ip + ":" + port + "/metrics", "", TimeSource.SYSTEM);
    }

    public PcMetricsClient(String ip, int port, String agentToken) {
        this("http://" + ip + ":" + port + "/metrics", agentToken, TimeSource.SYSTEM);
    }

    public PcMetricsClient(String url, TimeSource clock) {
        this(url, "", clock);
    }

    public PcMetricsClient(String url, String agentToken, TimeSource clock) {
        this(url, agentToken, clock, null);
    }

    private PcMetricsClient(String url, String agentToken, TimeSource clock, PcSettingsSource settings) {
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
        return "http://" + ip + ":" + port + "/metrics";
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

    public synchronized String getMetricsJson() {
        long now = clock.nowEpochMs();
        if (hasCached && (now - lastFetchTime < CACHE_TTL_MS)) {
            return cachedJson;
        }

        String result = null;
        try {
            String raw = fetchRaw(currentUrl(), DEFAULT_TIMEOUT_MS);
            if (raw != null && MetricsJson.isValidJsonObject(raw)) {
                result = raw.trim();
            }
        } catch (Exception ignored) {
            result = null;
        }

        cachedJson = result;
        lastFetchTime = now;
        hasCached = true;
        return cachedJson;
    }

    protected String fetchRaw(String urlStr, int timeoutMs) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setUseCaches(false);
            conn.setRequestProperty("X-PcAgent-Token", currentToken());

            int code = conn.getResponseCode();
            if (code == 403) {
                lastError = L10n.t("Agent refused: wrong token", "Agent từ chối: sai mã token");
                return "{\"error\":\"" + lastError + "\"}";
            }
            if (code == 503) {
                lastError = L10n.t("The PC agent has no token set", "Agent PC chưa cấu hình token");
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
