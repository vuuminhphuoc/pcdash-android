package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.i18n.L10n;

/**
 * Names in the quota tab come from the PC (omp's report), in whatever language it wrote them. The ones
 * the app knows are shown in the app's language; anything else is shown as received.
 */
final class QuotaLabels {
    private QuotaLabels() {}

    /** A window's name ("5 hours", "Week", "30 days"...), from its id and the label the PC sent. */
    static String window(String id, String label) {
        String fromId = known(id);
        if (fromId != null) return fromId;
        String fromLabel = known(label);
        if (fromLabel != null) return fromLabel;
        return label != null && !label.isEmpty() ? label : (id != null ? id : "");
    }

    /** A group's name; the "(shared)" the PC appends is translated. */
    static String group(String name) {
        if (name == null) return "";
        String fromTable = known(name);
        if (fromTable != null) return fromTable;
        return name.replace("(shared)", L10n.t("(shared)", "(dùng chung)"));
    }

    private static String known(String s) {
        if (s == null) return null;
        switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "5h": case "5 giờ": case "5 hours": case "5 hour": // l10n-ok: matches the PC's label in either language
                return L10n.t("5 hours", "5 giờ");
            case "weekly": case "1w": case "tuần": case "week": // l10n-ok: matches the PC's label in either language
                return L10n.t("Week", "Tuần");
            case "monthly": case "1mo": case "tháng": case "month": // l10n-ok: matches the PC's label in either language
                return L10n.t("Month", "Tháng");
            case "30d": case "30 ngày": case "30 days": // l10n-ok: matches the PC's label in either language
                return L10n.t("30 days", "30 ngày");
            case "chat requests": case "yêu cầu chat": // l10n-ok: matches the PC's label in either language
                return L10n.t("Chat requests", "Yêu cầu chat");
            case "completions": case "gợi ý mã": // l10n-ok: matches the PC's label in either language
                return L10n.t("Completions", "Gợi ý mã");
            default:
                return null;
        }
    }
}
