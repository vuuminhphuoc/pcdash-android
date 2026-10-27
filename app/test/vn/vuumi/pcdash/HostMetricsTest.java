package vn.vuumi.pcdash;

import vn.vuumi.pcdash.i18n.L10n;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public class HostMetricsTest {

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("Assertion failed: " + message);
        }
    }

    private static void assertEquals(Object actual, Object expected, String message) {
        if (actual == null && expected == null) return;
        if (actual == null || !actual.equals(expected)) {
            throw new AssertionError(message + " - expected: <" + expected + ">, but was: <" + actual + ">");
        }
    }

    static class HttpResponse {
        final int statusCode;
        final String body;
        final String contentType;

        HttpResponse(int statusCode, String body, String contentType) {
            this.statusCode = statusCode;
            this.body = body;
            this.contentType = contentType;
        }
    }

    private static HttpResponse sendRequest(int port, String path, String authHeader) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + port + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setUseCaches(false);
            if (authHeader != null) {
                conn.setRequestProperty("Authorization", authHeader);
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = "";
            if (is != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int r;
                while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
                body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            }
            String ct = conn.getContentType();
            return new HttpResponse(code, body, ct);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static void testMetricsJsonValidFullData() {
        FakePhoneMetricsSource phone = FakePhoneMetricsSource.full(
                100, true, 31.5,
                2048L, 6144L,
                80.2, 128.0,
                -55, 866,
                1000L, 500L,
                true,
                "NONE",
                99999L
        );

        String agentPcJson = "{\"cpu\":{\"percent\":12.3,\"tempC\":54.0,\"name\":\"AMD Ryzen 7\"},"
                + "\"ram\":{\"usedMB\":8123,\"totalMB\":32768,\"percent\":24.8},"
                + "\"gpu\":{\"percent\":5.0,\"tempC\":40.0,\"memUsedMB\":1200,\"memTotalMB\":8192,\"name\":\"NVIDIA RTX 3060\"},"
                + "\"net\":{\"downBps\":123456,\"upBps\":2345},"
                + "\"disks\":[{\"name\":\"C:\",\"usedGB\":210.5,\"totalGB\":476.0}],"
                + "\"uptimeSec\":12345}";

        long ts = 1792480000000L;
        String json = MetricsJson.build(ts, agentPcJson, phone);

        assertTrue(MetricsJson.isValidJsonObject(json), "MetricsJson must produce a valid JSON object");
        assertTrue(json.contains("\"ts\":1792480000000"), "Must contain timestamp");
        assertTrue(json.contains("\"pc\":{\"online\":true,"), "Must inject online:true into pc object");
        assertTrue(json.contains("\"cpu\":{\"percent\":12.3,"), "Must contain cpu");
        assertTrue(json.contains("\"ram\":{\"usedMB\":8123,"), "Must contain pc ram");
        assertTrue(json.contains("\"gpu\":{\"percent\":5.0,"), "Must contain gpu");
        assertTrue(json.contains("\"disks\":[{\"name\":\"C:\","), "Must contain disks");
        assertTrue(json.contains("\"phone\":{"), "Must contain phone");
        assertTrue(json.contains("\"battery\":{\"percent\":100,\"charging\":true,\"tempC\":31.5}"), "Must contain battery");
        assertTrue(json.contains("\"ram\":{\"availMB\":2048,\"totalMB\":6144}"), "Must contain phone ram");
        assertTrue(json.contains("\"storage\":{\"freeGB\":80.2,\"totalGB\":128.0}"), "Must contain storage");
        assertTrue(json.contains("\"wifi\":{\"rssiDbm\":-55,\"linkMbps\":866}"), "Must contain wifi");
        assertTrue(json.contains("\"net\":{\"downBps\":1000,\"upBps\":500}"), "Must contain net");
        assertTrue(json.contains("\"tailscale\":true"), "Must contain tailscale: true");
        assertTrue(json.contains("\"thermal\":\"NONE\""), "Must contain thermal: \"NONE\"");
        assertTrue(json.contains("\"uptimeSec\":99999"), "Must contain phone uptimeSec");
    }

    public static void testMetricsJsonNullFields() {
        FakePhoneMetricsSource phone = new FakePhoneMetricsSource();
        phone.hasBattery = true;
        phone.hasStorage = true;
        phone.storageTotalGB = 128.0;

        long ts = 1792480000000L;
        String json = MetricsJson.build(ts, (String) null, phone);

        assertTrue(MetricsJson.isValidJsonObject(json), "Must be valid JSON object with nulls");
        assertTrue(json.contains("\"battery\":{\"percent\":null,\"charging\":null,\"tempC\":null}"), "Null battery subfields");
        assertTrue(json.contains("\"ram\":null"), "Null ram object");
        assertTrue(json.contains("\"storage\":{\"freeGB\":null,\"totalGB\":128.0}"), "Null storage freeGB");
        assertTrue(json.contains("\"wifi\":null"), "Null wifi object");
        assertTrue(json.contains("\"net\":null"), "Null net object");
        assertTrue(json.contains("\"tailscale\":null"), "Null tailscale");
        assertTrue(json.contains("\"thermal\":null"), "Null thermal");
        assertTrue(json.contains("\"uptimeSec\":null"), "Null uptimeSec");
        assertTrue(json.contains("\"pc\":{\"online\":false}"), "PC offline with null pcJson");
    }

    public static void testMetricsJsonPcOffline() {
        FakePhoneMetricsSource phone = FakePhoneMetricsSource.full(
                80, false, 28.0,
                1024L, 4096L,
                50.0, 64.0,
                -60, 433,
                0L, 0L,
                false,
                "LIGHT",
                1234L
        );

        String json = MetricsJson.build(1792480000000L, (String) null, phone);
        assertTrue(MetricsJson.isValidJsonObject(json), "Must be valid JSON");
        assertTrue(json.contains("\"pc\":{\"online\":false}"), "Must have pc.online = false");
        assertTrue(json.contains("\"battery\":{\"percent\":80,\"charging\":false,\"tempC\":28.0}"), "Phone data must remain present");
    }

    public static void testSpecialCharactersEscaping() {
        FakePhoneMetricsSource phone = new FakePhoneMetricsSource();
        phone.thermal = "TEST \"Special\" \t \n \\ Thermal";
        phone.uptimeSec = 100L;

        String pcJson = "{\"cpu\":{\"name\":\"AMD \\\"Zen\\\" \\\\ Processor\\n\"}}";
        String json = MetricsJson.build(1000L, pcJson, phone);

        assertTrue(MetricsJson.isValidJsonObject(json), "Must be valid JSON after escaping");
        assertTrue(json.contains("\"thermal\":\"TEST \\\"Special\\\" \\t \\n \\\\ Thermal\""), "Escaped string in thermal");
        assertTrue(json.contains("\"online\":true"), "Injected online: true into PC JSON");
    }

    public static void testRouteMetricsAuth401And200() throws Exception {
        File tempFile = File.createTempFile("metrics-test-devices", ".json");
        tempFile.deleteOnExit();

        RemoteDeviceStore store = new RemoteDeviceStore(tempFile);
        String validToken = "valid-secret-token-12345";
        store.add("remote-1", validToken, "node-1");

        final String expectedResponse = "{\"ts\":12345,\"pc\":{\"online\":true},\"phone\":null}";
        RemoteActionExecutor dummyExecutor = new RemoteActionExecutor() {
            @Override
            public String statusJson() {
                return "{\"online\":true}";
            }

            @Override
            public String doAction(String action) {
                return "ok";
            }

            @Override
            public String metricsJson() {
                return expectedResponse;
            }
        };

        RemoteApiServer server = new RemoteApiServer(store, dummyExecutor);
        server.start(0);
        int port = server.getPort();

        try {
            // Missing token => 401
            HttpResponse r1 = sendRequest(port, "/v1/metrics", null);
            assertEquals(r1.statusCode, 401, "Missing token should return 401");
            assertTrue(r1.body.contains("unauthorized"), "Should return unauthorized error");

            // Wrong token => 401
            HttpResponse r2 = sendRequest(port, "/v1/metrics", "Bearer wrong-token-xyz");
            assertEquals(r2.statusCode, 401, "Wrong token should return 401");
            assertTrue(r2.body.contains("unauthorized"), "Should return unauthorized error");

            // Valid token => 200
            HttpResponse r3 = sendRequest(port, "/v1/metrics", "Bearer " + validToken);
            assertEquals(r3.statusCode, 200, "Valid token should return 200");
            assertEquals(r3.body, expectedResponse, "Body should match metrics json");
            assertTrue(r3.contentType != null && r3.contentType.contains("application/json"), "Content-Type should be application/json");
        } finally {
            server.close();
        }
    }

    public static void testPcMetricsClientCache2s() {
        final AtomicInteger fetchCount = new AtomicInteger(0);
        final long[] currentTime = new long[]{1000L};
        TimeSource clock = new TimeSource() {
            @Override
            public long nowEpochMs() {
                return currentTime[0];
            }
        };

        PcMetricsClient client = new PcMetricsClient("http://192.168.1.50:8799/metrics", clock) {
            @Override
            protected String fetchRaw(String url, int timeoutMs) throws IOException {
                fetchCount.incrementAndGet();
                return "{\"cpu\":{\"percent\":10.0}}";
            }
        };

        // Call 1 at t = 1000
        String r1 = client.getMetricsJson();
        assertEquals(fetchCount.get(), 1, "First call should fetch from transport");
        assertEquals(r1, "{\"cpu\":{\"percent\":10.0}}", "Result should match");

        // Call 2 at t = 1500 (delta = 500ms < 2000ms) -> cache hit
        currentTime[0] = 1500L;
        String r2 = client.getMetricsJson();
        assertEquals(fetchCount.get(), 1, "Second call within 2s should be cache hit");
        assertEquals(r2, "{\"cpu\":{\"percent\":10.0}}", "Cached result should match");

        // Call 3 at t = 2999 (delta = 1999ms < 2000ms) -> cache hit
        currentTime[0] = 2999L;
        String r3 = client.getMetricsJson();
        assertEquals(fetchCount.get(), 1, "Third call within 2s should be cache hit");
        assertEquals(r3, "{\"cpu\":{\"percent\":10.0}}", "Cached result should match");

        // Call 4 at t = 3000 (delta = 2000ms >= 2000ms) -> cache expired, new fetch
        currentTime[0] = 3000L;
        String r4 = client.getMetricsJson();
        assertEquals(fetchCount.get(), 2, "Call at 2s expiry should trigger fresh fetch");
        assertEquals(r4, "{\"cpu\":{\"percent\":10.0}}", "Fresh result should match");
    }

    public static void testPcMetricsClientMalformedOrOversized() {
        final long[] currentTime = new long[]{1000L};
        TimeSource clock = new TimeSource() {
            @Override
            public long nowEpochMs() {
                return currentTime[0];
            }
        };

        // 1. Malformed JSON
        PcMetricsClient clientMalformed = new PcMetricsClient("http://test/metrics", clock) {
            @Override
            protected String fetchRaw(String url, int timeoutMs) throws IOException {
                return "{\"cpu\": broken";
            }
        };
        String pcRes1 = clientMalformed.getMetricsJson();
        assertEquals(pcRes1, null, "Malformed JSON should return null");
        String fullJson1 = MetricsJson.build(1000L, clientMalformed, null);
        assertTrue(fullJson1.contains("\"pc\":{\"online\":false}"), "Malformed PC JSON yields pc.online=false");

        // 2. HTML/non-JSON error from agent
        PcMetricsClient clientHtml = new PcMetricsClient("http://test/metrics", clock) {
            @Override
            protected String fetchRaw(String url, int timeoutMs) throws IOException {
                return "<html>502 Bad Gateway</html>";
            }
        };
        String pcRes2 = clientHtml.getMetricsJson();
        assertEquals(pcRes2, null, "HTML error should return null");
        String fullJson2 = MetricsJson.build(1000L, clientHtml, null);
        assertTrue(fullJson2.contains("\"pc\":{\"online\":false}"), "HTML response yields pc.online=false");

        // 3. Oversized JSON (> 64 KB)
        StringBuilder big = new StringBuilder();
        big.append("{\"key\":\"");
        for (int i = 0; i < 70000; i++) big.append('a');
        big.append("\"}");
        final String bigStr = big.toString();

        PcMetricsClient clientBig = new PcMetricsClient("http://test/metrics", clock) {
            @Override
            protected String fetchRaw(String url, int timeoutMs) throws IOException {
                if (bigStr.length() > PcMetricsClient.MAX_RESPONSE_BYTES) {
                    return null;
                }
                return bigStr;
            }
        };
        String pcRes3 = clientBig.getMetricsJson();
        assertEquals(pcRes3, null, "Oversized response (>64KB) should return null");
        String fullJson3 = MetricsJson.build(1000L, clientBig, null);
        assertTrue(fullJson3.contains("\"pc\":{\"online\":false}"), "Oversized response yields pc.online=false");
    }

    public static void testTailscaleCidrMatching() throws Exception {
        // 100.64.0.0/10 matches 100.64.0.0 to 100.127.255.255
        assertTrue(LanAddressPolicy.isTailnetIpv4(InetAddress.getByName("100.64.0.1")), "100.64.0.1 should match Tailscale");
        assertTrue(LanAddressPolicy.isTailnetIpv4(InetAddress.getByName("100.100.50.1")), "100.100.50.1 should match Tailscale");
        assertTrue(LanAddressPolicy.isTailnetIpv4(InetAddress.getByName("100.127.255.254")), "100.127.255.254 should match Tailscale");

        assertTrue(!LanAddressPolicy.isTailnetIpv4(InetAddress.getByName("100.63.255.255")), "100.63.255.255 should not match");
        assertTrue(!LanAddressPolicy.isTailnetIpv4(InetAddress.getByName("100.128.0.0")), "100.128.0.0 should not match");
        assertTrue(!LanAddressPolicy.isTailnetIpv4(InetAddress.getByName("192.168.1.1")), "192.168.1.1 should not match");
        assertTrue(!LanAddressPolicy.isTailnetIpv4(InetAddress.getByName("10.0.0.1")), "10.0.0.1 should not match");
        assertTrue(!LanAddressPolicy.isTailnetIpv4(InetAddress.getByName("127.0.0.1")), "127.0.0.1 should not match");
    }

    public static void testMetricsJsonFixedFloatFormatAndNanInfinity() {
        assertEquals(MetricsJson.formatDouble(null), "null", "null should format to null");
        assertEquals(MetricsJson.formatDouble(Double.NaN), "null", "NaN should format to null");
        assertEquals(MetricsJson.formatDouble(Double.POSITIVE_INFINITY), "null", "Positive infinity should format to null");
        assertEquals(MetricsJson.formatDouble(Double.NEGATIVE_INFINITY), "null", "Negative infinity should format to null");
        assertEquals(MetricsJson.formatDouble(31.5), "31.5", "31.5 should format to 31.5");
        assertEquals(MetricsJson.formatDouble(128.0), "128.0", "128.0 should format to 128.0");
        assertEquals(MetricsJson.formatDouble(0.00000001), "0.0", "tiny float should not use scientific notation");
        assertEquals(MetricsJson.formatDouble(10000000.0), "10000000.0", "large float should not use scientific notation");

        FakePhoneMetricsSource phone = new FakePhoneMetricsSource();
        phone.hasBattery = true;
        phone.batteryPercent = 90;
        phone.batteryCharging = false;
        phone.batteryTempC = Double.NaN;
        phone.hasStorage = true;
        phone.storageFreeGB = Double.POSITIVE_INFINITY;
        phone.storageTotalGB = 128.0;

        String json = MetricsJson.build(1000L, (String) null, phone);
        assertTrue(MetricsJson.isValidJsonObject(json), "Must remain valid JSON even with NaN/Infinity");
        assertTrue(json.contains("\"tempC\":null"), "NaN battery temp becomes null in JSON");
        assertTrue(json.contains("\"freeGB\":null"), "Infinity storage becomes null in JSON");
        assertTrue(json.contains("\"totalGB\":128.0"), "Valid storageTotalGB formatted cleanly");
    }

    public static void testPhoneMetricsCollectorSanitization() {
        // Wi-Fi RSSI: <= -127 or >= 0 or Integer.MIN_VALUE => null
        assertEquals(PhoneMetricsCollector.sanitizeWifiRssi(-55), -55, "Normal RSSI -55 is valid");
        assertEquals(PhoneMetricsCollector.sanitizeWifiRssi(-126), -126, "-126 is valid");
        assertEquals(PhoneMetricsCollector.sanitizeWifiRssi(-1), -1, "-1 is valid");
        assertEquals(PhoneMetricsCollector.sanitizeWifiRssi(-127), null, "<= -127 must be null");
        assertEquals(PhoneMetricsCollector.sanitizeWifiRssi(-128), null, "< -127 must be null");
        assertEquals(PhoneMetricsCollector.sanitizeWifiRssi(0), null, ">= 0 must be null");
        assertEquals(PhoneMetricsCollector.sanitizeWifiRssi(5), null, "> 0 must be null");
        assertEquals(PhoneMetricsCollector.sanitizeWifiRssi(Integer.MIN_VALUE), null, "MIN_VALUE must be null");

        // Wi-Fi linkSpeed: <= 0 => null
        assertEquals(PhoneMetricsCollector.sanitizeWifiLinkSpeed(866), 866, "linkSpeed > 0 is valid");
        assertEquals(PhoneMetricsCollector.sanitizeWifiLinkSpeed(0), null, "linkSpeed == 0 must be null");
        assertEquals(PhoneMetricsCollector.sanitizeWifiLinkSpeed(-1), null, "linkSpeed < 0 must be null");

        // Battery temp raw: -400..1500 (-40..150 C), outside => null
        assertEquals(PhoneMetricsCollector.sanitizeBatteryTempC(315), 31.5, "315 raw -> 31.5 C");
        assertEquals(PhoneMetricsCollector.sanitizeBatteryTempC(-400), -40.0, "-400 raw -> -40.0 C");
        assertEquals(PhoneMetricsCollector.sanitizeBatteryTempC(1500), 150.0, "1500 raw -> 150.0 C");
        assertEquals(PhoneMetricsCollector.sanitizeBatteryTempC(-401), null, "Below -400 raw must be null");
        assertEquals(PhoneMetricsCollector.sanitizeBatteryTempC(1501), null, "Above 1500 raw must be null");
        assertEquals(PhoneMetricsCollector.sanitizeBatteryTempC(Integer.MIN_VALUE), null, "MIN_VALUE raw must be null");

        // Battery percent: 0..100, outside => null
        assertEquals(PhoneMetricsCollector.sanitizeBatteryPercent(50, 100), 50, "50% is valid");
        assertEquals(PhoneMetricsCollector.sanitizeBatteryPercent(0, 100), 0, "0% is valid");
        assertEquals(PhoneMetricsCollector.sanitizeBatteryPercent(100, 100), 100, "100% is valid");
        assertEquals(PhoneMetricsCollector.sanitizeBatteryPercent(-1, 100), null, "negative level must be null");
        assertEquals(PhoneMetricsCollector.sanitizeBatteryPercent(101, 100), null, ">100% must be null");
        assertEquals(PhoneMetricsCollector.sanitizeBatteryPercent(50, 0), null, "scale <= 0 must be null");
        assertEquals(PhoneMetricsCollector.sanitizeBatteryPercent(50, -1), null, "scale < 0 must be null");

        // TrafficStats & speeds: UNSUPPORTED (-1) => null, speeds non-negative
        assertEquals(PhoneMetricsCollector.computeNetSpeed(2000L, 1000L, 1000L), 1000L, "Normal rate 1000 Bps");
        assertEquals(PhoneMetricsCollector.computeNetSpeed(-1L, 1000L, 1000L), null, "UNSUPPORTED currentRx must be null");
        assertEquals(PhoneMetricsCollector.computeNetSpeed(1000L, -1L, 1000L), null, "Negative lastRx must be null");
        assertEquals(PhoneMetricsCollector.computeNetSpeed(500L, 1000L, 1000L), null, "Negative delta must be null");
        assertEquals(PhoneMetricsCollector.computeNetSpeed(1000L, 1000L, 50L), null, "Delta ms < 100 must be null");
        assertTrue(PhoneMetricsCollector.computeNetSpeed(1500L, 1000L, 1000L) >= 0, "Speed must be non-negative");
    }

    public static void testPcMetricsClientSendsTokenAndHandles403And503() throws Exception {
        final ServerSocket server = new ServerSocket(0);
        final int port = server.getLocalPort();
        final String token = "secret-token-metrics-456";
        final AtomicReference<String> receivedToken = new AtomicReference<String>();

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 1. 403 request
                    Socket s1 = server.accept();
                    BufferedReader r1 = new BufferedReader(new InputStreamReader(s1.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = r1.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("x-pcagent-token:")) {
                            receivedToken.set(line.substring(line.indexOf(':') + 1).trim());
                        }
                    }
                    OutputStream os1 = s1.getOutputStream();
                    os1.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 21\r\nConnection: close\r\n\r\n{\"error\":\"forbidden\"}".getBytes(StandardCharsets.UTF_8));
                    os1.flush();
                    s1.close();

                    // 2. 503 request
                    Socket s2 = server.accept();
                    BufferedReader r2 = new BufferedReader(new InputStreamReader(s2.getInputStream(), StandardCharsets.UTF_8));
                    while ((line = r2.readLine()) != null && !line.isEmpty()) {}
                    OutputStream os2 = s2.getOutputStream();
                    os2.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 32\r\nConnection: close\r\n\r\n{\"error\":\"token_not_configured\"}".getBytes(StandardCharsets.UTF_8));
                    os2.flush();
                    s2.close();
                } catch (Exception ignored) {
                } finally {
                    try { server.close(); } catch (Exception ignored) {}
                }
            }
        });
        t.start();

        try {
            PcMetricsClient client = new PcMetricsClient("http://127.0.0.1:" + port + "/metrics", token, TimeSource.SYSTEM);
            String r403 = client.getMetricsJson();
            assertTrue(r403 != null && r403.contains("Agent từ chối: sai mã token"), "403 must return Vietnamese error");
            assertEquals(client.getLastError(), "Agent từ chối: sai mã token", "403 lastError message");
            assertEquals(receivedToken.get(), token, "X-PcAgent-Token header must match");

            PcMetricsClient client2 = new PcMetricsClient("http://127.0.0.1:" + port + "/metrics", token, TimeSource.SYSTEM);
            String r503 = client2.getMetricsJson();
            assertTrue(r503 != null && r503.contains("Agent PC chưa cấu hình token"), "503 must return Vietnamese error");
            assertEquals(client2.getLastError(), "Agent PC chưa cấu hình token", "503 lastError message");
        } finally {
            t.join(3000);
        }
    }

    public static void testPcSettingsPreferencesAndCipher() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        FakeSecretCipher cipher = new FakeSecretCipher();
        PcSettings settings = new PcSettings(prefs, cipher);

        // WebToken generated on first use (24 hex chars)
        String wt1 = settings.getWebToken();
        assertEquals(wt1.length(), 24, "WebToken must be 24 hex chars");
        assertTrue(prefs.getString(PcSettings.KEY_WEB_TOKEN_ENC, "").startsWith(FakeSecretCipher.PREFIX), "WebToken must be encrypted in prefs");

        // Defaults before configuration
        assertEquals(settings.getPcIp(), "", "pcIp default empty");
        assertEquals(settings.getPcMac(), "", "pcMac default empty");
        assertEquals(settings.getAgentPort(), 8799, "agentPort default 8799");
        assertEquals(settings.getAgentToken(), "", "agentToken default empty");
        assertEquals(settings.isConfigured(), false, "isConfigured false when empty IP");

        // Save valid settings
        settings.save("192.168.1.50", "00:11:22:33:44:55", 8799, "agent-secret-pass-123");
        assertEquals(settings.getPcIp(), "192.168.1.50", "pcIp persisted");
        assertEquals(settings.getPcMac(), "00:11:22:33:44:55", "pcMac persisted");
        assertEquals(settings.getAgentToken(), "agent-secret-pass-123", "agentToken decrypted correctly");
        assertTrue(prefs.getString(PcSettings.KEY_AGENT_TOKEN_ENC, "").startsWith(FakeSecretCipher.PREFIX), "Agent token must be encrypted in prefs");
        assertEquals(settings.isConfigured(), true, "isConfigured true with RFC1918 IP");

        // Regenerate webToken
        String wt2 = settings.regenerateWebToken();
        assertEquals(wt2.length(), 24, "Regenerated webToken must be 24 hex chars");
        assertTrue(!wt1.equals(wt2), "Regenerated token must differ from old token");
        assertEquals(settings.getWebToken(), wt2, "getWebToken returns new token");
        assertTrue(prefs.getString(PcSettings.KEY_WEB_TOKEN_ENC, "").startsWith(FakeSecretCipher.PREFIX), "New web token must be encrypted in prefs");
    }

    public static void testPcSettingsWebTokenAndValidation() {
        // 1. IP validation
        assertTrue(PcSettings.isValidRfc1918Ip("10.0.0.1"), "10.0.0.1 is valid RFC1918");
        assertTrue(PcSettings.isValidRfc1918Ip("172.16.0.1"), "172.16.0.1 is valid RFC1918");
        assertTrue(PcSettings.isValidRfc1918Ip("172.31.255.254"), "172.31.255.254 is valid RFC1918");
        assertTrue(PcSettings.isValidRfc1918Ip("192.168.1.50"), "192.168.1.50 is valid RFC1918");
        assertTrue(!PcSettings.isValidRfc1918Ip("127.0.0.1"), "127.0.0.1 is loopback, not allowed");
        assertTrue(!PcSettings.isValidRfc1918Ip("100.64.0.1"), "100.64.0.1 is Tailscale CGNAT, not allowed");
        assertTrue(!PcSettings.isValidRfc1918Ip("8.8.8.8"), "8.8.8.8 is public, not allowed");
        assertTrue(!PcSettings.isValidRfc1918Ip(""), "empty string not allowed");
        assertTrue(!PcSettings.isValidRfc1918Ip(null), "null not allowed");
        assertTrue(!PcSettings.isValidRfc1918Ip("999.1.1.1"), "invalid octet not allowed");

        // 2. MAC validation
        assertTrue(PcSettings.isValidMac("00:11:22:33:44:55"), "00:11:22:33:44:55 is valid MAC");
        assertTrue(PcSettings.isValidMac("AA-BB-CC-DD-EE-FF"), "AA-BB-CC-DD-EE-FF is valid MAC");
        assertTrue(!PcSettings.isValidMac("00:11:22"), "Incomplete MAC rejected");
        assertTrue(!PcSettings.isValidMac("invalid-mac"), "Invalid string rejected");
        assertTrue(!PcSettings.isValidMac(""), "Empty MAC rejected by isValidMac");

        // 3. Web token generation and regeneration
        String hex24 = PcSettings.generateRandomHex(24);
        assertEquals(hex24.length(), 24, "Generated hex must be exactly 24 chars");
        assertTrue(hex24.matches("^[0-9a-f]{24}$"), "Generated hex must be lowercase hex");

        String hex24_2 = PcSettings.generateRandomHex(24);
        assertTrue(!hex24.equals(hex24_2), "Successive random hex tokens must be unique");
    }

    public static void main(String[] args) {
        L10n.setOverride(L10n.VI); // the assertions below check the Vietnamese UI text
        System.out.println("Running HostMetricsTest...");
        try {
            testMetricsJsonValidFullData();
            System.out.println("  PASS: testMetricsJsonValidFullData");

            testMetricsJsonNullFields();
            System.out.println("  PASS: testMetricsJsonNullFields");

            testMetricsJsonPcOffline();
            System.out.println("  PASS: testMetricsJsonPcOffline");

            testSpecialCharactersEscaping();
            System.out.println("  PASS: testSpecialCharactersEscaping");

            testRouteMetricsAuth401And200();
            System.out.println("  PASS: testRouteMetricsAuth401And200");

            testPcMetricsClientCache2s();
            System.out.println("  PASS: testPcMetricsClientCache2s");

            testPcMetricsClientMalformedOrOversized();
            System.out.println("  PASS: testPcMetricsClientMalformedOrOversized");

            testTailscaleCidrMatching();
            System.out.println("  PASS: testTailscaleCidrMatching");

            testMetricsJsonFixedFloatFormatAndNanInfinity();
            System.out.println("  PASS: testMetricsJsonFixedFloatFormatAndNanInfinity");

            testPhoneMetricsCollectorSanitization();
            System.out.println("  PASS: testPhoneMetricsCollectorSanitization");

            testPcMetricsClientSendsTokenAndHandles403And503();
            System.out.println("  PASS: testPcMetricsClientSendsTokenAndHandles403And503");

            testPcSettingsWebTokenAndValidation();
            System.out.println("  PASS: testPcSettingsWebTokenAndValidation");

            testPcSettingsPreferencesAndCipher();
            System.out.println("  PASS: testPcSettingsPreferencesAndCipher");
            System.out.println("ALL HostMetricsTest PASSED");
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
