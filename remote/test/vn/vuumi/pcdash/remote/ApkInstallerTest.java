package vn.vuumi.pcdash.remote;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ApkInstallerTest {

    public static void main(String[] args) throws Exception {
        System.out.println("Running ApkInstallerTest...");
        testFlowWhenPermissionNeeded();
        testFlowWhenPermissionGranted();
        testFlowWhenInstallFails();
        testStatusMessages();
        System.out.println("ALL ApkInstallerTest assertions PASSED!");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    private static class FakeApkInstaller implements ApkInstaller {
        final List<String> callOrder = new ArrayList<String>();
        boolean canRequest = true;
        int canRequestCalls = 0;
        int openSettingsCalls = 0;
        int installCalls = 0;
        File installedFile = null;
        IOException installException = null;

        @Override
        public boolean canRequestPackageInstalls() {
            callOrder.add("canRequestPackageInstalls");
            canRequestCalls++;
            return canRequest;
        }

        @Override
        public void openInstallPermissionSettings() {
            callOrder.add("openInstallPermissionSettings");
            openSettingsCalls++;
        }

        @Override
        public void install(File apkFile) throws IOException {
            callOrder.add("install");
            installCalls++;
            installedFile = apkFile;
            if (installException != null) {
                throw installException;
            }
        }
    }

    private static class RecordingCallback implements UpdateFlow.FlowCallback {
        boolean permissionNeededCalled = false;
        boolean installStartedCalled = false;
        String errorMessage = null;

        @Override
        public void onPermissionNeeded() {
            permissionNeededCalled = true;
        }

        @Override
        public void onInstallStarted() {
            installStartedCalled = true;
        }

        @Override
        public void onError(String message) {
            errorMessage = message;
        }
    }

    public static void testFlowWhenPermissionNeeded() {
        FakeApkInstaller fake = new FakeApkInstaller();
        fake.canRequest = false;
        RecordingCallback cb = new RecordingCallback();
        File apk = new File("test.apk");

        UpdateFlow.startInstall(fake, apk, cb);

        assertTrue(fake.canRequestCalls == 1, "must check canRequestPackageInstalls");
        assertTrue(fake.openSettingsCalls == 1, "must call openInstallPermissionSettings");
        assertTrue(fake.installCalls == 0, "must NOT call install when permission is missing");
        assertTrue(cb.permissionNeededCalled, "callback must receive onPermissionNeeded");
        assertTrue(!cb.installStartedCalled, "installStarted must not be called");
        assertTrue(fake.callOrder.equals(Arrays.asList("canRequestPackageInstalls", "openInstallPermissionSettings")),
                "calls must happen in strict order: [canRequestPackageInstalls, openInstallPermissionSettings]");

        System.out.println("  PASS: testFlowWhenPermissionNeeded");
    }

    public static void testFlowWhenPermissionGranted() {
        FakeApkInstaller fake = new FakeApkInstaller();
        fake.canRequest = true;
        RecordingCallback cb = new RecordingCallback();
        File apk = new File("test.apk");

        UpdateFlow.startInstall(fake, apk, cb);

        assertTrue(fake.canRequestCalls == 1, "must check canRequestPackageInstalls");
        assertTrue(fake.openSettingsCalls == 0, "must NOT call openInstallPermissionSettings when allowed");
        assertTrue(fake.installCalls == 1, "must call install when allowed");
        assertTrue(fake.installedFile == apk, "installed file must match");
        assertTrue(cb.installStartedCalled, "callback must receive onInstallStarted");
        assertTrue(!cb.permissionNeededCalled, "permissionNeeded must not be called");
        assertTrue(fake.callOrder.equals(Arrays.asList("canRequestPackageInstalls", "install")),
                "calls must happen in strict order: [canRequestPackageInstalls, install]");

        System.out.println("  PASS: testFlowWhenPermissionGranted");
    }

    public static void testFlowWhenInstallFails() {
        FakeApkInstaller fake = new FakeApkInstaller();
        fake.canRequest = true;
        fake.installException = new IOException("Disk full");
        RecordingCallback cb = new RecordingCallback();
        File apk = new File("test.apk");

        UpdateFlow.startInstall(fake, apk, cb);

        assertTrue(fake.installCalls == 1, "must attempt install");
        assertTrue(cb.errorMessage != null && cb.errorMessage.contains("Disk full"),
                "callback must receive error message");
        assertTrue(!cb.installStartedCalled, "installStarted must not be called on error");

        System.out.println("  PASS: testFlowWhenInstallFails");
    }

    public static void testStatusMessages() {
        assertTrue("Cập nhật thành công!".equals(
                PackageInstallerStatus.getStatusMessage(PackageInstallerStatus.STATUS_SUCCESS, null)),
                "STATUS_SUCCESS message");

        assertTrue("Người dùng đã hủy cài đặt".equals(
                PackageInstallerStatus.getStatusMessage(PackageInstallerStatus.STATUS_FAILURE_ABORTED, null)),
                "STATUS_FAILURE_ABORTED message");

        assertTrue("Cài đặt bị chặn bởi thiết bị".equals(
                PackageInstallerStatus.getStatusMessage(PackageInstallerStatus.STATUS_FAILURE_BLOCKED, null)),
                "STATUS_FAILURE_BLOCKED message");

        assertTrue("Xung đột với bản cài đặt hiện tại (chữ ký không khớp)".equals(
                PackageInstallerStatus.getStatusMessage(PackageInstallerStatus.STATUS_FAILURE_CONFLICT, null)),
                "STATUS_FAILURE_CONFLICT message");

        assertTrue("Không đủ dung lượng bộ nhớ để cài đặt".equals(
                PackageInstallerStatus.getStatusMessage(PackageInstallerStatus.STATUS_FAILURE_STORAGE, null)),
                "STATUS_FAILURE_STORAGE message");

        assertTrue("Bản cập nhật không tương thích với thiết bị".equals(
                PackageInstallerStatus.getStatusMessage(PackageInstallerStatus.STATUS_FAILURE_INCOMPATIBLE, null)),
                "STATUS_FAILURE_INCOMPATIBLE message");

        assertTrue("Tệp APK cập nhật không hợp lệ".equals(
                PackageInstallerStatus.getStatusMessage(PackageInstallerStatus.STATUS_FAILURE_INVALID, null)),
                "STATUS_FAILURE_INVALID message");

        assertTrue("Cài đặt thất bại: Lỗi kết nối".equals(
                PackageInstallerStatus.getStatusMessage(PackageInstallerStatus.STATUS_FAILURE, "Lỗi kết nối")),
                "generic STATUS_FAILURE with extra msg");

        assertTrue("Cài đặt thất bại".equals(
                PackageInstallerStatus.getStatusMessage(PackageInstallerStatus.STATUS_FAILURE, "")),
                "generic STATUS_FAILURE without extra msg");

        System.out.println("  PASS: testStatusMessages");
    }
}
