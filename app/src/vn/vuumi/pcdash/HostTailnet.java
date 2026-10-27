package vn.vuumi.pcdash;

import java.io.File;
import java.util.List;
import vn.vuumi.pcdash.net.PcnetBridge;
import vn.vuumi.pcdash.net.PcnetState;

public class HostTailnet {

    private final PcnetBridge bridge;

    public HostTailnet() {
        this(new PcnetBridge());
    }

    public HostTailnet(PcnetBridge bridge) {
        this.bridge = bridge != null ? bridge : new PcnetBridge();
    }

    public boolean begin(File stateDir, String hostname, int mode, String host, int hostPort, int localPort) {
        return bridge.begin(stateDir, hostname, mode, host, hostPort, localPort);
    }

    public void stop() {
        bridge.stop();
    }

    public boolean isRunning() {
        return bridge.isRunning();
    }

    public PcnetState state() {
        return bridge.state();
    }

    public String tailnetIp() {
        PcnetState s = state();
        if (s != null && s.tailnetIps != null && !s.tailnetIps.isEmpty()) {
            return s.tailnetIps.get(0);
        }
        return null;
    }

    public boolean isLoggedIn() {
        PcnetState s = state();
        return s != null && s.isRunning() && tailnetIp() != null;
    }

    public String authUrl() {
        PcnetState s = state();
        return (s != null && s.authUrl != null) ? s.authUrl : "";
    }

    public String nodeId() {
        return bridge.nodeId();
    }

    public String lastError() {
        return bridge.lastError();
    }

    public String diagnostics() {
        return bridge.diagnostics();
    }

    public PcnetBridge getBridge() {
        return bridge;
    }
}
