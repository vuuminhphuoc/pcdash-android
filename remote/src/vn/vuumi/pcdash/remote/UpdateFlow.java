package vn.vuumi.pcdash.remote;

import java.io.File;

public final class UpdateFlow {

    public interface FlowCallback {
        void onPermissionNeeded();
        void onInstallStarted();
        void onError(String message);
    }

    private UpdateFlow() {}

    public static void startInstall(ApkInstaller installer, File apkFile, FlowCallback callback) {
        if (installer == null) {
            if (callback != null) {
                callback.onError("Trình cài đặt không khả dụng");
            }
            return;
        }

        if (!installer.canRequestPackageInstalls()) {
            installer.openInstallPermissionSettings();
            if (callback != null) {
                callback.onPermissionNeeded();
            }
            return;
        }

        try {
            installer.install(apkFile);
            if (callback != null) {
                callback.onInstallStarted();
            }
        } catch (Exception e) {
            if (callback != null) {
                String msg = (e.getMessage() != null && !e.getMessage().isEmpty())
                        ? e.getMessage() : "Lỗi cài đặt bản cập nhật";
                callback.onError(msg);
            }
        }
    }
}
