package vn.vuumi.pcdash.remote;

/**
 * A Wake or Sleep asked for from a launcher shortcut or the Quick Settings tile. The request lives
 * only in this process, so another app cannot trigger a command by starting RemoteMainActivity
 * (which is exported for the launcher); it can only reach the non-exported QuickActionActivity
 * through the system on our behalf.
 */
final class QuickActions {
    static final String ACTION_WAKE = "vn.vuumi.pcdash.remote.action.WAKE";
    static final String ACTION_SLEEP = "vn.vuumi.pcdash.remote.action.SLEEP";
    // Long enough for tsnet to come up after a cold start, short enough that a stale tap never fires later.
    static final long MAX_AGE_MS = 60_000L;

    private static String pending;
    private static long requestedAtMs;

    private QuickActions() {}

    static synchronized void request(String intentAction, long nowMs) {
        pending = commandFor(intentAction);
        requestedAtMs = nowMs;
    }

    /** The command to send now ("wake" or "sleep"), or null. Clears the request either way. */
    static synchronized String take(long nowMs) {
        String command = pending;
        pending = null;
        if (command == null || nowMs < requestedAtMs || nowMs - requestedAtMs > MAX_AGE_MS) {
            return null;
        }
        return command;
    }

    static String commandFor(String intentAction) {
        if (ACTION_WAKE.equals(intentAction)) return "wake";
        if (ACTION_SLEEP.equals(intentAction)) return "sleep";
        return null;
    }
}
