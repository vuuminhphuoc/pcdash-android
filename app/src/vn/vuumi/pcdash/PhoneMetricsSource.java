package vn.vuumi.pcdash;

public interface PhoneMetricsSource {
    default void refresh() {}

    boolean hasBattery();
    Integer batteryPercent();
    Boolean batteryCharging();
    Double batteryTempC();

    boolean hasRam();
    Long ramAvailMB();
    Long ramTotalMB();

    boolean hasStorage();
    Double storageFreeGB();
    Double storageTotalGB();

    boolean hasWifi();
    Integer wifiRssiDbm();
    Integer wifiLinkMbps();

    boolean hasNet();
    Long netDownBps();
    Long netUpBps();

    Boolean tailscale();
    String thermal();
    Long uptimeSec();
}
