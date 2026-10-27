package vn.vuumi.pcdash;

import vn.vuumi.pcdash.i18n.L10n;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import vn.vuumi.pcdash.net.PcnetState;

public class RemoteDevicesPresenterTest {

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

    public static void testCountdownAndDeviceRowsNeverExposeToken() throws Exception {
        // Assert countdown formatting
        assertEquals(RemoteDevicesPresenter.formatCountdown(300_000L), "05:00", "300_000 ms -> 05:00");
        assertEquals(RemoteDevicesPresenter.formatCountdown(299_000L), "04:59", "299_000 ms -> 04:59");
        assertEquals(RemoteDevicesPresenter.formatCountdown(60_000L), "01:00", "60_000 ms -> 01:00");
        assertEquals(RemoteDevicesPresenter.formatCountdown(1_000L), "00:01", "1_000 ms -> 00:01");
        assertEquals(RemoteDevicesPresenter.formatCountdown(0L), "00:00", "0 ms -> 00:00");
        assertEquals(RemoteDevicesPresenter.formatCountdown(-1_000L), "00:00", "negative ms -> 00:00");

        // Create sample pairing code and device
        long issuedAt = 10_000L;
        long expiresAt = issuedAt + 300_000L; // 310_000L
        PairingManager.PairingCode code = new PairingManager.PairingCode(
                "ABCDE2", issuedAt, expiresAt, new byte[16], BigInteger.valueOf(12345));

        List<RemoteDeviceStore.RemoteDevice> devices = new ArrayList<RemoteDeviceStore.RemoteDevice>();
        devices.add(new RemoteDeviceStore.RemoteDevice(
                "dev-id-123", "Pixel 8 Pro", "sha256-hashed-secret-token", "node-tailscale-999", 5_000L));

        // Present at issuedAt: 300_000 ms remaining
        RemoteDevicesPresenter.ViewState stateActive = RemoteDevicesPresenter.present(code, devices, issuedAt);
        assertTrue(stateActive.hasActiveCode, "State must have active code");
        assertEquals(stateActive.pairingCode, "ABCDE2", "Pairing code must match");
        assertEquals(stateActive.countdownText, "05:00", "Countdown text must be 05:00");
        assertEquals(stateActive.remainingMs, 300_000L, "Remaining ms must be 300_000");
        assertEquals(stateActive.devices.size(), 1, "Must contain 1 device row");

        RemoteDevicesPresenter.DeviceRow row = stateActive.devices.get(0);
        assertEquals(row.id, "dev-id-123", "Device ID must match");
        assertEquals(row.name, "Pixel 8 Pro", "Device name must match");
        assertEquals(row.nodeId, "node-tailscale-999", "Node ID must match");

        // Present at expiry time (310_000 ms): code must be removed from state
        RemoteDevicesPresenter.ViewState stateExpired = RemoteDevicesPresenter.present(code, devices, expiresAt);
        assertTrue(!stateExpired.hasActiveCode, "Expired code must not be active");
        assertEquals(stateExpired.pairingCode, null, "Expired code must be cleared from state");
        assertEquals(stateExpired.countdownText, null, "Expired countdown must be null");
        assertEquals(stateExpired.remainingMs, 0L, "Expired remainingMs must be 0");
        assertEquals(stateExpired.devices.size(), 1, "Expired state must still contain existing devices");

        String rowStr = row.toString().toLowerCase();
        assertTrue(!rowStr.contains("sha256"), "DeviceRow.toString() must not contain token hash");
        assertTrue(!rowStr.contains("token"), "DeviceRow.toString() must not contain token");
    }

    public static void testDeleteTargetsOnlyRequestedDeviceId() throws Exception {
        File tempDir = Files.createTempDirectory("pcdash_ui_test_").toFile();
        tempDir.deleteOnExit();

        TimeSource clock = TimeSource.SYSTEM;
        RemoteHostController controller = new RemoteHostController(tempDir, new FakeExecutor(), null, null, clock);

        // Add 2 devices to controller
        RemoteDeviceStore.RemoteDevice dev1 = controller.deviceStore().add("Phone 1", "token-raw-1", "node-111");
        RemoteDeviceStore.RemoteDevice dev2 = controller.deviceStore().add("Phone 2", "token-raw-2", "node-222");

        List<RemoteDeviceStore.RemoteDevice> list = controller.listDevices();
        assertEquals(list.size(), 2, "Must have 2 devices");

        RemoteDevicesPresenter.ViewState vs = RemoteDevicesPresenter.present(null, list, clock.nowEpochMs());
        assertEquals(vs.devices.size(), 2, "Must have 2 rows");

        // Action model test: target specifically dev1
        RemoteDevicesPresenter.DeviceRow targetRow = vs.devices.get(0);
        assertEquals(targetRow.id, dev1.id, "Target row ID");

        RemoteDevicesPresenter.DeleteAction action = RemoteDevicesPresenter.createDeleteAction(targetRow);
        assertEquals(action.targetDeviceId, dev1.id, "Delete action must target only dev1");
        assertEquals(action.targetDeviceName, "Phone 1", "Delete action device name");

        // Execute action model
        RemoteDeviceStore.RemoteDevice deleted = action.execute(controller);
        assertEquals(deleted.id, dev1.id, "Deleted device returned by controller");

        // Assert dev1 is gone and dev2 remains untouched
        List<RemoteDeviceStore.RemoteDevice> afterDelete = controller.listDevices();
        assertEquals(afterDelete.size(), 1, "Only 1 device should remain");
        assertEquals(afterDelete.get(0).id, dev2.id, "dev2 must remain intact");
        assertEquals(afterDelete.get(0).name, "Phone 2", "dev2 name intact");
        assertTrue(!controller.deviceStore().accepts("token-raw-1"), "dev1 token must be revoked");
        assertTrue(controller.deviceStore().accepts("token-raw-2"), "dev2 token must still be valid");
    }

    public static void testTailscaleStatusAndLoginButton() {
        // 1. Running state
        PcnetState runningState = new PcnetState("running", "", Collections.singletonList("100.64.0.1"), 8091, "");
        RemoteDevicesPresenter.ViewState vsRunning = RemoteDevicesPresenter.present(null, null, 1000L, runningState);
        assertEquals(vsRunning.tailscaleStatus, "Đang chạy (100.64.0.1)", "Tailscale status running");
        assertTrue(!vsRunning.showLoginButton, "Running state does not show login button");
        assertEquals(vsRunning.authUrl, "", "Running state has empty authUrl");

        // 2. Needs login state
        PcnetState loginState = new PcnetState("needs_login", "https://login.tailscale.com/a/abc", Collections.<String>emptyList(), 0, "");
        RemoteDevicesPresenter.ViewState vsLogin = RemoteDevicesPresenter.present(null, null, 1000L, loginState);
        assertEquals(vsLogin.tailscaleStatus, "Cần đăng nhập Tailscale", "Tailscale status needs login");
        assertTrue(vsLogin.showLoginButton, "Needs login state shows login button");
        assertEquals(vsLogin.authUrl, "https://login.tailscale.com/a/abc", "Auth URL must match");

        // 3. Null state
        RemoteDevicesPresenter.ViewState vsNull = RemoteDevicesPresenter.present(null, null, 1000L, null);
        assertEquals(vsNull.tailscaleStatus, "Chưa kết nối", "Null state status");
        assertTrue(!vsNull.showLoginButton, "Null state does not show login button");

        // 4. Stopped state
        PcnetState stoppedState = new PcnetState("stopped", "", Collections.<String>emptyList(), 0, "");
        RemoteDevicesPresenter.ViewState vsStopped = RemoteDevicesPresenter.present(null, null, 1000L, stoppedState);
        assertEquals(vsStopped.tailscaleStatus, "Đã dừng", "Stopped state status");
        assertTrue(!vsStopped.showLoginButton, "Stopped state does not show login button");
    }

    public static void testNullSafetyAndEmptyDevices() {
        RemoteDevicesPresenter.ViewState vsNull = RemoteDevicesPresenter.present(null, null, 1000L);
        assertTrue(!vsNull.hasActiveCode, "Null code -> not active");
        assertEquals(vsNull.pairingCode, null, "Null code -> null pairingCode");
        assertEquals(vsNull.countdownText, null, "Null code -> null countdown");
        assertEquals(vsNull.remainingMs, 0L, "Null code -> 0 ms");
        assertEquals(vsNull.devices.size(), 0, "Null devices -> empty list");
        assertTrue(!vsNull.hasDevices, "hasDevices must be false");

        RemoteDevicesPresenter.ViewState vsEmpty = RemoteDevicesPresenter.present(null, Collections.<RemoteDeviceStore.RemoteDevice>emptyList(), 1000L);
        assertEquals(vsEmpty.devices.size(), 0, "Empty devices -> empty list");
        assertTrue(!vsEmpty.hasDevices, "hasDevices must be false");
    }

    public static void main(String[] args) throws Exception {
        L10n.setOverride(L10n.VI); // the assertions below check the Vietnamese UI text
        System.out.println("Running RemoteDevicesPresenterTest...");
        testCountdownAndDeviceRowsNeverExposeToken();
        System.out.println("  PASS: testCountdownAndDeviceRowsNeverExposeToken");
        testDeleteTargetsOnlyRequestedDeviceId();
        System.out.println("  PASS: testDeleteTargetsOnlyRequestedDeviceId");
        testTailscaleStatusAndLoginButton();
        System.out.println("  PASS: testTailscaleStatusAndLoginButton");
        testNullSafetyAndEmptyDevices();
        System.out.println("  PASS: testNullSafetyAndEmptyDevices");
        System.out.println("ALL RemoteDevicesPresenterTest PASSED");
    }
}
