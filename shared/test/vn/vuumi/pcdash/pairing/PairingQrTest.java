package vn.vuumi.pcdash.pairing;

public class PairingQrTest {

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError("Assertion failed: " + message);
    }

    private static void assertEquals(Object actual, Object expected, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Assertion failed for " + message + ": expected <" + expected + "> but got <" + actual + ">");
    }

    public static void main(String[] args) {
        System.out.println("Running PairingQrTest...");
        String text = PairingQr.build("192.168.1.20", "ABC234");
        assertEquals(text, "pcdash://pair?v=1&ip=192.168.1.20&code=ABC234", "payload");
        PairingQr qr = PairingQr.parse(text);
        assertEquals(qr.ip, "192.168.1.20", "ip");
        assertEquals(qr.code, "ABC234", "code");
        assertEquals(PairingQr.parse("  pcdash://pair?v=1&code=abc234&ip=10.0.0.5 ").code, "ABC234", "any order, lower case, spaces");

        assertTrue(PairingQr.parse(null) == null, "null");
        assertTrue(PairingQr.parse("pcdash://setup?v=1&ip=192.168.1.2") == null, "the PC setup code is not a pairing code");
        assertTrue(PairingQr.parse("pcdash://pair?v=2&ip=192.168.1.20&code=ABC234") == null, "unknown version");
        assertTrue(PairingQr.parse("pcdash://pair?v=1&ip=8.8.8.8&code=ABC234") == null, "public address");
        assertTrue(PairingQr.parse("pcdash://pair?v=1&ip=100.64.0.1&code=ABC234") == null, "tailnet address");
        assertTrue(PairingQr.parse("pcdash://pair?v=1&ip=172.32.0.1&code=ABC234") == null, "outside 172.16/12");
        assertTrue(PairingQr.parse("pcdash://pair?v=1&ip=192.168.1.300&code=ABC234") == null, "bad octet");
        assertTrue(PairingQr.parse("pcdash://pair?v=1&ip=192.168.1.20&code=ABC23") == null, "short code");
        assertTrue(PairingQr.parse("pcdash://pair?v=1&ip=192.168.1.20&code=ABC230") == null, "0 is not in the alphabet");
        assertTrue(PairingQr.parse("pcdash://pair?v=1&ip=172.20.3.4&code=ZZZZ99") != null, "172.16/12 accepted");
        System.out.println("ALL PairingQrTest PASSED");
    }
}
