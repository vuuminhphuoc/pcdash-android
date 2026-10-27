package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.i18n.L10n;

public class TailnetPeersTest {

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError("Assertion failed: " + message);
    }

    private static void assertEquals(Object actual, Object expected, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Assertion failed for " + message + ": expected <" + expected + "> but got <" + actual + ">");
    }

    // Shape written by pcnet's GetPeers (non-ASCII escaped as \\uXXXX).
    private static final String JSON = "{\"backendState\":\"Running\",\"peers\":["
            + "{\"hostName\":\"desktop\",\"os\":\"windows\",\"ips\":[\"100.64.0.3\"],\"online\":true,\"target\":false},"
            + "{\"hostName\":\"Ph\\u01b0\\u1edbc pixel\",\"dnsName\":\"pixel.tail.ts.net\",\"os\":\"android\","
            + "\"ips\":[\"100.64.0.7\",\"fd7a::7\"],\"online\":false,\"lastSeen\":\"2026-10-27T13:05:00Z\",\"target\":true}]}";

    private static void testParsesHostAndState() {
        TailnetPeers p = TailnetPeers.parse(JSON);
        assertEquals(p.backendState, "Running", "backend state");
        assertEquals(p.peers.size(), 2, "two peers");
        TailnetPeers.Peer host = p.host();
        assertEquals(host.hostName, "Phước pixel", "unicode escapes decoded");
        assertEquals(host.online, false, "offline");
        assertEquals(host.ips.get(0), "100.64.0.7", "first IP");
        assertEquals(host.lastSeenMs, 1793106300000L, "last seen parsed as UTC");
        assertEquals(p.peers.get(0).lastSeenMs, -1L, "online peers have no last seen");
        System.out.println("PASS: testParsesHostAndState");
    }

    private static void testNoTargetOrBadJson() {
        TailnetPeers p = TailnetPeers.parse("{\"backendState\":\"Running\",\"peers\":[]}");
        assertTrue(p != null && p.host() == null, "Empty list: no host");
        assertEquals(TailnetPeers.parse(null), null, "null");
        assertEquals(TailnetPeers.parse("not json"), null, "garbage");
        assertEquals(TailnetPeers.parse("{\"backendState\":\"Running\"}"), null, "no peers array");
        System.out.println("PASS: testNoTargetOrBadJson");
    }

    private static void testAgo() {
        L10n.setOverride("en");
        try {
            long now = 10_000_000_000L;
            assertEquals(TailnetPeers.ago(-1L, now), "", "unknown");
            assertEquals(TailnetPeers.ago(now - 20_000L, now), "just now", "seconds");
            assertEquals(TailnetPeers.ago(now - 12 * 60_000L, now), "12 min ago", "minutes");
            assertEquals(TailnetPeers.ago(now - 3 * 3600_000L, now), "3 h ago", "hours");
            assertEquals(TailnetPeers.ago(now - 72 * 3600_000L, now), "3 days ago", "days");
        } finally {
            L10n.setOverride(null);
        }
        System.out.println("PASS: testAgo");
    }

    public static void main(String[] args) {
        System.out.println("Running TailnetPeersTest...");
        testParsesHostAndState();
        testNoTargetOrBadJson();
        testAgo();
        System.out.println("ALL TailnetPeersTest PASSED");
    }
}
