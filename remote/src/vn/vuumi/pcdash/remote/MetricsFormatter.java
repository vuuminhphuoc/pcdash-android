package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.i18n.L10n;
import java.util.Locale;

public final class MetricsFormatter {

    public static final String DASH = "—";

    public enum AlertLevel {
        NORMAL,  // < 80
        WARNING, // 80..95
        CRITICAL // > 95
    }

    private MetricsFormatter() {}

    public static AlertLevel getAlertLevel(Double percent) {
        if (percent == null || percent < 80.0) {
            return AlertLevel.NORMAL;
        }
        if (percent <= 95.0) {
            return AlertLevel.WARNING;
        }
        return AlertLevel.CRITICAL;
    }

    public static String formatPercent(Double percent) {
        if (percent == null) {
            return DASH;
        }
        return Math.round(percent) + "%";
    }

    public static String formatRam(Long usedMB, Long totalMB) {
        if (usedMB == null || totalMB == null) {
            return DASH;
        }
        double usedGB = usedMB / 1024.0;
        double totalGB = totalMB / 1024.0;
        return String.format(Locale.US, "%.1f / %.1f GB", usedGB, totalGB).replace('.', ',');
    }

    public static String formatPhoneRam(Long availMB, Long totalMB) {
        if (availMB == null || totalMB == null) {
            return DASH;
        }
        long usedMB = totalMB - availMB;
        return formatRam(usedMB, totalMB);
    }

    public static String formatStorage(Double freeGB, Double totalGB) {
        if (freeGB == null || totalGB == null) {
            return DASH;
        }
        double usedGB = totalGB - freeGB;
        return String.format(Locale.US, "%.1f / %.1f GB", usedGB, totalGB).replace('.', ',');
    }

    public static String formatDisk(Double usedGB, Double totalGB) {
        if (usedGB == null || totalGB == null) {
            return DASH;
        }
        return String.format(Locale.US, "%.1f / %.1f GB", usedGB, totalGB).replace('.', ',');
    }

    public static String formatSpeed(Long bytesPerSec) {
        if (bytesPerSec == null) {
            return DASH;
        }
        if (bytesPerSec < 0) {
            return "0 B/s";
        }
        if (bytesPerSec < 1024) {
            return bytesPerSec + " B/s";
        }
        if (bytesPerSec < 1024 * 1024) {
            long kb = Math.round(bytesPerSec / 1024.0);
            return kb + " KB/s";
        }
        if (bytesPerSec < 1024L * 1024 * 1024) {
            double mb = bytesPerSec / (1024.0 * 1024.0);
            return String.format(Locale.US, "%.1f MB/s", mb).replace('.', ',');
        }
        double gb = bytesPerSec / (1024.0 * 1024.0 * 1024.0);
        return String.format(Locale.US, "%.1f GB/s", gb).replace('.', ',');
    }

    public static String formatNetBoth(Long downBps, Long upBps) {
        if (downBps == null && upBps == null) {
            return DASH;
        }
        return "↓ " + formatSpeed(downBps) + "  ·  ↑ " + formatSpeed(upBps);
    }

    public static String formatTemp(Double tempC) {
        if (tempC == null) {
            return DASH;
        }
        return Math.round(tempC) + " °C";
    }

    public static String formatCpuOrSysTemp(Double cpuTempC, Double sysTempC) {
        if (cpuTempC != null) {
            return "CPU: " + formatTemp(cpuTempC);
        }
        if (sysTempC != null) {
            return "Mainboard: " + formatTemp(sysTempC);
        }
        return null;
    }

    public static String formatCpuOrSysTemp(MetricsSnapshot.Pc pc) {
        if (pc == null) {
            return null;
        }
        Double cpuTemp = (pc.cpu != null) ? pc.cpu.tempC : null;
        Double sysTemp = (pc.sys != null) ? pc.sys.tempC : null;
        return formatCpuOrSysTemp(cpuTemp, sysTemp);
    }

    public static String formatUptime(Long uptimeSec) {
        if (uptimeSec == null || uptimeSec < 0) {
            return DASH;
        }
        long days = uptimeSec / 86400;
        long rem = uptimeSec % 86400;
        long hours = rem / 3600;
        long minutes = (rem % 3600) / 60;

        if (days > 0) {
            if (hours > 0) {
                return days + L10n.t("d ", " ngày ") + hours + L10n.t("h", " giờ");
            } else {
                return days + L10n.t("d", " ngày");
            }
        } else if (hours > 0) {
            if (minutes > 0) {
                return hours + L10n.t("h ", " giờ ") + minutes + L10n.t("m", " phút");
            } else {
                return hours + L10n.t("h", " giờ");
            }
        } else {
            return minutes + L10n.t("m", " phút");
        }
    }

    public static String formatBattery(Double percent, Boolean charging) {
        if (percent == null) {
            return DASH;
        }
        String pctStr = formatPercent(percent);
        if (charging != null && charging) {
            return pctStr + L10n.t(" (charging)", " (đang sạc)");
        }
        return pctStr;
    }

    public static String formatWifi(Integer rssiDbm, Integer linkMbps) {
        if (rssiDbm == null && linkMbps == null) {
            return DASH;
        }
        if (rssiDbm != null && linkMbps != null) {
            return rssiDbm + " dBm · " + linkMbps + " Mbps";
        }
        if (rssiDbm != null) {
            return rssiDbm + " dBm";
        }
        return linkMbps + " Mbps";
    }
}
