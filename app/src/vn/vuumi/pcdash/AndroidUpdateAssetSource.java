package vn.vuumi.pcdash;

import android.content.res.AssetManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class AndroidUpdateAssetSource implements UpdateAssetSource {
    private static final String UPDATE_INFO_PATH = "remote-update.json";
    private static final String UPDATE_APK_PATH = "remote-update.apk";

    private final AssetManager assets;
    private volatile long cachedApkSize = -1;

    public AndroidUpdateAssetSource(AssetManager assets) {
        this.assets = assets;
    }

    @Override
    public InputStream openInfo() throws IOException {
        if (assets == null) return null;
        try {
            return assets.open(UPDATE_INFO_PATH);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public InputStream openApk() throws IOException {
        if (assets == null) return null;
        try {
            return assets.open(UPDATE_APK_PATH);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public long apkSize() {
        if (cachedApkSize >= 0) {
            return cachedApkSize;
        }
        if (assets == null) {
            return -1;
        }

        try {
            InputStream is = openInfo();
            if (is != null) {
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        baos.write(buf, 0, r);
                    }
                    String str = new String(baos.toByteArray(), StandardCharsets.UTF_8);
                    int idx = str.indexOf("\"size\":");
                    if (idx >= 0) {
                        int start = idx + 7;
                        while (start < str.length() && Character.isWhitespace(str.charAt(start))) {
                            start++;
                        }
                        int end = start;
                        while (end < str.length() && Character.isDigit(str.charAt(end))) {
                            end++;
                        }
                        if (end > start) {
                            long size = Long.parseLong(str.substring(start, end));
                            if (size > 0) {
                                cachedApkSize = size;
                                return size;
                            }
                        }
                    }
                } finally {
                    try { is.close(); } catch (IOException ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        try {
            InputStream is = openApk();
            if (is != null) {
                try {
                    long avail = is.available();
                    if (avail > 0) {
                        cachedApkSize = avail;
                        return avail;
                    }
                } finally {
                    try { is.close(); } catch (IOException ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        return -1;
    }
}
