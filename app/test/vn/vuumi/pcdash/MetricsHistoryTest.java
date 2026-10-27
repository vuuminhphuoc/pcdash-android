package vn.vuumi.pcdash;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class MetricsHistoryTest {

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAILED: " + msg);
    }

    private static void assertEquals(Object actual, Object expected, String msg) {
        if (actual == null && expected == null) return;
        if (actual == null || !actual.equals(expected)) {
            throw new AssertionError("FAILED: " + msg + " (expected: " + expected + ", got: " + actual + ")");
        }
    }

    public static void testRingBuffer720() throws IOException {
        File f = File.createTempFile("metrics-ring-", ".json");
        f.deleteOnExit();
        try {
            MetricsHistory history = new MetricsHistory(f);
            long baseTs = 1000000000L;
            for (int i = 1; i <= 800; i++) {
                history.add(new MetricsHistory.Sample(
                        baseTs + i * 30000L,
                        (double) (i % 100),
                        (double) (i % 50),
                        50.0,
                        1024L,
                        512L,
                        45.0,
                        55.0,
                        true
                ));
            }
            List<MetricsHistory.Sample> list = history.getSamples();
            assertEquals(list.size(), 720, "Ring buffer should cap at 720 samples");
            // Oldest kept sample: index 81 (800 - 720 + 1)
            assertEquals(list.get(0).ts, baseTs + 81 * 30000L, "Oldest kept timestamp");
            // Newest sample: index 800
            assertEquals(list.get(719).ts, baseTs + 800 * 30000L, "Newest timestamp");
        } finally {
            f.delete();
        }
    }

    public static void testSaveAndLoad() throws IOException {
        File f = File.createTempFile("metrics-save-", ".json");
        f.deleteOnExit();
        try {
            MetricsHistory history = new MetricsHistory(f);
            long now = System.currentTimeMillis();
            for (int i = 0; i < 20; i++) {
                history.add(new MetricsHistory.Sample(
                        now - (20 - i) * 30000L,
                        12.5 + i,
                        30.0 + i,
                        55.5,
                        100000L + i,
                        20000L + i,
                        48.0,
                        60.0,
                        true
                ));
            }
            history.save();

            MetricsHistory reloaded = new MetricsHistory(f);
            List<MetricsHistory.Sample> list = reloaded.getSamples();
            assertEquals(list.size(), 20, "Should reload all 20 recent samples");
            assertEquals(list.get(0).cpu, 12.5, "First sample cpu matches");
            assertEquals(list.get(19).cpu, 31.5, "Last sample cpu matches");
            assertEquals(list.get(19).down, 100019L, "Last sample down bps matches");
        } finally {
            f.delete();
        }
    }

    public static void testDiscardOldSamples() throws IOException {
        File f = File.createTempFile("metrics-discard-", ".json");
        f.deleteOnExit();
        try {
            MetricsHistory history = new MetricsHistory(f);
            long now = System.currentTimeMillis();
            long sevenHoursAgo = now - 7 * 3600 * 1000L;
            long twoHoursAgo = now - 2 * 3600 * 1000L;

            // Old sample (> 6 hours)
            history.add(new MetricsHistory.Sample(sevenHoursAgo, 10.0, 10.0, 10.0, 100L, 100L, 40.0, 40.0, true));
            // Recent sample (< 6 hours)
            history.add(new MetricsHistory.Sample(twoHoursAgo, 20.0, 20.0, 20.0, 200L, 200L, 50.0, 50.0, true));

            history.save();

            // When reloaded, samples older than 6 hours are discarded
            MetricsHistory reloaded = new MetricsHistory(f);
            List<MetricsHistory.Sample> list = reloaded.getSamples();
            assertEquals(list.size(), 1, "Only samples <= 6h retained after load");
            assertEquals(list.get(0).ts, twoHoursAgo, "Retained sample is the 2-hour-old one");
        } finally {
            f.delete();
        }
    }

    public static void testNullHandlingAndJsonOutput() {
        MetricsHistory history = new MetricsHistory(null);
        long now = System.currentTimeMillis();
        // Sample with missing/null fields (e.g. PC offline)
        history.add(new MetricsHistory.Sample(
                now - 30000L,
                null, null, null, null, null, null, null, false
        ));
        // Sample with partial fields (e.g. no discrete GPU)
        history.add(new MetricsHistory.Sample(
                now,
                15.0, null, 45.0, 5000L, 1000L, 52.0, null, true
        ));

        String json = history.toHistoryJson(now, 60);
        assertTrue(json.contains("\"intervalMs\":30000"), "Must contain intervalMs: 30000");
        assertTrue(json.contains("\"cpu\":[null,15"), "cpu array has null then number");
        assertTrue(json.contains("\"gpu\":[null,null]"), "gpu array has both nulls");
        assertTrue(json.contains("\"online\":[0,1]"), "online array has 0 and 1");

        // Verify array lengths match
        int countTs = countElements(extractArray(json, "ts"));
        int countCpu = countElements(extractArray(json, "cpu"));
        int countGpu = countElements(extractArray(json, "gpu"));
        int countRam = countElements(extractArray(json, "ram"));
        int countDown = countElements(extractArray(json, "down"));
        int countUp = countElements(extractArray(json, "up"));
        int countCpuTemp = countElements(extractArray(json, "cpuTemp"));
        int countGpuTemp = countElements(extractArray(json, "gpuTemp"));
        int countOnline = countElements(extractArray(json, "online"));

        assertEquals(countTs, 2, "2 elements in ts");
        assertEquals(countCpu, 2, "2 elements in cpu");
        assertEquals(countGpu, 2, "2 elements in gpu");
        assertEquals(countRam, 2, "2 elements in ram");
        assertEquals(countDown, 2, "2 elements in down");
        assertEquals(countUp, 2, "2 elements in up");
        assertEquals(countCpuTemp, 2, "2 elements in cpuTemp");
        assertEquals(countGpuTemp, 2, "2 elements in gpuTemp");
        assertEquals(countOnline, 2, "2 elements in online");
    }

    public static void testUnexpectedMinutes() {
        MetricsHistory history = new MetricsHistory(null);
        long now = System.currentTimeMillis();
        history.add(new MetricsHistory.Sample(now - 120000L, 10.0, 10.0, 50.0, 100L, 100L, 40.0, 40.0, true));
        history.add(new MetricsHistory.Sample(now - 60000L, 20.0, 20.0, 50.0, 200L, 200L, 42.0, 42.0, true));
        history.add(new MetricsHistory.Sample(now, 30.0, 30.0, 50.0, 300L, 300L, 45.0, 45.0, true));

        // Negative minutes -> default 60
        String jNeg = history.toHistoryJson(now, -10);
        assertEquals(countElements(extractArray(jNeg, "ts")), 3, "Negative minutes should default to 60");

        // Zero minutes -> default 60
        String jZero = history.toHistoryJson(now, 0);
        assertEquals(countElements(extractArray(jZero, "ts")), 3, "Zero minutes should default to 60");

        // > 360 -> capped to 360
        String jBig = history.toHistoryJson(now, 9999);
        assertEquals(countElements(extractArray(jBig, "ts")), 3, "Minutes > 360 should cap to 360");
    }

    public static void testParseAgentMetrics() {
        long ts = 1792480000000L;
        String rawAgentJson = "{\"cpu\":{\"percent\":12.3,\"tempC\":54.0},"
                + "\"ram\":{\"percent\":24.8},"
                + "\"gpu\":{\"percent\":5.0,\"tempC\":40.0},"
                + "\"net\":{\"downBps\":123456,\"upBps\":2345},"
                + "\"online\":true}";

        MetricsHistory.Sample s = MetricsHistory.parseAgentMetrics(ts, rawAgentJson);
        assertEquals(s.ts, ts, "ts matches");
        assertEquals(s.online, true, "online is true");
        assertEquals(s.cpu, 12.3, "cpu matches");
        assertEquals(s.cpuTemp, 54.0, "cpuTemp matches");
        assertEquals(s.ram, 24.8, "ram matches");
        assertEquals(s.gpu, 5.0, "gpu matches");
        assertEquals(s.gpuTemp, 40.0, "gpuTemp matches");
        assertEquals(s.down, 123456L, "down bps matches");
        assertEquals(s.up, 2345L, "up bps matches");

        // Offline / null
        MetricsHistory.Sample off = MetricsHistory.parseAgentMetrics(ts, null);
        assertEquals(off.online, false, "offline online is false");
        assertEquals(off.cpu, null, "offline cpu is null");
        assertEquals(off.gpu, null, "offline gpu is null");
    }

    private static String extractArray(String json, String key) {
        String marker = "\"" + key + "\":[";
        int idx = json.indexOf(marker);
        if (idx < 0) return "";
        int start = idx + marker.length();
        int end = json.indexOf(']', start);
        if (end < 0) return "";
        return json.substring(start, end).trim();
    }

    private static int countElements(String arrayContent) {
        if (arrayContent.isEmpty()) return 0;
        String[] parts = arrayContent.split(",");
        return parts.length;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Running MetricsHistoryTest...");
        testRingBuffer720();
        System.out.println("  PASS: testRingBuffer720");
        testSaveAndLoad();
        System.out.println("  PASS: testSaveAndLoad");
        testDiscardOldSamples();
        System.out.println("  PASS: testDiscardOldSamples");
        testNullHandlingAndJsonOutput();
        System.out.println("  PASS: testNullHandlingAndJsonOutput");
        testUnexpectedMinutes();
        System.out.println("  PASS: testUnexpectedMinutes");
        testParseAgentMetrics();
        System.out.println("  PASS: testParseAgentMetrics");
        System.out.println("ALL MetricsHistoryTest PASSED");
    }
}
