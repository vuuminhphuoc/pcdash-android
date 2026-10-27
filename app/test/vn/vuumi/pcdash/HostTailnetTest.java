package vn.vuumi.pcdash;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import vn.vuumi.pcdash.net.PcnetBridge;
import vn.vuumi.pcdash.net.PcnetState;

public class HostTailnetTest {

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("Assertion failed: " + message);
        }
    }

    private static void assertEquals(Object actual, Object expected, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Assertion failed for " + message + ": expected <" + expected + "> but got <" + actual + ">");
    }

    static class FakeBridge extends PcnetBridge {
        String stateJson = "{\"state\":\"stopped\"}";
        boolean beginCalled = false;
        boolean stopCalled = false;
        File lastStateDir = null;
        String lastHostname = null;
        int lastMode = -1;
        String lastHost = null;
        int lastHostPort = -1;
        int lastLocalPort = -1;

        @Override
        public String stateJson() {
            return stateJson;
        }

        @Override
        public boolean begin(File stateDir, String hostname, int mode, String host, int hostPort, int localPort) {
            this.beginCalled = true;
            this.lastStateDir = stateDir;
            this.lastHostname = hostname;
            this.lastMode = mode;
            this.lastHost = host;
            this.lastHostPort = hostPort;
            this.lastLocalPort = localPort;
            return true;
        }

        @Override
        public void stop() {
            this.stopCalled = true;
            this.stateJson = "{\"state\":\"stopped\"}";
        }
    }

    public static void testStoppedState() {
        FakeBridge bridge = new FakeBridge();
        bridge.stateJson = "{\"state\":\"stopped\"}";
        HostTailnet tailnet = new HostTailnet(bridge);

        PcnetState state = tailnet.state();
        assertTrue(state.isStopped(), "State must be stopped");
        assertEquals(tailnet.tailnetIp(), null, "Stopped state must return null tailnetIp");
        assertTrue(!tailnet.isLoggedIn(), "Stopped state must not be logged in");
    }

    public static void testNeedsLoginState() {
        FakeBridge bridge = new FakeBridge();
        bridge.stateJson = "{\"state\":\"needs_login\",\"authUrl\":\"https://login.tailscale.com/a/test123\"}";
        HostTailnet tailnet = new HostTailnet(bridge);

        PcnetState state = tailnet.state();
        assertTrue(state.isNeedsLogin(), "State must be needs_login");
        assertEquals(state.authUrl, "https://login.tailscale.com/a/test123", "AuthUrl must match");
        assertEquals(tailnet.authUrl(), "https://login.tailscale.com/a/test123", "tailnet.authUrl() must match");
        assertEquals(tailnet.tailnetIp(), null, "needs_login must return null tailnetIp");
        assertTrue(!tailnet.isLoggedIn(), "needs_login must not be logged in");
    }

    public static void testRunningStateWithIp() {
        FakeBridge bridge = new FakeBridge();
        bridge.stateJson = "{\"state\":\"running\",\"tailnetIps\":[\"100.64.0.55\",\"fd7a:115c:a1e0::37\"]}";
        HostTailnet tailnet = new HostTailnet(bridge);

        PcnetState state = tailnet.state();
        assertTrue(state.isRunning(), "State must be running");
        assertEquals(tailnet.tailnetIp(), "100.64.0.55", "tailnetIp must return first tailnet IPv4");
        assertTrue(tailnet.isLoggedIn(), "running state with IP must be logged in");
    }

    public static void testErrorState() {
        FakeBridge bridge = new FakeBridge();
        bridge.stateJson = "{\"state\":\"error\",\"error\":\"tsnet daemon exited\"}";
        HostTailnet tailnet = new HostTailnet(bridge);

        PcnetState state = tailnet.state();
        assertTrue(state.isError(), "State must be error");
        assertEquals(state.error, "tsnet daemon exited", "Error message must match");
        assertEquals(tailnet.tailnetIp(), null, "error state must return null tailnetIp");
        assertTrue(!tailnet.isLoggedIn(), "error state must not be logged in");
    }

    public static void testBeginAndStop() {
        FakeBridge bridge = new FakeBridge();
        HostTailnet tailnet = new HostTailnet(bridge);

        File dir = new File("/tmp/tsnet");
        boolean ok = tailnet.begin(dir, "pcdash-host", PcnetBridge.MODE_HOST_LISTEN, "", 8091, 8091);
        assertTrue(ok, "begin must return true");
        assertTrue(bridge.beginCalled, "bridge.begin must be called");
        assertEquals(bridge.lastStateDir, dir, "stateDir passed to bridge");
        assertEquals(bridge.lastHostname, "pcdash-host", "hostname passed to bridge");
        assertEquals(bridge.lastMode, PcnetBridge.MODE_HOST_LISTEN, "mode passed to bridge");
        assertEquals(bridge.lastHostPort, 8091, "hostPort passed to bridge");
        assertEquals(bridge.lastLocalPort, 8091, "localPort passed to bridge");

        tailnet.stop();
        assertTrue(bridge.stopCalled, "bridge.stop must be called");
    }

    public static void main(String[] args) {
        System.out.println("Running HostTailnetTest...");
        testStoppedState();
        System.out.println("  PASS: testStoppedState");
        testNeedsLoginState();
        System.out.println("  PASS: testNeedsLoginState");
        testRunningStateWithIp();
        System.out.println("  PASS: testRunningStateWithIp");
        testErrorState();
        System.out.println("  PASS: testErrorState");
        testBeginAndStop();
        System.out.println("  PASS: testBeginAndStop");
        System.out.println("ALL HostTailnetTest PASSED");
    }
}
