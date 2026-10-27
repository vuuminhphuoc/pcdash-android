package vn.vuumi.pcdash;

import java.util.Locale;

public interface RemoteActionExecutor {
    String statusJson();
    String doAction(String action);
    default String metricsJson() { return null; }
    default String quotaJson(boolean refresh) { return null; }

    // Several PCs. A null pcId is the first PC; executors with one PC keep the defaults.

    /** {"pcs":[{"id","name","online","port","rtt_ms"}, ...]}, or null when there is no PC list. */
    default String pcsJson() { return null; }
    default String statusJson(String pcId) { return statusJson(); }
    default String doAction(String pcId, String action) { return doAction(action); }
    default String metricsJson(String pcId) { return metricsJson(); }
    default String quotaJson(String pcId, boolean refresh) { return quotaJson(refresh); }
    /** One PC's history; null lets the server answer from its own (single-PC) history. */
    default String historyJson(String pcId, int minutes) { return null; }
    /** The PC's name for the command log when the host has more than one PC; null otherwise. */
    default String pcLabel(String pcId) { return null; }

    /** True when a doAction() result reports a failure, in English or Vietnamese. */
    static boolean isFailure(String detail) {
        if (detail == null) return false;
        String lower = detail.trim().toLowerCase(Locale.ROOT);
        for (String prefix : FAILURE_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }

    String[] FAILURE_PREFIXES = {
            "error", "wol error", "agent refused", "the pc agent has no token", "cannot reach the pc agent",
            "agent[4", "agent[5", // the agent answered with an HTTP error
            "lỗi", "agent từ chối", "agent pc chưa", "không kết nối được" // l10n-ok: matches both languages
    };
}
