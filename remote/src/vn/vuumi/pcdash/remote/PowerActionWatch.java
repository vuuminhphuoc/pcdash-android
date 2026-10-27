package vn.vuumi.pcdash.remote;

/**
 * After the home phone accepted a power command: waits until the PC really is on (Wake) or off (Sleep,
 * Hibernate, Shut down), as seen in the status the app polls, or gives up after a while. A command
 * with nothing to wait for (Cancel shutdown) has no watch.
 */
final class PowerActionWatch {
    static final long WAKE_TIMEOUT_MS = 120_000L;
    static final long OFF_TIMEOUT_MS = 90_000L;

    enum Progress {
        WAITING,
        REACHED,
        TIMED_OUT
    }

    final String action;
    final boolean expectOnline;
    private final long deadlineMs;

    private PowerActionWatch(String action, boolean expectOnline, long deadlineMs) {
        this.action = action;
        this.expectOnline = expectOnline;
        this.deadlineMs = deadlineMs;
    }

    /** A watch for the command, or null when there is no PC state to wait for. */
    static PowerActionWatch start(String action, long nowMs) {
        if ("wake".equals(action)) {
            return new PowerActionWatch(action, true, nowMs + WAKE_TIMEOUT_MS);
        }
        if ("sleep".equals(action) || "hibernate".equals(action) || "shutdown".equals(action)) {
            return new PowerActionWatch(action, false, nowMs + OFF_TIMEOUT_MS);
        }
        return null;
    }

    /**
     * @param pcOnline the PC's state from the latest status, or null when the home phone did not answer
     */
    Progress check(Boolean pcOnline, long nowMs) {
        if (pcOnline != null && pcOnline == expectOnline) {
            return Progress.REACHED;
        }
        return nowMs >= deadlineMs ? Progress.TIMED_OUT : Progress.WAITING;
    }
}
