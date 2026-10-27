package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.i18n.L10n;

public class MetricsFormatterTest {

    public static void main(String[] args) {
        L10n.setOverride(L10n.VI); // the assertions below check the Vietnamese UI text
        System.out.println("Running MetricsFormatterTest...");
        testFormatPercent();
        testFormatRam();
        testFormatSpeed();
        testFormatTemp();
        testFormatCpuOrSysTemp();
        testFormatUptime();
        testFormatBattery();
        testEnglishText();
        testFormatWifi();
        testAlertLevels();
        System.out.println("ALL MetricsFormatterTest assertions PASSED!");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    public static void testFormatPercent() {
        assertTrue("12%".equals(MetricsFormatter.formatPercent(12.3)), "12.3 -> 12%");
        assertTrue("0%".equals(MetricsFormatter.formatPercent(0.0)), "0.0 -> 0%");
        assertTrue("100%".equals(MetricsFormatter.formatPercent(100.0)), "100.0 -> 100%");
        assertTrue("—".equals(MetricsFormatter.formatPercent(null)), "null -> —");
        System.out.println("PASS: testFormatPercent");
    }

    public static void testFormatRam() {
        // 8123 MB / 1024 = 7.9326 GB -> 7,9 GB
        // 32768 MB / 1024 = 32.0 GB -> 32,0 GB
        String ramStr = MetricsFormatter.formatRam(8123L, 32768L);
        assertTrue("7,9 / 32,0 GB".equals(ramStr), "RAM format: expected '7,9 / 32,0 GB', got '" + ramStr + "'");

        assertTrue("—".equals(MetricsFormatter.formatRam(null, 32768L)), "null usedMB -> —");
        assertTrue("—".equals(MetricsFormatter.formatRam(8123L, null)), "null totalMB -> —");
        assertTrue("—".equals(MetricsFormatter.formatRam(null, null)), "null both -> —");

        // Phone ram helper
        String phoneRam = MetricsFormatter.formatPhoneRam(2048L, 6144L);
        // (6144 - 2048) / 1024 = 4096 / 1024 = 4.0 GB; total = 6.0 GB
        assertTrue("4,0 / 6,0 GB".equals(phoneRam), "Phone RAM format: " + phoneRam);

        System.out.println("PASS: testFormatRam");
    }

    public static void testFormatSpeed() {
        assertTrue("0 B/s".equals(MetricsFormatter.formatSpeed(0L)), "0 B/s");
        assertTrue("500 B/s".equals(MetricsFormatter.formatSpeed(500L)), "500 B/s");

        // 340 KB/s: 340 * 1024 = 348160 bytes
        assertTrue("340 KB/s".equals(MetricsFormatter.formatSpeed(340L * 1024L)), "340 KB/s");

        // 1.2 MB/s: 1.2 * 1024 * 1024 = 1258291 bytes
        long onePointTwoMB = (long) (1.2 * 1024 * 1024);
        assertTrue("1,2 MB/s".equals(MetricsFormatter.formatSpeed(onePointTwoMB)), "1,2 MB/s");

        assertTrue("—".equals(MetricsFormatter.formatSpeed(null)), "null -> —");

        // Net both
        String both = MetricsFormatter.formatNetBoth(onePointTwoMB, 340L * 1024L);
        assertTrue(both.contains("1,2 MB/s") && both.contains("340 KB/s"), "net both format: " + both);

        System.out.println("PASS: testFormatSpeed");
    }

    public static void testFormatTemp() {
        assertTrue("54 °C".equals(MetricsFormatter.formatTemp(54.0)), "54.0 -> 54 °C");
        assertTrue("54 °C".equals(MetricsFormatter.formatTemp(54.4)), "54.4 -> 54 °C");
        assertTrue("32 °C".equals(MetricsFormatter.formatTemp(31.5)), "31.5 -> 32 °C");
        assertTrue("—".equals(MetricsFormatter.formatTemp(null)), "null -> —");
        System.out.println("PASS: testFormatTemp");
    }

    public static void testFormatCpuOrSysTemp() {
        assertTrue("CPU: 54 °C".equals(MetricsFormatter.formatCpuOrSysTemp(54.0, null)), "cpu only");
        assertTrue("CPU: 54 °C".equals(MetricsFormatter.formatCpuOrSysTemp(54.0, 48.0)), "cpu prioritized over sys");
        assertTrue("Mainboard: 48 °C".equals(MetricsFormatter.formatCpuOrSysTemp(null, 48.0)), "sys when cpu null");
        assertTrue(MetricsFormatter.formatCpuOrSysTemp(null, null) == null, "both null returns null");

        MetricsSnapshot.Pc pcCpu = new MetricsSnapshot.Pc(true, new MetricsSnapshot.Processor(10.0, 54.0, "CPU"), null, null, null, null, null, new MetricsSnapshot.Sys(48.0));
        assertTrue("CPU: 54 °C".equals(MetricsFormatter.formatCpuOrSysTemp(pcCpu)), "pc with cpu and sys");

        MetricsSnapshot.Pc pcSys = new MetricsSnapshot.Pc(true, new MetricsSnapshot.Processor(10.0, null, "CPU"), null, null, null, null, null, new MetricsSnapshot.Sys(48.0));
        assertTrue("Mainboard: 48 °C".equals(MetricsFormatter.formatCpuOrSysTemp(pcSys)), "pc with sys only");

        MetricsSnapshot.Pc pcNone = new MetricsSnapshot.Pc(true, null, null, null, null, null, null, null);
        assertTrue(MetricsFormatter.formatCpuOrSysTemp(pcNone) == null, "pc with neither");
        assertTrue(MetricsFormatter.formatCpuOrSysTemp((MetricsSnapshot.Pc) null) == null, "null pc");

        System.out.println("PASS: testFormatCpuOrSysTemp");
    }
    public static void testFormatUptime() {
        // 2 days 3 hours: 2 * 86400 + 3 * 3600 = 172800 + 10800 = 183600 seconds
        assertTrue("2 ngày 3 giờ".equals(MetricsFormatter.formatUptime(183600L)), "2 ngày 3 giờ");

        // 5 hours 12 minutes: 5 * 3600 + 12 * 60 = 18000 + 720 = 18720 seconds
        assertTrue("5 giờ 12 phút".equals(MetricsFormatter.formatUptime(18720L)), "5 giờ 12 phút");

        // 25 minutes
        assertTrue("25 phút".equals(MetricsFormatter.formatUptime(1500L)), "25 phút");

        assertTrue("—".equals(MetricsFormatter.formatUptime(null)), "null -> —");
        assertTrue("—".equals(MetricsFormatter.formatUptime(-1L)), "negative -> —");

        System.out.println("PASS: testFormatUptime");
    }

    public static void testEnglishText() {
        L10n.setOverride(L10n.EN);
        try {
            assertTrue("2d 3h".equals(MetricsFormatter.formatUptime(183600L)), "2d 3h");
            assertTrue("5h 12m".equals(MetricsFormatter.formatUptime(18720L)), "5h 12m");
            assertTrue("25m".equals(MetricsFormatter.formatUptime(1500L)), "25m");
            assertTrue("100% (charging)".equals(MetricsFormatter.formatBattery(100.0, true)), "100% (charging)");
            assertTrue("resets in 1d 2h".equals(QuotaFormatter.resetText(1000L + 26 * 3600_000L, 1000L)), "resets in 1d 2h");
            assertTrue(DiagnosticsFormatter.format(null).startsWith("No diagnostics yet"), "English diagnostics");
        } finally {
            L10n.setOverride(L10n.VI);
        }
        System.out.println("PASS: testEnglishText");
    }

    public static void testFormatBattery() {
        assertTrue("100% (đang sạc)".equals(MetricsFormatter.formatBattery(100.0, true)), "100% (đang sạc)");
        assertTrue("100%".equals(MetricsFormatter.formatBattery(100.0, false)), "100% not charging");
        assertTrue("100%".equals(MetricsFormatter.formatBattery(100.0, null)), "100% null charging");
        assertTrue("—".equals(MetricsFormatter.formatBattery(null, true)), "null percent -> —");
        System.out.println("PASS: testFormatBattery");
    }

    public static void testFormatWifi() {
        assertTrue("-55 dBm · 866 Mbps".equals(MetricsFormatter.formatWifi(-55, 866)), "-55 dBm · 866 Mbps");
        assertTrue("-55 dBm".equals(MetricsFormatter.formatWifi(-55, null)), "-55 dBm");
        assertTrue("866 Mbps".equals(MetricsFormatter.formatWifi(null, 866)), "866 Mbps");
        assertTrue("—".equals(MetricsFormatter.formatWifi(null, null)), "null -> —");
        System.out.println("PASS: testFormatWifi");
    }

    public static void testAlertLevels() {
        // <80: NORMAL
        assertTrue(MetricsFormatter.getAlertLevel(0.0) == MetricsFormatter.AlertLevel.NORMAL, "0 -> NORMAL");
        assertTrue(MetricsFormatter.getAlertLevel(50.0) == MetricsFormatter.AlertLevel.NORMAL, "50 -> NORMAL");
        assertTrue(MetricsFormatter.getAlertLevel(79.9) == MetricsFormatter.AlertLevel.NORMAL, "79.9 -> NORMAL");
        assertTrue(MetricsFormatter.getAlertLevel(null) == MetricsFormatter.AlertLevel.NORMAL, "null -> NORMAL");

        // 80..95: WARNING
        assertTrue(MetricsFormatter.getAlertLevel(80.0) == MetricsFormatter.AlertLevel.WARNING, "80.0 boundary -> WARNING");
        assertTrue(MetricsFormatter.getAlertLevel(85.0) == MetricsFormatter.AlertLevel.WARNING, "85.0 -> WARNING");
        assertTrue(MetricsFormatter.getAlertLevel(95.0) == MetricsFormatter.AlertLevel.WARNING, "95.0 boundary -> WARNING");

        // >95: CRITICAL
        assertTrue(MetricsFormatter.getAlertLevel(95.01) == MetricsFormatter.AlertLevel.CRITICAL, "95.01 -> CRITICAL");
        assertTrue(MetricsFormatter.getAlertLevel(95.1) == MetricsFormatter.AlertLevel.CRITICAL, "95.1 -> CRITICAL");
        assertTrue(MetricsFormatter.getAlertLevel(100.0) == MetricsFormatter.AlertLevel.CRITICAL, "100.0 -> CRITICAL");

        System.out.println("PASS: testAlertLevels");
    }
}
