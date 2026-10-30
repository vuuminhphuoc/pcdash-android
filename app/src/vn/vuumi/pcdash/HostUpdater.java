package vn.vuumi.pcdash;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import vn.vuumi.pcdash.i18n.L10n;

/**
 * Updates the host from this project's latest GitHub release: asks for the newest release, downloads the
 * host APK, checks it against the release's SHA256SUMS.txt and hands it to Android's installer (which
 * asks the person to confirm). Every method here talks to the network or the disk: not on the UI thread.
 */
final class HostUpdater {
    static final String ACTION_RESULT = "vn.vuumi.pcdash.HOST_UPDATE_RESULT";
    private static final int MAX_TEXT_BYTES = 256 * 1024;
    private static final int MAX_APK_BYTES = 80 * 1024 * 1024;
    private static final int TIMEOUT_MS = 20000;

    interface Progress {
        void onPercent(int percent);
    }

    private HostUpdater() {}

    static long installedVersionCode(Context context) throws IOException {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException(e);
        }
    }

    static String installedVersionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /** The newer release, or null when this app is already the newest. */
    static HostUpdateCheck.Offer check(Context context) throws IOException {
        String release = fetchText(HostUpdateCheck.RELEASE_API);
        Map<String, String> files = HostUpdateCheck.assets(release);
        String versionUrl = files.get(HostUpdateCheck.VERSION_ASSET);
        String versionJson = versionUrl != null ? fetchText(versionUrl) : null;
        return HostUpdateCheck.offer(release, versionJson, installedVersionCode(context));
    }

    /** Downloads the offered APK into the app's cache and verifies its checksum. */
    static File download(Context context, HostUpdateCheck.Offer offer, Progress progress) throws IOException {
        if (!HostUpdateCheck.isOfficialDownload(offer.apkUrl) || !HostUpdateCheck.isOfficialDownload(offer.sumsUrl)) {
            throw new IOException(L10n.t("The update is not from this project's release", "Bản cập nhật không nằm trong release của dự án"));
        }
        String expected = HostUpdateCheck.checksumFor(fetchText(offer.sumsUrl), offer.apkName);
        if (expected == null) {
            throw new IOException(L10n.t("The release lists no checksum for the APK", "Release không có mã kiểm tra cho tệp APK"));
        }
        File dir = new File(context.getCacheDir(), "host-update");
        deleteContents(dir);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException(L10n.t("Could not create the download folder", "Không tạo được thư mục tải về"));
        }
        File apk = new File(dir, offer.apkName);
        HttpURLConnection c = open(offer.apkUrl);
        try {
            long total = c.getContentLengthLong();
            if (total > MAX_APK_BYTES) {
                throw new IOException(L10n.t("The update is larger than expected", "Bản cập nhật lớn hơn dự kiến"));
            }
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(apk);
            try {
                byte[] buf = new byte[65536];
                long done = 0;
                int lastPercent = -1;
                int n;
                while ((n = in.read(buf)) > 0) {
                    done += n;
                    if (done > MAX_APK_BYTES) {
                        throw new IOException(L10n.t("The update is larger than expected", "Bản cập nhật lớn hơn dự kiến"));
                    }
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                    if (progress != null && total > 0) {
                        int percent = (int) (done * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            progress.onPercent(percent);
                        }
                    }
                }
                out.flush();
            } finally {
                out.close();
                in.close();
            }
            if (!hex(sha.digest()).equals(expected)) {
                apk.delete();
                throw new IOException(L10n.t("The download does not match its checksum; not installing it",
                        "Tệp tải về không khớp mã kiểm tra, không cài đặt"));
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            c.disconnect();
        }
        return apk;
    }

    static boolean canInstall(Context context) {
        return Build.VERSION.SDK_INT < 26 || context.getPackageManager().canRequestPackageInstalls();
    }

    /** The Android screen where this app is allowed to install apps. */
    static Intent installPermissionIntent(Context context) {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Gives the APK to Android's installer; the result comes to {@link HostUpdateReceiver}. */
    static void install(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(id);
        try {
            OutputStream out = session.openWrite("pcdash_host", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            } finally {
                in.close();
                out.close();
            }
            Intent result = new Intent(ACTION_RESULT).setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(context, id, result, flags).getIntentSender());
        } catch (IOException e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
        }
    }

    private static String fetchText(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_TEXT_BYTES) throw new IOException("response too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "PCDash-host-updater");
        c.setRequestProperty("Accept", "application/vnd.github+json, */*");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        return c;
    }

    /** Removes the APK a finished or abandoned update left in the cache. */
    static void clearCache(Context context) {
        deleteContents(new File(context.getCacheDir(), "host-update"));
    }

    private static void deleteContents(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) f.delete();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
