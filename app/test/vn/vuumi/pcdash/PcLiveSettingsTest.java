package vn.vuumi.pcdash;

import java.io.IOException;

/** Values saved in Cài đặt PC must reach the already-running host service without a restart. */
public class PcLiveSettingsTest {
    private static void assertEquals(Object actual, Object expected, String msg) {
        if (actual == null ? expected != null : !actual.equals(expected)) {
            throw new AssertionError("FAILED: " + msg + " (expected: " + expected + ", got: " + actual + ")");
        }
    }

    static class RecordingMetrics extends PcMetricsClient {
        String lastUrl;
        RecordingMetrics(PcSettingsSource s) { super(s, TimeSource.SYSTEM); }
        @Override protected String fetchRaw(String urlStr, int timeoutMs) throws IOException {
            lastUrl = urlStr;
            return "{\"ok\":true}";
        }
        String token() { return currentToken(); }
    }

    static class RecordingQuota extends PcQuotaClient {
        String lastUrl;
        RecordingQuota(PcSettingsSource s) { super(s, TimeSource.SYSTEM); }
        @Override protected String fetchRaw(String urlStr, int timeoutMs) throws IOException {
            lastUrl = urlStr;
            return "{\"ok\":true}";
        }
        String token() { return currentToken(); }
    }

    public static void main(String[] args) {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        FakeSecretCipher cipher = new FakeSecretCipher();
        PcSettings service = new PcSettings(prefs, cipher);   // created at service start, nothing configured yet
        PcSettings screen = new PcSettings(prefs, cipher);    // the settings screen has its own instance

        RecordingMetrics metrics = new RecordingMetrics(service);
        RecordingQuota quota = new RecordingQuota(service);
        assertEquals(metrics.token(), "", "no token before setup");

        screen.save("192.168.1.50", "AA:BB:CC:DD:EE:FF", 8801, "0123456789abcdef0123456789abcdef");

        assertEquals(service.getAgentToken(), "0123456789abcdef0123456789abcdef", "service sees saved agent token");
        assertEquals(metrics.token(), "0123456789abcdef0123456789abcdef", "metrics client uses saved token");
        assertEquals(quota.token(), "0123456789abcdef0123456789abcdef", "quota client uses saved token");
        metrics.getMetricsJson();
        quota.getQuotaJson(false);
        assertEquals(metrics.lastUrl, "http://192.168.1.50:8801/metrics", "metrics url follows saved settings");
        assertEquals(quota.lastUrl, "http://192.168.1.50:8801/quota", "quota url follows saved settings");

        screen.save("192.168.1.60", "AA:BB:CC:DD:EE:FF", 8799, "fedcba9876543210fedcba9876543210");
        assertEquals(metrics.token(), "fedcba9876543210fedcba9876543210", "token change applies");

        String before = service.getWebToken();
        String regenerated = screen.regenerateWebToken();
        assertEquals(service.getWebToken(), regenerated, "web token regenerated elsewhere is picked up");
        if (before.equals(regenerated)) throw new AssertionError("FAILED: regenerated web token should differ");

        System.out.println("PcLiveSettingsTest OK");
    }
}
