package vn.vuumi.pcdash.remote;

/**
 * Right after this phone's tailnet comes up, the first status request often fails while the tunnel to
 * the home phone is still being set up ("Connection reset"). Until one status has arrived on the new
 * tunnel, a couple of early failures read as "connecting", not as an error.
 */
public final class StatusGrace {
    /** How long after the tunnel came up an early failure still counts as connecting. */
    public static final long WINDOW_MS = 12_000L;
    /** How many failures in a row may count as connecting (the window usually ends first). */
    public static final int MAX_FAILURES = 6;

    private long upAtMs = -1;
    private boolean answered;

    /** The tailnet is not up (starting, restarting or stopped): the next time it is up starts a new window. */
    public void tunnelDown() {
        upAtMs = -1;
        answered = false;
    }

    /** A status request went out on a running tailnet at {@code nowMs}. */
    public void tunnelUp(long nowMs) {
        if (upAtMs < 0) {
            upAtMs = nowMs;
        }
    }

    /** A status arrived: from now on failures are real errors. */
    public void answered() {
        answered = true;
    }

    /** Whether a failed status request should show as "connecting" rather than as an error. */
    public boolean stillConnecting(int failuresInARow, long nowMs) {
        return !answered && upAtMs >= 0 && failuresInARow >= 1 && failuresInARow <= MAX_FAILURES
                && nowMs - upAtMs < WINDOW_MS;
    }
}
