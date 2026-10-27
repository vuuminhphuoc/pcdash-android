package vn.vuumi.pcdash.net;

import vn.vuumi.pcdash.i18n.L10n;
import java.io.File;
import java.io.IOException;

public class PcnetBridge {

    public static final int MODE_REMOTE_DIAL = 0;
    public static final int MODE_HOST_LISTEN = 1;

    public static String nativeUnavailableMessage() {
        return L10n.t("Native library missing (unsupported ABI?)", "Thiếu thư viện native (ABI không hỗ trợ?)");
    }
    private static final boolean nativeAvailable;

    static {
        boolean available = false;
        try {
            System.loadLibrary("pcnet");
            System.loadLibrary("pcnet_jni");
            available = true;
        } catch (UnsatisfiedLinkError ignored) {
            available = false;
        }
        nativeAvailable = available;
    }

    public static class DeviceRevokedException extends IOException {
        public DeviceRevokedException(String message) {
            super(message);
        }
    }

    private volatile int boundPort = -1;

    public boolean isNativeAvailable() {
        return nativeAvailable;
    }

    protected void ensureNativeAvailable() {
        if (!isNativeAvailable()) {
            throw new IllegalStateException(nativeUnavailableMessage());
        }
    }

    public boolean begin(File stateDir, String hostname, int mode, String host, int hostPort, int localPort) {
        ensureNativeAvailable();
        String dir = stateDir != null ? stateDir.getAbsolutePath() : "";
        String hName = hostname != null ? hostname : "";
        String hHost = host != null ? host : "";
        int res = nativeBegin(dir, hName, mode, hHost, hostPort, localPort);
        return res == 0;
    }

    public String stateJson() {
        if (!isNativeAvailable()) {
            return "{\"state\":\"error\",\"authUrl\":\"\",\"tailnetIps\":[],\"localPort\":0,\"error\":\""
                    + nativeUnavailableMessage() + "\"}";
        }
        return nativeStateJson();
    }

    public PcnetState state() {
        return PcnetState.parse(stateJson());
    }

    /**
     * Temporary compatibility method for callers using start() until Task 3/4 cutover.
     */
    public int start(File stateDir, String authKey, String host, int hostPort) throws DeviceRevokedException {
        ensureNativeAvailable();
        String dir = stateDir != null ? stateDir.getAbsolutePath() : "";
        int port = nativeStart(dir, authKey != null ? authKey : "", host != null ? host : "", hostPort, 0);
        if (port > 0) {
            this.boundPort = port;
        } else {
            checkRevoked();
        }
        return port;
    }

    public void checkRevoked() throws DeviceRevokedException {
        if (!isNativeAvailable()) {
            return;
        }
        String err = lastError();
        if (err != null && (err.startsWith("NEEDS_LOGIN:") || err.contains("NEEDS_LOGIN"))) {
            throw new DeviceRevokedException(err);
        }
    }

    public void stop() {
        ensureNativeAvailable();
        nativeStop();
        this.boundPort = -1;
    }

    public boolean isRunning() {
        ensureNativeAvailable();
        return nativeRunning();
    }

    public String nodeId() {
        ensureNativeAvailable();
        return nativeNodeId();
    }

    public String lastError() {
        ensureNativeAvailable();
        return nativeLastError();
    }

    public String diagnostics() {
        ensureNativeAvailable();
        return nativeDiagnostics();
    }

    /**
     * The tailnet's other devices and whether each is online, as the coordination server sees them
     * (the Tailscale device list), as JSON; null when the node is not available.
     */
    public String peersJson() {
        if (!isNativeAvailable()) {
            return null;
        }
        return nativePeersJson();
    }

    public int getBoundPort() {
        return boundPort;
    }

    protected void setBoundPort(int port) {
        this.boundPort = port;
    }

    private static native int nativeBegin(String stateDir, String hostname, int mode, String host, int hostPort, int localPort);
    private static native String nativeStateJson();
    private static native int nativeStart(String stateDir, String authKey, String host, int hostPort, int localPort);
    private static native void nativeStop();
    private static native boolean nativeRunning();
    private static native String nativeNodeId();
    private static native String nativeLastError();
    private static native String nativeDiagnostics();
    private static native String nativePeersJson();
}
