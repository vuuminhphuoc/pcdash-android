package vn.vuumi.pcdash.remote;

import java.io.File;
import java.io.IOException;

public interface ApkInstaller {
    boolean canRequestPackageInstalls();
    void openInstallPermissionSettings();
    void install(File apkFile) throws IOException;
}
