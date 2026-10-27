package vn.vuumi.pcdash;

/**
 * One PC the host can wake and control: its name, LAN address, MAC (for Wake-on-LAN) and the PcAgent
 * port and token. A snapshot: edits make a new profile (see {@link PcSettings}).
 */
public final class PcProfile implements PcSettingsSource {
    public final String id;
    public final String name;
    public final String ip;
    public final String mac;
    public final int port;
    public final String token;

    public PcProfile(String id, String name, String ip, String mac, int port, String token) {
        this.id = id;
        this.name = name != null && !name.trim().isEmpty() ? name.trim() : "PC";
        this.ip = ip != null ? ip.trim() : "";
        this.mac = mac != null ? mac.trim() : "";
        this.port = port > 0 ? port : PcSettings.DEFAULT_AGENT_PORT;
        this.token = token != null ? token.trim() : "";
    }

    @Override
    public String getPcIp() {
        return ip;
    }

    @Override
    public String getPcMac() {
        return mac;
    }

    @Override
    public int getAgentPort() {
        return port;
    }

    @Override
    public String getAgentToken() {
        return token;
    }

    /** PCs do not have a web dashboard code of their own; the host's is in {@link PcSettings}. */
    @Override
    public String getWebToken() {
        return "";
    }

    @Override
    public boolean isConfigured() {
        return PcSettings.isValidRfc1918Ip(ip);
    }
}
