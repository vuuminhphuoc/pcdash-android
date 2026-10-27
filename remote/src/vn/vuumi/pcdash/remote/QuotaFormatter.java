package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.i18n.L10n;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class QuotaFormatter {

    private QuotaFormatter() {}

    public enum Tier {
        LOW,
        MID,
        HIGH
    }

    public static Tier tier(int pct) {
        if (pct < 20) {
            return Tier.LOW;
        } else if (pct < 50) {
            return Tier.MID;
        } else {
            return Tier.HIGH;
        }
    }

    public static String resetText(Long resetsAtMs, long nowMs) {
        if (resetsAtMs == null || resetsAtMs <= nowMs) {
            return L10n.t("resetting", "đang hồi");
        }
        long diffMs = resetsAtMs - nowMs;
        long totalMinutes = diffMs / 60000L;
        if (totalMinutes <= 0) {
            return L10n.t("resetting", "đang hồi");
        }
        long days = totalMinutes / (24 * 60);
        long hours = (totalMinutes % (24 * 60)) / 60;
        long minutes = totalMinutes % 60;

        if (days > 0) {
            if (hours > 0) {
                return L10n.t("resets in ", "hồi sau ") + days + L10n.t("d ", " ngày ") + hours + L10n.t("h", " giờ");
            } else {
                return L10n.t("resets in ", "hồi sau ") + days + L10n.t("d", " ngày");
            }
        } else if (hours > 0) {
            if (minutes > 0) {
                return L10n.t("resets in ", "hồi sau ") + hours + L10n.t("h ", " giờ ") + minutes + L10n.t("m", " phút");
            } else {
                return L10n.t("resets in ", "hồi sau ") + hours + L10n.t("h", " giờ");
            }
        } else {
            return L10n.t("resets in ", "hồi sau ") + minutes + L10n.t("m", " phút");
        }
    }

    public static String formatUpdatedTime(long fetchedAtMs) {
        if (fetchedAtMs <= 0) {
            return "";
        }
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return L10n.t("Updated at ", "Cập nhật lúc ") + sdf.format(new Date(fetchedAtMs));
    }
}
