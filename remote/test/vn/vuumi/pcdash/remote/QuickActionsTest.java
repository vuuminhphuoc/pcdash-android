package vn.vuumi.pcdash.remote;

public class QuickActionsTest {

    private static void assertEquals(Object actual, Object expected, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Assertion failed for " + message + ": expected <" + expected + "> but got <" + actual + ">");
    }

    private static void testOnlyWakeAndSleepAreAccepted() {
        assertEquals(QuickActions.commandFor(QuickActions.ACTION_WAKE), "wake", "Wake shortcut");
        assertEquals(QuickActions.commandFor(QuickActions.ACTION_SLEEP), "sleep", "Sleep shortcut");
        assertEquals(QuickActions.commandFor("vn.vuumi.pcdash.remote.action.SHUTDOWN"), null, "Shutdown is never a quick action");
        assertEquals(QuickActions.commandFor(null), null, "No action");
        System.out.println("PASS: testOnlyWakeAndSleepAreAccepted");
    }

    private static void testTakeRunsOnceWhileFresh() {
        QuickActions.request(QuickActions.ACTION_WAKE, 1000L);
        assertEquals(QuickActions.take(1000L + QuickActions.MAX_AGE_MS), "wake", "Fresh request");
        assertEquals(QuickActions.take(1000L + QuickActions.MAX_AGE_MS), null, "Taken only once");
        System.out.println("PASS: testTakeRunsOnceWhileFresh");
    }

    private static void testStaleOrClockSkewedRequestIsDropped() {
        QuickActions.request(QuickActions.ACTION_SLEEP, 1000L);
        assertEquals(QuickActions.take(1001L + QuickActions.MAX_AGE_MS), null, "Stale request");
        QuickActions.request(QuickActions.ACTION_SLEEP, 5000L);
        assertEquals(QuickActions.take(4000L), null, "Clock went backwards");
        assertEquals(QuickActions.take(5000L), null, "Dropped request does not come back");
        System.out.println("PASS: testStaleOrClockSkewedRequestIsDropped");
    }

    public static void main(String[] args) {
        System.out.println("Running QuickActionsTest...");
        testOnlyWakeAndSleepAreAccepted();
        testTakeRunsOnceWhileFresh();
        testStaleOrClockSkewedRequestIsDropped();
        System.out.println("ALL QuickActionsTest PASSED");
    }
}
