package vn.vuumi.pcdash;

public interface PcSettingsSource {
    String getPcIp();
    String getPcMac();
    int getAgentPort();
    String getAgentToken();
    String getWebToken();
    boolean isConfigured();
}
