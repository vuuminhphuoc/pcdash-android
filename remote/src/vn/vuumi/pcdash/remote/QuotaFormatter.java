package vn.vuumi.pcdash.remote;

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
            return "đang hồi";
        }
        long diffMs = resetsAtMs - nowMs;
        long totalMinutes = diffMs / 60000L;
        if (totalMinutes <= 0) {
            return "đang hồi";
        }
        long days = totalMinutes / (24 * 60);
        long hours = (totalMinutes % (24 * 60)) / 60;
        long minutes = totalMinutes % 60;

        if (days > 0) {
            if (hours > 0) {
                return "hồi sau " + days + " ngày " + hours + " giờ";
            } else {
                return "hồi sau " + days + " ngày";
            }
        } else if (hours > 0) {
            if (minutes > 0) {
                return "hồi sau " + hours + " giờ " + minutes + " phút";
            } else {
                return "hồi sau " + hours + " giờ";
            }
        } else {
            return "hồi sau " + minutes + " phút";
        }
    }

    public static String formatUpdatedTime(long fetchedAtMs) {
        if (fetchedAtMs <= 0) {
            return "";
        }
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return "Cập nhật lúc " + sdf.format(new Date(fetchedAtMs));
    }
}
