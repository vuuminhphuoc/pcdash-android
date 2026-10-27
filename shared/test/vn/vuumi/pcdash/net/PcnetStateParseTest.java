package vn.vuumi.pcdash.net;

import java.util.List;

public class PcnetStateParseTest {

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    private static void assertEquals(Object actual, Object expected, String msg) {
        if (actual == null ? expected != null : !actual.equals(expected)) {
            throw new AssertionError("Expected [" + expected + "] but got [" + actual + "]: " + msg);
        }
    }

    public static void testAllStates() {
        // 1. Stopped
        String stoppedJson = "{\"state\":\"stopped\",\"authUrl\":\"\",\"tailnetIps\":[],\"localPort\":0,\"error\":\"\"}";
        PcnetState s1 = PcnetState.parse(stoppedJson);
        assertEquals(s1.state, "stopped", "State must be stopped");
        assertTrue(s1.isStopped(), "isStopped() must be true");
        assertEquals(s1.authUrl, "", "authUrl must be empty");
        assertTrue(s1.tailnetIps.isEmpty(), "tailnetIps must be empty");
        assertEquals(s1.localPort, 0, "localPort must be 0");
        assertEquals(s1.error, "", "error must be empty");

        // 2. Starting
        String startingJson = "{\"state\":\"starting\",\"authUrl\":\"\",\"tailnetIps\":[],\"localPort\":0,\"error\":\"\"}";
        PcnetState s2 = PcnetState.parse(startingJson);
        assertEquals(s2.state, "starting", "State must be starting");
        assertTrue(s2.isStarting(), "isStarting() must be true");

        // 3. Needs Login
        String needsLoginJson = "{\"state\":\"needs_login\",\"authUrl\":\"https://login.tailscale.com/a/abc123xyz\",\"tailnetIps\":[],\"localPort\":0,\"error\":\"\"}";
        PcnetState s3 = PcnetState.parse(needsLoginJson);
        assertEquals(s3.state, "needs_login", "State must be needs_login");
        assertTrue(s3.isNeedsLogin(), "isNeedsLogin() must be true");
        assertEquals(s3.authUrl, "https://login.tailscale.com/a/abc123xyz", "authUrl must match");
        assertTrue(s3.tailnetIps.isEmpty(), "tailnetIps must be empty");

        // 4. Running
        String runningJson = "{\"state\":\"running\",\"authUrl\":\"\",\"tailnetIps\":[\"100.64.0.1\",\"100.64.0.2\"],\"localPort\":8091,\"error\":\"\"}";
        PcnetState s4 = PcnetState.parse(runningJson);
        assertEquals(s4.state, "running", "State must be running");
        assertTrue(s4.isRunning(), "isRunning() must be true");
        assertEquals(s4.tailnetIps.size(), 2, "tailnetIps size must be 2");
        assertEquals(s4.tailnetIps.get(0), "100.64.0.1", "First IP must match");
        assertEquals(s4.tailnetIps.get(1), "100.64.0.2", "Second IP must match");
        assertEquals(s4.localPort, 8091, "localPort must match");

        // 5. Error
        String errorJson = "{\"state\":\"error\",\"authUrl\":\"\",\"tailnetIps\":[],\"localPort\":0,\"error\":\"tailscale init failed\"}";
        PcnetState s5 = PcnetState.parse(errorJson);
        assertEquals(s5.state, "error", "State must be error");
        assertTrue(s5.isError(), "isError() must be true");
        assertEquals(s5.error, "tailscale init failed", "error message must match");
    }

    public static void testMalformedJson() {
        // Null input
        PcnetState sNull = PcnetState.parse(null);
        assertEquals(sNull.state, "error", "Null JSON should yield error state");
        assertTrue(sNull.isError(), "Null JSON isError()");
        assertTrue(!sNull.error.isEmpty(), "Null JSON should have error message");

        // Empty string
        PcnetState sEmpty = PcnetState.parse("");
        assertEquals(sEmpty.state, "error", "Empty JSON should yield error state");
        assertTrue(sEmpty.isError(), "Empty JSON isError()");

        // Non-JSON garbage
        PcnetState sGarbage = PcnetState.parse("this is not JSON at all");
        assertEquals(sGarbage.state, "error", "Garbage should yield error state");
        assertTrue(sGarbage.isError(), "Garbage isError()");

        // Incomplete / truncated JSON
        PcnetState sTruncated = PcnetState.parse("{\"state\":\"running\",\"localPort\":");
        assertEquals(sTruncated.state, "error", "Truncated JSON should yield error state");
        assertTrue(sTruncated.isError(), "Truncated JSON isError()");
    }

    public static void testMissingFields() {
        // Only state
        String partial1 = "{\"state\":\"running\"}";
        PcnetState s1 = PcnetState.parse(partial1);
        assertEquals(s1.state, "running", "State must be running");
        assertEquals(s1.authUrl, "", "authUrl defaults to empty");
        assertTrue(s1.tailnetIps.isEmpty(), "tailnetIps defaults to empty list");
        assertEquals(s1.localPort, 0, "localPort defaults to 0");
        assertEquals(s1.error, "", "error defaults to empty");

        // Only needs_login and authUrl
        String partial2 = "{\"state\":\"needs_login\",\"authUrl\":\"https://login.example.com\"}";
        PcnetState s2 = PcnetState.parse(partial2);
        assertEquals(s2.state, "needs_login", "State must be needs_login");
        assertEquals(s2.authUrl, "https://login.example.com", "authUrl must match");
        assertEquals(s2.localPort, 0, "localPort defaults to 0");

        // Empty object
        String partial3 = "{}";
        PcnetState s3 = PcnetState.parse(partial3);
        assertEquals(s3.state, "stopped", "Empty object defaults state to stopped");
        assertTrue(s3.isStopped(), "Empty object isStopped");
        assertEquals(s3.authUrl, "", "authUrl defaults to empty");
        assertTrue(s3.tailnetIps.isEmpty(), "tailnetIps defaults to empty list");
        assertEquals(s3.localPort, 0, "localPort defaults to 0");
        assertEquals(s3.error, "", "error defaults to empty");
    }

    public static void testEscapedStrings() {
        String json = "{\"state\":\"error\",\"error\":\"line1\\nline2 \\\"quoted\\\"\"}";
        PcnetState s = PcnetState.parse(json);
        assertEquals(s.state, "error", "State must be error");
        assertEquals(s.error, "line1\nline2 \"quoted\"", "Escaped characters must be unescaped");
    }

    public static void main(String[] args) {
        System.out.println("Running PcnetStateParseTest...");
        testAllStates();
        System.out.println("  PASS: testAllStates");
        testMalformedJson();
        System.out.println("  PASS: testMalformedJson");
        testMissingFields();
        System.out.println("  PASS: testMissingFields");
        testEscapedStrings();
        System.out.println("  PASS: testEscapedStrings");
        System.out.println("ALL PcnetStateParseTest PASSED");
    }
}
