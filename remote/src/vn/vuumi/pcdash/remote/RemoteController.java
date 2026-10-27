package vn.vuumi.pcdash.remote;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.Locale;
import vn.vuumi.pcdash.net.PcnetBridge;
import vn.vuumi.pcdash.net.PcnetState;

public class RemoteController {

    public static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    public static final int CODE_LENGTH = 6;

    private final PairingClient pairing;
    private final PcnetBridge bridge;
    private final RemoteConfigStore store;
    private final RemoteApiClient api;
    private final File stateDir;

    private volatile int boundLocalPort = -1;
    private volatile RemoteConfigStore.RemoteConfig currentConfig = null;

    public RemoteController(PairingClient pairing, PcnetBridge bridge, RemoteConfigStore store, RemoteApiClient api, File stateDir) {
        this.pairing = pairing != null ? pairing : new PairingClient();
        this.bridge = bridge != null ? bridge : new PcnetBridge();
        this.store = store;
        this.api = api != null ? api : new RemoteApiClient();
        this.stateDir = stateDir;
    }

    public static String normalizeCode(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        String upper = input.toUpperCase(Locale.US);
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (CODE_ALPHABET.indexOf(c) >= 0) {
                sb.append(c);
                if (sb.length() == CODE_LENGTH) {
                    break;
                }
            }
        }
        return sb.toString();
    }

    // Không khóa: luồng giao diện gọi hàm này, khóa controller có thể đang bị lệnh gọi mạng giữ.
    public boolean isPaired() {
        if (currentConfig != null) return true;
        try {
            RemoteConfigStore.RemoteConfig loaded = store.load();
            if (loaded != null) {
                currentConfig = loaded;
            }
            return loaded != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static final class Session {
        final int port;
        final String token;

        Session(int port, String token) {
            this.port = port;
            this.token = token;
        }
    }

    // Chỉ giữ khóa lúc đọc cấu hình; phần chờ mạng chạy ngoài khóa.
    private synchronized Session session() throws IOException {
        if (currentConfig == null) {
            currentConfig = store.load();
        }
        if (currentConfig == null) {
            throw new IOException("Remote is not paired with any host");
        }
        if (boundLocalPort <= 0) {
            int bp = bridge.getBoundPort();
            if (bp > 0) {
                boundLocalPort = bp;
            } else {
                try {
                    PcnetState st = bridge.state();
                    if (st != null && st.localPort > 0) {
                        boundLocalPort = st.localPort;
                    }
                } catch (Throwable ignored) {}
            }
        }
        if (boundLocalPort <= 0) {
            throw new IOException("Tsnet bridge is not running");
        }
        return new Session(boundLocalPort, currentConfig.deviceToken);
    }

    public PcnetState state() {
        return bridge != null ? bridge.state() : null;
    }

    public synchronized void open() throws IOException {
        try {
            if (bridge.isRunning()) {
                if (boundLocalPort <= 0) {
                    int bp = bridge.getBoundPort();
                    if (bp > 0) {
                        boundLocalPort = bp;
                    } else {
                        try {
                            PcnetState st = bridge.state();
                            if (st != null && st.localPort > 0) {
                                boundLocalPort = st.localPort;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                if (boundLocalPort > 0) {
                    return;
                }
                // tsnet đang chạy ở chế độ đăng nhập (chưa có đích) nhưng đã ghép đôi: khởi động lại với địa chỉ host.
                if (store.load() == null) {
                    return;
                }
                bridge.stop();
            }
        } catch (IllegalStateException e) {
            throw new IOException(e.getMessage(), e);
        }
        currentConfig = store.load();
        if (currentConfig != null) {
            if (stateDir != null && !stateDir.exists()) {
                stateDir.mkdirs();
            }
            String hostname = store != null ? store.getOrCreateHostname() : "pcdash-remote";
            boolean ok;
            try {
                ok = bridge.begin(stateDir, hostname, PcnetBridge.MODE_REMOTE_DIAL, currentConfig.hostTailnetIp, currentConfig.hostPort, 0);
            } catch (IllegalStateException e) {
                throw new IOException(e.getMessage(), e);
            }
            if (ok) {
                int bp = bridge.getBoundPort();
                if (bp > 0) {
                    boundLocalPort = bp;
                } else {
                    try {
                        PcnetState st = bridge.state();
                        if (st != null && st.localPort > 0) {
                            boundLocalPort = st.localPort;
                        }
                    } catch (Throwable ignored) {}
                }
            } else {
                String lastErr = null;
                try {
                    lastErr = bridge.lastError();
                } catch (Exception ignored) {}
                throw new IOException("Failed to start tsnet bridge" + (lastErr != null ? ": " + lastErr : ""));
            }
        }
    }

    public synchronized void pair(InetAddress host, String code, String deviceName) throws Exception {
        if (stateDir != null && !stateDir.exists()) {
            stateDir.mkdirs();
        }
        RemoteConfigStore.RemoteConfig config = pairing.pair(host, code, deviceName, stateDir, bridge);
        store.save(config);
        currentConfig = config;
        String hostname = store != null ? store.getOrCreateHostname() : "pcdash-remote";
        // Phiên tsnet dùng để đăng nhập không có đích chuyển tiếp: dừng rồi bắt đầu lại (trạng thái đăng nhập được giữ).
        try {
            bridge.stop();
        } catch (Exception ignored) {}
        boundLocalPort = -1;
        boolean ok = bridge.begin(stateDir, hostname, PcnetBridge.MODE_REMOTE_DIAL, config.hostTailnetIp, config.hostPort, 0);
        if (ok) {
            int bp = bridge.getBoundPort();
            if (bp > 0) {
                boundLocalPort = bp;
            } else {
                try {
                    PcnetState st = bridge.state();
                    if (st != null && st.localPort > 0) {
                        boundLocalPort = st.localPort;
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    public synchronized void close() {
        try {
            bridge.stop();
        } catch (Exception ignored) {}
        boundLocalPort = -1;
    }

    public synchronized void forgetHost() {
        try {
            bridge.stop();
        } catch (Exception ignored) {}
        boundLocalPort = -1;
        currentConfig = null;
        if (store != null) {
            store.clear();
        }
        if (stateDir != null && stateDir.exists()) {
            deleteRecursively(stateDir);
            stateDir.mkdirs();
        }
    }

    private static boolean isTimeout(IOException e) {
        if (e instanceof java.net.SocketTimeoutException) {
            return true;
        }
        String msg = e.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase(Locale.ROOT);
            return lower.contains("timed out") || lower.contains("timeout") || lower.contains("time out");
        }
        return false;
    }

    private interface RemoteCall<T> {
        T call() throws IOException;
    }

    private <T> T executeWithRetry(RemoteCall<T> call) throws IOException {
        IOException lastEx = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return call.call();
            } catch (RemoteApiClient.DeviceRevokedException e) {
                throw e;
            } catch (IOException e) {
                lastEx = e;
                if (isTimeout(e) && attempt < 2) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue;
                }
                throw e;
            }
        }
        if (lastEx != null) {
            throw lastEx;
        }
        throw new IOException("Remote call failed");
    }

    public RemoteApiClient.RemoteStatus getStatus() throws IOException {
        final Session session = session();
        final int port = session.port;
        final String token = session.token;
        return executeWithRetry(new RemoteCall<RemoteApiClient.RemoteStatus>() {
            @Override
            public RemoteApiClient.RemoteStatus call() throws IOException {
                return api.getStatus(port, token);
            }
        });
    }

    public RemoteApiClient.RemoteCommandResult postAction(final String action) throws IOException {
        final Session session = session();
        final int port = session.port;
        final String token = session.token;
        return executeWithRetry(new RemoteCall<RemoteApiClient.RemoteCommandResult>() {
            @Override
            public RemoteApiClient.RemoteCommandResult call() throws IOException {
                return api.postAction(port, token, action);
            }
        });
    }

    public String getMetrics() throws IOException {
        final Session session = session();
        final int port = session.port;
        final String token = session.token;
        return executeWithRetry(new RemoteCall<String>() {
            @Override
            public String call() throws IOException {
                return api.getMetrics(port, token);
            }
        });
    }
    public String getLog(final int limit) throws IOException {
        final Session session = session();
        final int port = session.port;
        final String token = session.token;
        return executeWithRetry(new RemoteCall<String>() {
            @Override
            public String call() throws IOException {
                return api.getLog(port, token, limit);
            }
        });
    }

    public String getLog() throws IOException {
        return getLog(50);
    }

    public String getHistory(final int minutes) throws IOException {
        final Session session = session();
        final int port = session.port;
        final String token = session.token;
        return executeWithRetry(new RemoteCall<String>() {
            @Override
            public String call() throws IOException {
                return api.getHistory(port, token, minutes);
            }
        });
    }

    public String getHistory() throws IOException {
        return getHistory(60);
    }

    public String getQuota(final boolean refresh) throws IOException {
        final Session session = session();
        final int port = session.port;
        final String token = session.token;
        try {
            return executeWithRetry(new RemoteCall<String>() {
                @Override
                public String call() throws IOException {
                    return api.getQuota(port, token, refresh);
                }
            });
        } catch (RemoteApiClient.UnsupportedRouteException e) {
            throw new RemoteApiClient.UnsupportedRouteException(RemoteApiClient.UnsupportedRouteException.hostNeedsUpdateMessage(), e);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                throw new RemoteApiClient.UnsupportedRouteException(RemoteApiClient.UnsupportedRouteException.hostNeedsUpdateMessage(), e);
            }
            throw e;
        }
    }

    public String getQuota() throws IOException {
        return getQuota(false);
    }


    public UpdateInfo getUpdateInfo() throws IOException {
        final Session session = session();
        final int port = session.port;
        final String token = session.token;
        return executeWithRetry(new RemoteCall<UpdateInfo>() {
            @Override
            public UpdateInfo call() throws IOException {
                String raw = api.getUpdateInfo(port, token);
                try {
                    return UpdateInfo.parse(raw);
                } catch (IllegalArgumentException e) {
                    throw new IOException("Failed to parse update info: " + e.getMessage(), e);
                }
            }
        });
    }

    public void downloadUpdate(UpdateInfo info, File dest, ProgressListener listener, CancelFlag cancelFlag) throws IOException {
        if (info == null || !info.available) {
            throw new IOException("Update is not available");
        }
        if (info.size <= 0 || info.size > UpdateInfo.MAX_SIZE) {
            throw new IOException("Invalid update size: " + info.size);
        }
        if (info.sha256 == null || info.sha256.length() != 64) {
            throw new IOException("Invalid update sha256");
        }
        if (dest == null) {
            throw new IllegalArgumentException("Destination file cannot be null");
        }
        if (cancelFlag != null && cancelFlag.isCancelled()) {
            throw new IOException("Download cancelled");
        }

        final Session session = session();
        final int port = session.port;
        final String token = session.token;

        File parentDir = dest.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        File tempFile = File.createTempFile("pcdash_upd_", ".tmp", parentDir != null ? parentDir : new File("."));
        HttpURLConnection conn = null;
        boolean success = false;
        try {
            conn = api.openUpdateApk(port, token);

            long contentLength = -1;
            String clHeader = conn.getHeaderField("Content-Length");
            if (clHeader != null && !clHeader.trim().isEmpty()) {
                try {
                    contentLength = Long.parseLong(clHeader.trim());
                } catch (NumberFormatException ignored) {}
            } else {
                contentLength = conn.getContentLength();
            }

            if (contentLength != info.size) {
                throw new IOException("Content-Length mismatch: expected " + info.size + " but got " + contentLength);
            }
            if (contentLength > UpdateInfo.MAX_SIZE) {
                throw new IOException("Content-Length exceeds 64 MB: " + contentLength);
            }

            MessageDigest md;
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IOException("SHA-256 not available", e);
            }

            byte[] buffer = new byte[32 * 1024]; // 32 KB blocks
            long totalRead = 0;
            InputStream in = conn.getInputStream();
            try {
                OutputStream out = new FileOutputStream(tempFile);
                try {
                    while (true) {
                        if (cancelFlag != null && cancelFlag.isCancelled()) {
                            throw new IOException("Download cancelled");
                        }
                        int read = in.read(buffer);
                        if (read == -1) {
                            break;
                        }
                        totalRead += read;
                        if (totalRead > info.size) {
                            throw new IOException("Downloaded bytes exceeded expected size (" + info.size + ")");
                        }
                        out.write(buffer, 0, read);
                        md.update(buffer, 0, read);
                        if (listener != null) {
                            listener.onProgress(totalRead, info.size);
                        }
                    }
                    out.flush();
                } finally {
                    try {
                        out.close();
                    } catch (Exception ignored) {}
                }
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {}
            }

            if (cancelFlag != null && cancelFlag.isCancelled()) {
                throw new IOException("Download cancelled");
            }

            if (totalRead != info.size) {
                throw new IOException("Downloaded bytes (" + totalRead + ") does not match expected size (" + info.size + ")");
            }

            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            String computedSha = sb.toString();
            if (!computedSha.equalsIgnoreCase(info.sha256)) {
                throw new IOException("SHA-256 mismatch: expected " + info.sha256 + " but got " + computedSha);
            }

            RemoteConfigStore.defaultAtomicReplace(tempFile, dest);
            success = true;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {}
            }
            if (!success) {
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            }
        }
    }

    public int getBoundLocalPort() {
        return boundLocalPort;
    }

    public PcnetBridge getBridge() {
        return bridge;
    }

    public PairingClient getPairingClient() {
        return pairing;
    }
    public boolean isRequireDangerousAuth() {
        RemoteConfigStore.RemoteConfig cfg = currentConfig;
        if (cfg != null) return cfg.requireDangerousAuth;
        RemoteConfigStore.RemoteConfig cached = store != null ? store.getCached() : null;
        if (cached != null) return cached.requireDangerousAuth;
        return true;
    }

    public void setRequireDangerousAuth(boolean require) throws IOException {
        if (store != null) {
            store.setRequireDangerousAuth(require);
        }
        RemoteConfigStore.RemoteConfig cur = currentConfig;
        if (cur != null) {
            currentConfig = new RemoteConfigStore.RemoteConfig(cur.deviceToken, cur.hostTailnetIp, cur.hostPort, require);
        }
    }


    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
