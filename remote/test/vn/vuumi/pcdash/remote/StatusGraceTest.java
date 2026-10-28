package vn.vuumi.pcdash.remote;

public class StatusGraceTest {

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAILED: " + msg);
    }

    public static void testFirstFailuresAfterTunnelUpAreConnecting() {
        StatusGrace g = new StatusGrace();
        g.tunnelUp(1_000);
        assertTrue(g.stillConnecting(1, 1_300), "first failure right after the tunnel came up is connecting");
        assertTrue(g.stillConnecting(2, 3_500), "second failure inside the window is connecting");
        assertTrue(g.stillConnecting(StatusGrace.MAX_FAILURES, 5_500), "up to MAX_FAILURES inside the window");
        assertTrue(!g.stillConnecting(StatusGrace.MAX_FAILURES + 1, 6_500), "more failures in a row are an error");
    }

    public static void testWindowExpires() {
        StatusGrace g = new StatusGrace();
        g.tunnelUp(0);
        assertTrue(!g.stillConnecting(1, StatusGrace.WINDOW_MS), "a failure after the window is an error");
    }

    public static void testAfterAnAnswerFailuresAreErrors() {
        StatusGrace g = new StatusGrace();
        g.tunnelUp(0);
        g.answered();
        assertTrue(!g.stillConnecting(1, 500), "once a status arrived, a failure is an error");
    }

    public static void testTunnelDownStartsANewWindow() {
        StatusGrace g = new StatusGrace();
        g.tunnelUp(0);
        g.answered();
        g.tunnelDown();
        assertTrue(!g.stillConnecting(1, 100), "no window while the tunnel is down");
        g.tunnelUp(60_000);
        g.tunnelUp(61_000);
        assertTrue(g.stillConnecting(1, 61_500), "the window starts when the tunnel is up again, not on later calls");
        assertTrue(!g.stillConnecting(1, 60_000 + StatusGrace.WINDOW_MS), "and ends WINDOW_MS after that");
    }

    public static void testNoFailureIsNotConnecting() {
        StatusGrace g = new StatusGrace();
        g.tunnelUp(0);
        assertTrue(!g.stillConnecting(0, 100), "zero failures is not a failed status");
    }

    public static void main(String[] args) {
        testFirstFailuresAfterTunnelUpAreConnecting();
        testWindowExpires();
        testAfterAnAnswerFailuresAreErrors();
        testTunnelDownStartsANewWindow();
        testNoFailureIsNotConnecting();
        System.out.println("ALL StatusGraceTest PASSED");
    }
}
