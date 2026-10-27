package vn.vuumi.pcdash;

import vn.vuumi.pcdash.i18n.L10n;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import vn.vuumi.pcdash.net.PcnetBridge;
import vn.vuumi.pcdash.net.PcnetState;

public class RemoteHostControllerTest {

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

    static class FakeExecutor implements RemoteActionExecutor {
        @Override
        public String statusJson() {
            return "{\"online\":true}";
        }
        @Override
        public String doAction(String action) {
            return "ok: " + action;
        }
    }

    static class FakeBridge extends PcnetBridge {
        String stateJson = "{\"state\":\"stopped\"}";
        boolean stopCalled = false;

        @Override
        public String stateJson() {
            return stateJson;
        }

        @Override
        public void stop() {
            this.stopCalled = true;
            this.stateJson = "{\"state\":\"stopped\"}";
        }
    }

    public static void testRemoveRevokesLocalTokenOnly() throws Exception {
        File tempDir = Files.createTempDirectory("pcdash_ctrl_test_").toFile();
        tempDir.deleteOnExit();

        TimeSource clock = TimeSource.SYSTEM;
        FakeBridge bridge = new FakeBridge();
        bridge.stateJson = "{\"state\":\"running\",\"tailnetIps\":[\"100.64.1.2\"]}";
        HostTailnet hostTailnet = new HostTailnet(bridge);
        RemoteHostController controller = new RemoteHostController(tempDir, new FakeExecutor(), hostTailnet, null, clock);

        String rawToken = "raw-token-valid-device-12345";
        RemoteDeviceStore.RemoteDevice dev = controller.deviceStore().add("Phone X", rawToken, "node-tailscale-888");
        assertTrue(controller.deviceStore().accepts(rawToken), "Device token must be initially accepted");

        // Remove device: only revokes token, no external Tailscale API calls
        RemoteDeviceStore.RemoteDevice removed = controller.removeDevice(dev.id);
        assertEquals(removed.id, dev.id, "Removed device returned");
        assertTrue(!controller.deviceStore().accepts(rawToken), "Device token must be revoked immediately");
        assertEquals(controller.listDevices().size(), 0, "Device store must be empty");
    }

    public static void testLifecycleStartsAndCloses() throws Exception {
        File tempDir = Files.createTempDirectory("pcdash_ctrl_lifecycle_").toFile();
        tempDir.deleteOnExit();

        FakeBridge bridge = new FakeBridge();
        bridge.stateJson = "{\"state\":\"running\",\"tailnetIps\":[\"100.64.1.2\"]}";
        HostTailnet hostTailnet = new HostTailnet(bridge);
        RemoteHostController controller = new RemoteHostController(tempDir, new FakeExecutor(), hostTailnet, null, TimeSource.SYSTEM);

        controller.start();
        assertTrue(controller.apiServer().isRunning(), "ApiServer must be running after start");
        assertTrue(controller.pairingManager().isRunning(), "PairingManager must be running after start");

        controller.close();
        assertTrue(!controller.apiServer().isRunning(), "ApiServer must be closed");
        assertTrue(!controller.pairingManager().isRunning(), "PairingManager must be closed");
        assertTrue(bridge.stopCalled, "HostTailnet must be stopped when controller closes");
    }

    public static void testBeginPairingRequiresLoggedInTailnet() throws Exception {
        File tempDir = Files.createTempDirectory("pcdash_ctrl_pairing_").toFile();
        tempDir.deleteOnExit();

        FakeBridge bridge = new FakeBridge();
        bridge.stateJson = "{\"state\":\"needs_login\",\"authUrl\":\"https://login.tailscale.com/a/xyz\"}";
        HostTailnet hostTailnet = new HostTailnet(bridge);
        RemoteHostController controller = new RemoteHostController(tempDir, new FakeExecutor(), hostTailnet, null, TimeSource.SYSTEM);

        try {
            controller.beginPairing();
            throw new AssertionError("beginPairing must fail when not logged in to tailnet");
        } catch (PairingManager.PairingException pe) {
            assertEquals(pe.getMessage(), "Host chưa đăng nhập Tailscale, mở PCDash host và đăng nhập",
                    "Must reject with exact message from plan");
        }
    }

    public static void main(String[] args) throws Exception {
        L10n.setOverride(L10n.VI); // the assertions below check the Vietnamese UI text
        System.out.println("Running RemoteHostControllerTest...");
        testRemoveRevokesLocalTokenOnly();
        System.out.println("  PASS: testRemoveRevokesLocalTokenOnly");
        testLifecycleStartsAndCloses();
        System.out.println("  PASS: testLifecycleStartsAndCloses");
        testBeginPairingRequiresLoggedInTailnet();
        System.out.println("  PASS: testBeginPairingRequiresLoggedInTailnet");
        System.out.println("ALL RemoteHostControllerTest PASSED");
    }
}
