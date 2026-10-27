package vn.vuumi.pcdash;

import vn.vuumi.pcdash.i18n.L10n;
import java.io.File;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.util.Enumeration;
import java.util.Collections;
import vn.vuumi.pcdash.net.PcnetBridge;
import vn.vuumi.pcdash.net.PcnetState;
import vn.vuumi.pcdash.pairing.PairingMessages;
import vn.vuumi.pcdash.pairing.PairingProtocol;
import vn.vuumi.pcdash.remote.PairingClient;
import vn.vuumi.pcdash.remote.RemoteConfigStore;

public class PairingE2eTest {

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static void assertEquals(Object actual, Object expected, String msg) {
        if (actual == null ? expected != null : !actual.equals(expected)) {
            throw new AssertionError(msg + " - expected: <" + expected + ">, actual: <" + actual + ">");
        }
    }

    static class FakeHostTailnet extends HostTailnet {
        String ip = "100.64.0.99";

        @Override
        public String tailnetIp() {
            return ip;
        }

        @Override
        public boolean isLoggedIn() {
            return ip != null;
        }

        @Override
        public PcnetState state() {
            if (ip != null) {
                return new PcnetState("running", "", Collections.singletonList(ip), 8091, "");
            }
            return new PcnetState("needs_login", "https://login.tailscale.com/a/mock", Collections.<String>emptyList(), 0, "");
        }
    }

    static class FakePcnetBridge extends PcnetBridge {
        String assignedNodeId = "node_pcdash_verified_777";
        boolean running = false;

        @Override
        public int start(File stateDir, String authKey, String host, int hostPort) {
            running = true;
            return 18091;
        }

        @Override
        public String nodeId() {
            return assignedNodeId;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void stop() {
            running = false;
        }
    }

    private static InetAddress findLanIpv4() throws Exception {
        Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
        while (nifs != null && nifs.hasMoreElements()) {
            NetworkInterface nif = nifs.nextElement();
            if (!nif.isUp() || nif.isLoopback()) continue;
            Enumeration<InetAddress> addrs = nif.getInetAddresses();
            while (addrs.hasMoreElements()) {
                InetAddress addr = addrs.nextElement();
                if (addr instanceof Inet4Address && LanAddressPolicy.isAllowedPairingPeer(addr)) {
                    return addr;
                }
            }
        }
        return InetAddress.getByName("127.0.0.1");
    }

    private static int findFreeTcpPort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static int findFreeUdpPort() throws IOException {
        try (DatagramSocket ds = new DatagramSocket(0)) {
            return ds.getLocalPort();
        }
    }

    public static void testPairingWithCorrectCode() throws Exception {
        System.out.println("--- testPairingWithCorrectCode ---");
        PairingClient.setLogListener(new PairingClient.LogListener() {
            @Override
            public void onLog(int priority, String tag, String msg) {
                System.out.println("[REMOTE " + priority + "] " + tag + ": " + msg);
            }
        });
        PairingManager.setLogListener(new PairingManager.LogListener() {
            @Override
            public void onLog(int priority, String tag, String msg) {
                System.out.println("[HOST " + priority + "] " + tag + ": " + msg);
            }
        });
        InetAddress lanIp = findLanIpv4();
        int tcpPort = findFreeTcpPort();
        int udpPort = findFreeUdpPort();

        File tempStore = File.createTempFile("pcdash_e2e_store1_", ".json");
        tempStore.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(tempStore);
        FakeHostTailnet tailnet = new FakeHostTailnet();
        TimeSource clock = TimeSource.SYSTEM;
        PairingManager manager = new PairingManager(store, tailnet, clock);
        manager.start(udpPort, tcpPort);

        try {
            PairingManager.PairingCode codeObj = manager.begin();
            String code = codeObj.getCode();
            System.out.println("Generated pairing code: " + code + ", connecting to " + lanIp.getHostAddress() + ":" + tcpPort);

            File stateDir = File.createTempFile("tsnet_state_e2e1_", "");
            stateDir.delete();
            stateDir.mkdirs();
            stateDir.deleteOnExit();

            FakePcnetBridge bridge = new FakePcnetBridge();
            PairingClient client = new PairingClient(tcpPort);

            RemoteConfigStore.RemoteConfig config = client.pair(lanIp, code, "E2E Phone", stateDir, bridge);
            assertTrue(config != null, "Pairing config must not be null");
            assertEquals(config.hostTailnetIp, "100.64.0.99", "Tailnet IP must match");
            assertEquals(config.hostPort, 8091, "Host port must match");
            assertTrue(config.deviceToken != null && !config.deviceToken.isEmpty(), "Device token must not be empty");
            assertEquals(store.list().size(), 1, "Host device store must immediately have 1 device upon client.pair success");
            assertEquals(store.list().get(0).nodeId, bridge.assignedNodeId, "Node ID in host store must match");
            assertEquals(store.list().get(0).name, "E2E Phone", "Device name in host store must match");
            System.out.println("PASS: testPairingWithCorrectCode");
        } finally {
            manager.close();
        }
    }

    public static void testPairingWithWrongCode() throws Exception {
        System.out.println("--- testPairingWithWrongCode ---");
        InetAddress lanIp = findLanIpv4();
        int tcpPort = findFreeTcpPort();
        int udpPort = findFreeUdpPort();

        File tempStore = File.createTempFile("pcdash_e2e_store2_", ".json");
        tempStore.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(tempStore);
        FakeHostTailnet tailnet = new FakeHostTailnet();
        TimeSource clock = TimeSource.SYSTEM;

        PairingManager manager = new PairingManager(store, tailnet, clock);
        manager.start(udpPort, tcpPort);

        try {
            PairingManager.PairingCode codeObj = manager.begin();
            String code = codeObj.getCode();
            String wrongCode = code.equals("234567") ? "765432" : "234567";

            File stateDir = File.createTempFile("tsnet_state_e2e2_", "");
            stateDir.delete();
            stateDir.mkdirs();
            stateDir.deleteOnExit();

            FakePcnetBridge bridge = new FakePcnetBridge();
            PairingClient client = new PairingClient(tcpPort);

            try {
                client.pair(lanIp, wrongCode, "E2E Phone Wrong", stateDir, bridge);
                throw new AssertionError("Expected pairing with wrong code to fail");
            } catch (PairingClient.PairingException pe) {
                assertEquals(pe.getStage(), PairingClient.Stage.PROOF_REJECTED, "Wrong code stage must be PROOF_REJECTED");
                System.out.println("Caught expected PROOF_REJECTED for wrong code: " + pe.getMessage());
            }

            assertEquals(manager.getFailureCount(), 1, "Host failure count must be incremented to 1");
            assertEquals(store.list().size(), 0, "No device should be registered in store");
            System.out.println("PASS: testPairingWithWrongCode");
        } finally {
            manager.close();
        }
    }

    public static void testPairingWithLowercaseCode() throws Exception {
        System.out.println("--- testPairingWithLowercaseCode ---");
        InetAddress lanIp = findLanIpv4();
        int tcpPort = findFreeTcpPort();
        int udpPort = findFreeUdpPort();

        File tempStore = File.createTempFile("pcdash_e2e_store3_", ".json");
        tempStore.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(tempStore);
        FakeHostTailnet tailnet = new FakeHostTailnet();
        TimeSource clock = TimeSource.SYSTEM;

        PairingManager manager = new PairingManager(store, tailnet, clock);
        manager.start(udpPort, tcpPort);

        try {
            PairingManager.PairingCode codeObj = manager.begin();
            String code = codeObj.getCode();
            String lowerCode = code.toLowerCase();
            System.out.println("Original code: " + code + ", testing lowercase: " + lowerCode);

            File stateDir = File.createTempFile("tsnet_state_e2e3_", "");
            stateDir.delete();
            stateDir.mkdirs();
            stateDir.deleteOnExit();

            FakePcnetBridge bridge = new FakePcnetBridge();
            PairingClient client = new PairingClient(tcpPort);

            RemoteConfigStore.RemoteConfig config = client.pair(lanIp, lowerCode, "E2E Phone Lowercase", stateDir, bridge);
            assertTrue(config != null, "Pairing config must not be null");
            assertEquals(config.hostTailnetIp, "100.64.0.99", "Tailnet IP must match");
            assertEquals(config.hostPort, 8091, "Host port must match");
            assertTrue(config.deviceToken != null && !config.deviceToken.isEmpty(), "Device token must not be empty");
            assertEquals(store.list().size(), 1, "Host device store must immediately have 1 device upon client.pair success");
            assertEquals(store.list().get(0).nodeId, bridge.assignedNodeId, "Node ID in host store must match");
            assertEquals(store.list().get(0).name, "E2E Phone Lowercase", "Device name in host store must match");
            System.out.println("PASS: testPairingWithLowercaseCode");
        } finally {
            manager.close();
        }
    }

    public static void testPairingRejectedWhenHostNotLoggedIn() throws Exception {
        System.out.println("--- testPairingRejectedWhenHostNotLoggedIn ---");
        File tempStore = File.createTempFile("pcdash_e2e_not_logged_in_", ".json");
        tempStore.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(tempStore);
        FakeHostTailnet tailnet = new FakeHostTailnet();
        tailnet.ip = null;
        TimeSource clock = TimeSource.SYSTEM;

        PairingManager manager = new PairingManager(store, tailnet, clock);
        try {
            manager.begin();
            throw new AssertionError("Expected begin() to fail when host not logged in");
        } catch (PairingManager.PairingException pe) {
            assertEquals(pe.getMessage(), "Host chưa đăng nhập Tailscale, mở PCDash host và đăng nhập",
                    "Expected exact Vietnamese error message");
            System.out.println("Caught expected rejection: " + pe.getMessage());
        }
        System.out.println("PASS: testPairingRejectedWhenHostNotLoggedIn");
    }

    public static void main(String[] args) throws Exception {
        L10n.setOverride(L10n.VI); // the assertions below check the Vietnamese UI text
        System.out.println("=== Starting Pairing E2E Integration Tests ===");
        testPairingWithCorrectCode();
        testPairingWithWrongCode();
        testPairingWithLowercaseCode();
        testPairingRejectedWhenHostNotLoggedIn();
        System.out.println("=== ALL PAIRING E2E TESTS PASSED ===");
    }
}
