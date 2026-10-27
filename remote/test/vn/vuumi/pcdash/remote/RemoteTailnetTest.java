package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.i18n.L10n;
import java.io.File;
import java.util.Collections;
import java.util.List;
import vn.vuumi.pcdash.net.PcnetBridge;
import vn.vuumi.pcdash.net.PcnetState;

public class RemoteTailnetTest {

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void assertEquals(Object actual, Object expected, String msg) {
        if (actual == null ? expected != null : !actual.equals(expected)) {
            throw new AssertionError(msg + " - expected: <" + expected + ">, actual: <" + actual + ">");
        }
    }

    static class MockPcnetBridge extends PcnetBridge {
        File lastStateDir;
        String lastHostname;
        int lastMode = -1;
        String lastHost;
        int lastHostPort = -1;
        int lastLocalPort = -1;
        boolean beginResult = true;
        boolean stopped = false;
        String stateJson = "{\"state\":\"stopped\",\"authUrl\":\"\",\"tailnetIps\":[],\"localPort\":0,\"error\":\"\"}";
        String lastErr = "";
        String assignedNodeId = "node-123";

        @Override
        public boolean isNativeAvailable() {
            return true;
        }

        @Override
        public boolean begin(File stateDir, String hostname, int mode, String host, int hostPort, int localPort) {
            this.lastStateDir = stateDir;
            this.lastHostname = hostname;
            this.lastMode = mode;
            this.lastHost = host;
            this.lastHostPort = hostPort;
            this.lastLocalPort = localPort;
            return beginResult;
        }

        @Override
        public String stateJson() {
            return stateJson;
        }

        @Override
        public void stop() {
            stopped = true;
            stateJson = "{\"state\":\"stopped\",\"authUrl\":\"\",\"tailnetIps\":[],\"localPort\":0,\"error\":\"\"}";
        }

        @Override
        public String lastError() {
            return lastErr;
        }

        @Override
        public String nodeId() {
            return assignedNodeId;
        }
    }

    public static void testBeginCallsBridgeWithCorrectParameters() {
        MockPcnetBridge bridge = new MockPcnetBridge();
        File dir = new File("/tmp/test-tsnet");
        RemoteTailnet tailnet = new RemoteTailnet(bridge, dir, "pcdash-remote-abcd");

        boolean ok = tailnet.begin();
        assertTrue(ok, "begin should return true");
        assertEquals(bridge.lastStateDir, dir, "stateDir must match");
        assertEquals(bridge.lastHostname, "pcdash-remote-abcd", "hostname must match");
        assertEquals(bridge.lastMode, PcnetBridge.MODE_REMOTE_DIAL, "mode must be MODE_REMOTE_DIAL");
        assertEquals(bridge.lastHost, "", "initial host must be empty");
        assertEquals(bridge.lastHostPort, 0, "initial hostPort must be 0");
        assertEquals(bridge.lastLocalPort, 0, "initial localPort must be 0");
        System.out.println("PASS: testBeginCallsBridgeWithCorrectParameters");
    }

    public static void testStateNeedsLoginReportsAuthUrl() {
        MockPcnetBridge bridge = new MockPcnetBridge();
        File dir = new File("/tmp/test-tsnet");
        RemoteTailnet tailnet = new RemoteTailnet(bridge, dir, "pcdash-remote-1234");

        bridge.stateJson = "{\"state\":\"needs_login\",\"authUrl\":\"https://login.tailscale.com/a/xyz987\",\"tailnetIps\":[],\"localPort\":0,\"error\":\"\"}";

        assertTrue(tailnet.isNeedsLogin(), "isNeedsLogin must be true");
        assertTrue(!tailnet.isRunning(), "isRunning must be false");
        assertEquals(tailnet.authUrl(), "https://login.tailscale.com/a/xyz987", "authUrl must match");
        assertTrue(tailnet.getStatusText().contains("Cần đăng nhập"), "Status must mention Cần đăng nhập");
        System.out.println("PASS: testStateNeedsLoginReportsAuthUrl");
    }

    public static void testStateRunningReportsTailnetIps() {
        MockPcnetBridge bridge = new MockPcnetBridge();
        File dir = new File("/tmp/test-tsnet");
        RemoteTailnet tailnet = new RemoteTailnet(bridge, dir, "pcdash-remote-5678");

        bridge.stateJson = "{\"state\":\"running\",\"authUrl\":\"\",\"tailnetIps\":[\"100.101.102.103\"],\"localPort\":18091,\"error\":\"\"}";

        assertTrue(tailnet.isRunning(), "isRunning must be true");
        assertTrue(!tailnet.isNeedsLogin(), "isNeedsLogin must be false");
        assertEquals(tailnet.tailnetIp(), "100.101.102.103", "tailnetIp must match");
        assertTrue(tailnet.getStatusText().contains("100.101.102.103"), "Status must contain IP");
        System.out.println("PASS: testStateRunningReportsTailnetIps");
    }

    public static void testStopDelegatesToBridge() {
        MockPcnetBridge bridge = new MockPcnetBridge();
        File dir = new File("/tmp/test-tsnet");
        RemoteTailnet tailnet = new RemoteTailnet(bridge, dir, "pcdash-remote-9999");

        tailnet.stop();
        assertTrue(bridge.stopped, "bridge.stop must have been called");
        assertTrue(tailnet.isStopped(), "tailnet must report isStopped");
        System.out.println("PASS: testStopDelegatesToBridge");
    }

    public static void testVietnameseStatusMessages() {
        MockPcnetBridge bridge = new MockPcnetBridge();
        File dir = new File("/tmp/test-tsnet");
        RemoteTailnet tailnet = new RemoteTailnet(bridge, dir, "pcdash-remote-test");

        bridge.stateJson = "{\"state\":\"starting\",\"authUrl\":\"\",\"tailnetIps\":[],\"localPort\":0,\"error\":\"\"}";
        assertTrue(tailnet.getStatusText().contains("khởi động"), "Starting must mention khởi động");

        bridge.stateJson = "{\"state\":\"error\",\"authUrl\":\"\",\"tailnetIps\":[],\"localPort\":0,\"error\":\"Mạng không khả dụng\"}";
        assertTrue(tailnet.isError(), "isError must be true");
        assertTrue(tailnet.getStatusText().contains("Lỗi kết nối Tailscale"), "Error must mention Lỗi kết nối Tailscale");
        System.out.println("PASS: testVietnameseStatusMessages");
    }

    public static void main(String[] args) {
        L10n.setOverride(L10n.VI); // the assertions below check the Vietnamese UI text
        System.out.println("Running RemoteTailnetTest...");
        testBeginCallsBridgeWithCorrectParameters();
        testStateNeedsLoginReportsAuthUrl();
        testStateRunningReportsTailnetIps();
        testStopDelegatesToBridge();
        testVietnameseStatusMessages();
        System.out.println("ALL RemoteTailnetTest assertions PASSED!");
    }
}
