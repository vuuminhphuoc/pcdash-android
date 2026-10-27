package vn.vuumi.pcdash.remote;

public final class UpdateChecker {

    private UpdateChecker() {}

    public static boolean isUpdateAvailable(UpdateInfo info, int installedVersionCode) {
        if (info == null || !info.available) {
            return false;
        }
        return info.versionCode > installedVersionCode;
    }
}
