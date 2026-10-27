package vn.vuumi.pcdash.remote;

public class PowerActionWatchTest {

    private static void assertEquals(Object actual, Object expected, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Assertion failed for " + message + ": expected <" + expected + "> but got <" + actual + ">");
    }

    private static void testWakeWaitsForThePcToBeOn() {
        PowerActionWatch w = PowerActionWatch.start("wake", 1_000L);
        assertEquals(w.check(false, 5_000L), PowerActionWatch.Progress.WAITING, "Still off");
        assertEquals(w.check(null, 6_000L), PowerActionWatch.Progress.WAITING, "No answer is not an outcome");
        assertEquals(w.check(true, 30_000L), PowerActionWatch.Progress.REACHED, "On");
        System.out.println("PASS: testWakeWaitsForThePcToBeOn");
    }

    private static void testOffCommandsWaitForThePcToBeOff() {
        for (String action : new String[]{"sleep", "hibernate", "shutdown"}) {
            PowerActionWatch w = PowerActionWatch.start(action, 0L);
            assertEquals(w.check(true, 2_000L), PowerActionWatch.Progress.WAITING, action + ": still on");
            assertEquals(w.check(false, 9_000L), PowerActionWatch.Progress.REACHED, action + ": off");
        }
        System.out.println("PASS: testOffCommandsWaitForThePcToBeOff");
    }

    private static void testGivesUp() {
        PowerActionWatch w = PowerActionWatch.start("sleep", 0L);
        assertEquals(w.check(true, PowerActionWatch.OFF_TIMEOUT_MS - 1), PowerActionWatch.Progress.WAITING, "Just before");
        assertEquals(w.check(true, PowerActionWatch.OFF_TIMEOUT_MS), PowerActionWatch.Progress.TIMED_OUT, "Deadline");
        assertEquals(w.check(false, PowerActionWatch.OFF_TIMEOUT_MS + 5), PowerActionWatch.Progress.REACHED, "Late success still counts");
        System.out.println("PASS: testGivesUp");
    }

    private static void testNothingToWaitFor() {
        assertEquals(PowerActionWatch.start("abort", 0L), null, "Cancel shutdown has no PC state to wait for");
        assertEquals(PowerActionWatch.start(null, 0L), null, "No action");
        System.out.println("PASS: testNothingToWaitFor");
    }

    public static void main(String[] args) {
        System.out.println("Running PowerActionWatchTest...");
        testWakeWaitsForThePcToBeOn();
        testOffCommandsWaitForThePcToBeOff();
        testGivesUp();
        testNothingToWaitFor();
        System.out.println("ALL PowerActionWatchTest PASSED");
    }
}
