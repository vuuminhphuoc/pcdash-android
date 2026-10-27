package vn.vuumi.pcdash;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import vn.vuumi.pcdash.net.PcnetState;

public class PhoneMetricsCollector implements PhoneMetricsSource {
    private final Context context;
    private long lastRx = -1;
    private long lastTx = -1;
    private long lastTimeMs = -1;

    private boolean hasBattery;
    private Integer batteryPercent;
    private Boolean batteryCharging;
    private Double batteryTempC;

    private boolean hasRam;
    private Long ramAvailMB;
    private Long ramTotalMB;

    private boolean hasStorage;
    private Double storageFreeGB;
    private Double storageTotalGB;

    private boolean hasWifi;
    private Integer wifiRssiDbm;
    private Integer wifiLinkMbps;

    private boolean hasNet;
    private Long netDownBps;
    private Long netUpBps;

    private Boolean tailscale;
    private String thermal;
    private Long uptimeSec;

    private final HostTailnet tailnet;

    public PhoneMetricsCollector(Context context) {
        this(context, null);
    }

    public PhoneMetricsCollector(Context context, HostTailnet tailnet) {
        this.context = context != null ? context.getApplicationContext() : null;
        this.tailnet = tailnet;
    }

    @Override
    public synchronized void refresh() {
        if (context == null) {
            hasBattery = false;
            hasRam = false;
            hasStorage = false;
            hasWifi = false;
            hasNet = false;
            tailscale = null;
            thermal = null;
            uptimeSec = null;
            return;
        }

        // 1. Battery
        hasBattery = false;
        batteryPercent = null;
        batteryCharging = null;
        batteryTempC = null;
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = context.registerReceiver(null, filter);
            if (batteryStatus != null) {
                hasBattery = true;
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                batteryPercent = sanitizeBatteryPercent(level, scale);

                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                batteryCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                   status == BatteryManager.BATTERY_STATUS_FULL ||
                                   plugged > 0);

                int tempRaw = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                batteryTempC = sanitizeBatteryTempC(tempRaw);
            }
        } catch (Exception ignored) {}

        // 2. RAM
        hasRam = false;
        ramAvailMB = null;
        ramTotalMB = null;
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                hasRam = true;
                ramAvailMB = mi.availMem / (1024L * 1024L);
                ramTotalMB = mi.totalMem / (1024L * 1024L);
            }
        } catch (Exception ignored) {}

        // 3. Storage
        hasStorage = false;
        storageFreeGB = null;
        storageTotalGB = null;
        try {
            File dataDir = Environment.getDataDirectory();
            if (dataDir != null) {
                StatFs stat = new StatFs(dataDir.getAbsolutePath());
                hasStorage = true;
                storageFreeGB = round1(stat.getAvailableBytes() / (1024.0 * 1024.0 * 1024.0));
                storageTotalGB = round1(stat.getTotalBytes() / (1024.0 * 1024.0 * 1024.0));
            }
        } catch (Exception ignored) {}

        // 4. Wi-Fi
        hasWifi = false;
        wifiRssiDbm = null;
        wifiLinkMbps = null;
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null && info.getNetworkId() != -1) {
                    hasWifi = true;
                    int rssi = info.getRssi();
                    wifiRssiDbm = sanitizeWifiRssi(rssi);
                    int linkSpeed = info.getLinkSpeed();
                    wifiLinkMbps = sanitizeWifiLinkSpeed(linkSpeed);
                }
            }
        } catch (Exception ignored) {}

        // 5. TrafficStats
        hasNet = false;
        netDownBps = null;
        netUpBps = null;
        try {
            long currentRx = TrafficStats.getTotalRxBytes();
            long currentTx = TrafficStats.getTotalTxBytes();
            long currentTimeMs = SystemClock.elapsedRealtime();

            if (currentRx != TrafficStats.UNSUPPORTED && currentTx != TrafficStats.UNSUPPORTED && currentRx >= 0 && currentTx >= 0) {
                hasNet = true;
                if (lastRx >= 0 && lastTx >= 0 && lastTimeMs > 0 && currentTimeMs > lastTimeMs) {
                    long deltaMs = currentTimeMs - lastTimeMs;
                    netDownBps = computeNetSpeed(currentRx, lastRx, deltaMs);
                    netUpBps = computeNetSpeed(currentTx, lastTx, deltaMs);
                }
                lastRx = currentRx;
                lastTx = currentTx;
                lastTimeMs = currentTimeMs;
            } else {
                hasNet = false;
                netDownBps = null;
                netUpBps = null;
                lastRx = -1;
                lastTx = -1;
                lastTimeMs = -1;
            }
        } catch (Exception ignored) {}

        // 6. Tailscale
        HostTailnet ht = this.tailnet != null ? this.tailnet : lookupHostTailnet();
        if (ht != null) {
            PcnetState s = ht.state();
            String ip = ht.tailnetIp();
            tailscale = (s != null && s.isRunning() && ip != null && !ip.trim().isEmpty());
        } else {
            tailscale = false;
        }

        // 7. Thermal
        thermal = null;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    int status = pm.getCurrentThermalStatus();
                    switch (status) {
                        case PowerManager.THERMAL_STATUS_NONE: thermal = "NONE"; break;
                        case PowerManager.THERMAL_STATUS_LIGHT: thermal = "LIGHT"; break;
                        case PowerManager.THERMAL_STATUS_MODERATE: thermal = "MODERATE"; break;
                        case PowerManager.THERMAL_STATUS_SEVERE: thermal = "SEVERE"; break;
                        case PowerManager.THERMAL_STATUS_CRITICAL: thermal = "CRITICAL"; break;
                        case PowerManager.THERMAL_STATUS_EMERGENCY: thermal = "EMERGENCY"; break;
                        case PowerManager.THERMAL_STATUS_SHUTDOWN: thermal = "SHUTDOWN"; break;
                        default: thermal = "UNKNOWN"; break;
                    }
                }
            } catch (Exception ignored) {}
        }

        // 8. Uptime
        uptimeSec = SystemClock.elapsedRealtime() / 1000L;
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

    private static HostTailnet lookupHostTailnet() {
        try {
            Class<?> ds = Class.forName("vn.vuumi.pcdash.DashService");
            java.lang.reflect.Method m = ds.getMethod("getHostTailnet");
            Object res = m.invoke(null);
            if (res instanceof HostTailnet) {
                return (HostTailnet) res;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static boolean isTailscaleActive() {
        HostTailnet ht = lookupHostTailnet();
        if (ht != null) {
            PcnetState s = ht.state();
            String ip = ht.tailnetIp();
            return s != null && s.isRunning() && ip != null && !ip.trim().isEmpty();
        }
        return false;
    }

    public static Integer sanitizeBatteryPercent(int level, int scale) {
        if (level < 0 || scale <= 0) return null;
        int pct = (int) Math.round((level * 100.0) / scale);
        return (pct >= 0 && pct <= 100) ? pct : null;
    }

    public static Double sanitizeBatteryTempC(int tempRaw) {
        if (tempRaw < -400 || tempRaw > 1500) {
            return null;
        }
        return round1(tempRaw / 10.0);
    }

    public static Integer sanitizeWifiRssi(int rssi) {
        if (rssi <= -127 || rssi >= 0 || rssi == Integer.MIN_VALUE) {
            return null;
        }
        return rssi;
    }

    public static Integer sanitizeWifiLinkSpeed(int linkSpeed) {
        if (linkSpeed <= 0) {
            return null;
        }
        return linkSpeed;
    }

    public static Long computeNetSpeed(long currentBytes, long lastBytes, long deltaMs) {
        if (currentBytes < 0 || lastBytes < 0 || deltaMs < 100) return null;
        long delta = currentBytes - lastBytes;
        if (delta < 0) return null;
        double deltaSec = deltaMs / 1000.0;
        long speed = Math.round(delta / deltaSec);
        return speed >= 0 ? speed : null;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
