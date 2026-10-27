package vn.vuumi.pcdash;

import java.util.Locale;

public interface RemoteActionExecutor {
    String statusJson();
    String doAction(String action);
    default String metricsJson() { return null; }
    default String quotaJson(boolean refresh) { return null; }

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
