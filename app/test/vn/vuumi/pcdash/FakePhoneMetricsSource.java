package vn.vuumi.pcdash;

public class FakePhoneMetricsSource implements PhoneMetricsSource {
    public boolean hasBattery;
    public Integer batteryPercent;
    public Boolean batteryCharging;
    public Double batteryTempC;

    public boolean hasRam;
    public Long ramAvailMB;
    public Long ramTotalMB;

    public boolean hasStorage;
    public Double storageFreeGB;
    public Double storageTotalGB;

    public boolean hasWifi;
    public Integer wifiRssiDbm;
    public Integer wifiLinkMbps;

    public boolean hasNet;
    public Long netDownBps;
    public Long netUpBps;

    public Boolean tailscale;
    public String thermal;
    public Long uptimeSec;

    public FakePhoneMetricsSource() {
    }

    public static FakePhoneMetricsSource full(Integer batteryPercent, Boolean batteryCharging, Double batteryTempC,
                                              Long ramAvailMB, Long ramTotalMB,
                                              Double storageFreeGB, Double storageTotalGB,
                                              Integer wifiRssiDbm, Integer wifiLinkMbps,
                                              Long netDownBps, Long netUpBps,
                                              Boolean tailscale, String thermal, Long uptimeSec) {
        FakePhoneMetricsSource s = new FakePhoneMetricsSource();
        s.hasBattery = true;
        s.batteryPercent = batteryPercent;
        s.batteryCharging = batteryCharging;
        s.batteryTempC = batteryTempC;
        s.hasRam = true;
        s.ramAvailMB = ramAvailMB;
        s.ramTotalMB = ramTotalMB;
        s.hasStorage = true;
        s.storageFreeGB = storageFreeGB;
        s.storageTotalGB = storageTotalGB;
        s.hasWifi = true;
        s.wifiRssiDbm = wifiRssiDbm;
        s.wifiLinkMbps = wifiLinkMbps;
        s.hasNet = true;
        s.netDownBps = netDownBps;
        s.netUpBps = netUpBps;
        s.tailscale = tailscale;
        s.thermal = thermal;
        s.uptimeSec = uptimeSec;
        return s;
    }

    @Override public boolean hasBattery() { return hasBattery; }
    @Override public Integer batteryPercent() { return batteryPercent; }
    @Override public Boolean batteryCharging() { return batteryCharging; }
    @Override public Double batteryTempC() { return batteryTempC; }

    @Override public boolean hasRam() { return hasRam; }
    @Override public Long ramAvailMB() { return ramAvailMB; }
    @Override public Long ramTotalMB() { return ramTotalMB; }

    @Override public boolean hasStorage() { return hasStorage; }
    @Override public Double storageFreeGB() { return storageFreeGB; }
    @Override public Double storageTotalGB() { return storageTotalGB; }

    @Override public boolean hasWifi() { return hasWifi; }
    @Override public Integer wifiRssiDbm() { return wifiRssiDbm; }
    @Override public Integer wifiLinkMbps() { return wifiLinkMbps; }

    @Override public boolean hasNet() { return hasNet; }
    @Override public Long netDownBps() { return netDownBps; }
    @Override public Long netUpBps() { return netUpBps; }

    @Override public Boolean tailscale() { return tailscale; }
    @Override public String thermal() { return thermal; }
    @Override public Long uptimeSec() { return uptimeSec; }
}
