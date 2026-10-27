package vn.vuumi.pcdash.remote;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class UpdateInstaller implements ApkInstaller {

    public static final String ACTION_UPDATE_RESULT = "vn.vuumi.pcdash.remote.UPDATE_RESULT";

    private final Context context;

    public UpdateInstaller(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
    }

    @Override
    public boolean canRequestPackageInstalls() {
        if (Build.VERSION.SDK_INT >= 26) { // Build.VERSION_CODES.O
            PackageManager pm = context.getPackageManager();
            return pm != null && pm.canRequestPackageInstalls();
        }
        return true;
    }

    @Override
    public void openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= 26) { // Build.VERSION_CODES.O
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    @Override
    public void install(File apkFile) throws IOException {
        if (apkFile == null || !apkFile.exists() || apkFile.length() <= 0) {
            throw new IOException("Tệp APK không tồn tại hoặc rỗng");
        }

        PackageManager pm = context.getPackageManager();
        if (pm == null) {
            throw new IOException("PackageManager không khả dụng");
        }

        PackageInstaller packageInstaller = pm.getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (Build.VERSION.SDK_INT >= 31) { // Build.VERSION_CODES.S
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }

        int sessionId = packageInstaller.createSession(params);
        PackageInstaller.Session session = null;
        try {
            session = packageInstaller.openSession(sessionId);
            try (OutputStream out = session.openWrite("pcdash_update", 0, apkFile.length());
                 InputStream in = new FileInputStream(apkFile)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                session.fsync(out);
            }

            Intent intent = new Intent(ACTION_UPDATE_RESULT);
            intent.setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags);
            session.commit(pendingIntent.getIntentSender());
        } catch (Exception e) {
            if (session != null) {
                try {
                    session.abandon();
                } catch (Exception ignored) {}
            }
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Lỗi tạo phiên cài đặt: " + e.getMessage(), e);
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {}
            }
        }
    }

    public static String getStatusMessage(int status, String extraMsg) {
        return PackageInstallerStatus.getStatusMessage(status, extraMsg);
    }
}
