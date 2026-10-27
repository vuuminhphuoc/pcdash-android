package vn.vuumi.pcdash;

public class PcProvisioningTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAILED: " + msg);
    }

    private static void assertEquals(Object actual, Object expected, String msg) {
        if (actual == null ? expected != null : !actual.equals(expected)) {
            throw new AssertionError("FAILED: " + msg + " (expected: " + expected + ", got: " + actual + ")");
        }
    }

    private static String payload(String ip, String mac, String port, String token, String v) {
        return "pcdash://setup?v=" + v + "&ip=" + ip + "&mac=" + mac + "&port=" + port + "&token=" + token;
    }

    static void testValid() {
        PcProvisioning p = PcProvisioning.parse(payload("192.168.1.50", "aa:bb:cc:dd:ee:0f", "8799", TOKEN, "1"));
        assertTrue(p != null, "valid payload parses");
        assertEquals(p.ip, "192.168.1.50", "ip");
        assertEquals(p.mac, "AA:BB:CC:DD:EE:0F", "mac upper-cased");
        assertEquals(p.port, 8799, "port");
        assertEquals(p.token, TOKEN, "token");
    }

    static void testWhitespaceAndDefaultPort() {
        PcProvisioning p = PcProvisioning.parse("  pcdash://setup?v=1&ip=10.0.0.7&mac=AA-BB-CC-DD-EE-FF&token=" + TOKEN + "\r\n");
        assertTrue(p != null, "trimmed payload without port parses");
        assertEquals(p.port, PcSettings.DEFAULT_AGENT_PORT, "default port");
    }

    static void testRejects() {
        assertTrue(PcProvisioning.parse(null) == null, "null");
        assertTrue(PcProvisioning.parse("") == null, "empty");
        assertTrue(PcProvisioning.parse("https://example.com/?ip=192.168.1.5") == null, "foreign url");
        assertTrue(PcProvisioning.parse(payload("8.8.8.8", "AA:BB:CC:DD:EE:FF", "8799", TOKEN, "1")) == null, "public ip");
        assertTrue(PcProvisioning.parse(payload("192.168.1.50", "AA:BB:CC", "8799", TOKEN, "1")) == null, "bad mac");
        assertTrue(PcProvisioning.parse(payload("192.168.1.50", "AA:BB:CC:DD:EE:FF", "8799", "short", "1")) == null, "short token");
        assertTrue(PcProvisioning.parse(payload("192.168.1.50", "AA:BB:CC:DD:EE:FF", "8799", TOKEN, "2")) == null, "unknown version");
        assertTrue(PcProvisioning.parse(payload("192.168.1.50", "AA:BB:CC:DD:EE:FF", "0", TOKEN, "1")) == null, "port 0");
        assertTrue(PcProvisioning.parse(payload("192.168.1.50", "AA:BB:CC:DD:EE:FF", "70000", TOKEN, "1")) == null, "port too big");
        assertTrue(PcProvisioning.parse(payload("192.168.1.50", "AA:BB:CC:DD:EE:FF", "abc", TOKEN, "1")) == null, "port not a number");
        assertTrue(PcProvisioning.parse(payload("192.168.1.50", "AA:BB:CC:DD:EE:FF", "8799", TOKEN + "%20x", "1")) == null, "token with space");
        assertTrue(PcProvisioning.parse(payload("192.168.1.50", "AA:BB:CC:DD:EE:FF", "8799", TOKEN + "%3Bx", "1")) == null, "token with symbol");
        assertTrue(PcProvisioning.parse("pcdash://setup?v=1&ip=192.168.1.50&mac=AA:BB:CC:DD:EE:FF") == null, "missing token");
        StringBuilder big = new StringBuilder(TOKEN);
        while (big.length() < 600) big.append('a');
        assertTrue(PcProvisioning.parse(payload("192.168.1.50", "AA:BB:CC:DD:EE:FF", "8799", big.toString(), "1")) == null, "oversized payload");
    }

    public static void main(String[] args) {
        testValid();
        testWhitespaceAndDefaultPort();
        testRejects();
        System.out.println("PcProvisioningTest OK");
    }
}
