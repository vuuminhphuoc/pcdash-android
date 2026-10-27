package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.i18n.L10n;
import java.io.File;
import java.util.Collections;
import java.util.List;
import vn.vuumi.pcdash.net.PcnetBridge;
import vn.vuumi.pcdash.net.PcnetState;

public class RemoteTailnet {

    private final PcnetBridge bridge;
    private final File stateDir;
    private final String hostname;

    public RemoteTailnet(PcnetBridge bridge, File stateDir, String hostname) {
        this.bridge = bridge != null ? bridge : new PcnetBridge();
        this.stateDir = stateDir;
        this.hostname = hostname != null ? hostname : "pcdash-remote";
    }

    public boolean begin() {
        return begin("", 0);
    }

    public boolean begin(String hostTailnetIp, int hostPort) {
        return bridge.begin(stateDir, hostname, PcnetBridge.MODE_REMOTE_DIAL,
                hostTailnetIp != null ? hostTailnetIp : "", hostPort, 0);
    }

    public PcnetState state() {
        return bridge.state();
    }

    public String authUrl() {
        PcnetState st = state();
        return st != null ? st.authUrl : "";
    }

    public boolean isRunning() {
        PcnetState st = state();
        return st != null && st.isRunning();
    }

    public boolean isNeedsLogin() {
        PcnetState st = state();
        return st != null && st.isNeedsLogin();
    }

    public boolean isStarting() {
        PcnetState st = state();
        return st != null && st.isStarting();
    }

    public boolean isStopped() {
        PcnetState st = state();
        return st != null && st.isStopped();
    }

    public boolean isError() {
        PcnetState st = state();
        return st != null && st.isError();
    }

    public List<String> tailnetIps() {
        PcnetState st = state();
        return st != null && st.tailnetIps != null ? st.tailnetIps : Collections.<String>emptyList();
    }

    public String tailnetIp() {
        List<String> ips = tailnetIps();
        for (String ip : ips) {
            if (ip != null && !ip.contains(":")) {
                return ip;
            }
        }
        return !ips.isEmpty() ? ips.get(0) : null;
    }

    public String nodeId() {
        return bridge.nodeId();
    }

    public void stop() {
        bridge.stop();
    }

    public String lastError() {
        String err = bridge.lastError();
        if (err != null && !err.trim().isEmpty()) {
            return err.trim();
        }
        PcnetState st = state();
        if (st != null && st.error != null && !st.error.trim().isEmpty()) {
            return st.error.trim();
        }
        return "";
    }

    public String getStatusText() {
        PcnetState st = state();
        if (st == null || st.isStopped()) {
            return L10n.t("Stopped", "Đã dừng");
        }
        if (st.isStarting()) {
            return L10n.t("Starting Tailscale...", "Đang khởi động Tailscale...");
        }
        if (st.isNeedsLogin()) {
            return L10n.t("Tailscale sign-in needed", "Cần đăng nhập Tailscale");
        }
        if (st.isRunning()) {
            String ip = tailnetIp();
            return L10n.t("Running", "Đang chạy") + (ip != null ? " (" + ip + ")" : "");
        }
        if (st.isError()) {
            String err = lastError();
            return L10n.t("Tailscale connection error", "Lỗi kết nối Tailscale") + (!err.isEmpty() ? ": " + err : "");
        }
        return st.state;
    }
}
