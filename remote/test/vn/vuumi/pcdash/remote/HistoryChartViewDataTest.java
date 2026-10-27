package vn.vuumi.pcdash.remote;

public class HistoryChartViewDataTest {
    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("Assertion failed: " + message);
        }
    }

    public static void main(String[] args) {
        System.out.println("Running HistoryChartViewDataTest...");
        assertTrue(HistoryChartView.matchesData(
                new long[]{1000L, 2000L}, new double[]{1.0, -1.0}, null, new int[]{1, 0},
                new long[]{1000L, 2000L}, new Double[]{1.0, null}, null, new int[]{1, 0}),
                "identical nullable input must not redraw");
        assertTrue(!HistoryChartView.matchesData(
                new long[]{1000L}, new double[]{1.0}, null, new int[]{1},
                new long[]{1000L}, new Double[]{2.0}, null, new int[]{1}),
                "changed series value must redraw");
        assertTrue(!HistoryChartView.matchesData(
                new long[]{1000L}, new double[]{1.0}, null, new int[]{1},
                new long[]{1001L}, new Double[]{1.0}, null, new int[]{1}),
                "changed timestamp must redraw");
        assertTrue("512B".equals(HistoryChartView.formatCompactSpeed(512.0)), "512B");
        assertTrue("500K".equals(HistoryChartView.formatCompactSpeed(500.0 * 1024.0)), "500K");
        assertTrue("1.5M".equals(HistoryChartView.formatCompactSpeed(1.5 * 1024.0 * 1024.0)), "1.5M");
        System.out.println("ALL HistoryChartViewDataTest assertions PASSED!");
    }
}
