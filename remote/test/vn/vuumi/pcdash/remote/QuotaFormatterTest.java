package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.i18n.L10n;

public class QuotaFormatterTest {

    public static void main(String[] args) {
        L10n.setOverride(L10n.VI); // the assertions below check the Vietnamese UI text
        System.out.println("Running QuotaFormatterTest...");
        testTier();
        testResetText();
        testFormatUpdatedTime();
        System.out.println("ALL QuotaFormatterTest assertions PASSED!");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    public static void testTier() {
        assertTrue(QuotaFormatter.tier(0) == QuotaFormatter.Tier.LOW, "tier(0)=LOW");
        assertTrue(QuotaFormatter.tier(19) == QuotaFormatter.Tier.LOW, "tier(19)=LOW");
        assertTrue(QuotaFormatter.tier(20) == QuotaFormatter.Tier.MID, "tier(20)=MID");
        assertTrue(QuotaFormatter.tier(49) == QuotaFormatter.Tier.MID, "tier(49)=MID");
        assertTrue(QuotaFormatter.tier(50) == QuotaFormatter.Tier.HIGH, "tier(50)=HIGH");
        assertTrue(QuotaFormatter.tier(100) == QuotaFormatter.Tier.HIGH, "tier(100)=HIGH");
        System.out.println("PASS: testTier");
    }

    public static void testResetText() {
        long nowMs = 1730000000000L;

        // 3 hours 17 minutes
        long reset3h17m = nowMs + (3L * 3600 + 17L * 60) * 1000L;
        String text3h17m = QuotaFormatter.resetText(reset3h17m, nowMs);
        assertTrue("hồi sau 3 giờ 17 phút".equals(text3h17m), "3h 17m formatted: " + text3h17m);

        // 6 days 22 hours
        long reset6d22h = nowMs + (6L * 86400 + 22L * 3600) * 1000L;
        String text6d22h = QuotaFormatter.resetText(reset6d22h, nowMs);
        assertTrue("hồi sau 6 ngày 22 giờ".equals(text6d22h), "6d 22h formatted: " + text6d22h);

        // null
        String textNull = QuotaFormatter.resetText(null, nowMs);
        assertTrue("đang hồi".equals(textNull), "null formatted: " + textNull);

        // In the past (already passed)
        long resetPast = nowMs - 10000L;
        String textPast = QuotaFormatter.resetText(resetPast, nowMs);
        assertTrue("đang hồi".equals(textPast), "past formatted: " + textPast);

        // Exact now
        String textExactNow = QuotaFormatter.resetText(nowMs, nowMs);
        assertTrue("đang hồi".equals(textExactNow), "now formatted: " + textExactNow);

        System.out.println("PASS: testResetText");
    }

    public static void testFormatUpdatedTime() {
        assertTrue("".equals(QuotaFormatter.formatUpdatedTime(0L)), "formatUpdatedTime(0) is empty");
        assertTrue("".equals(QuotaFormatter.formatUpdatedTime(-100L)), "formatUpdatedTime(-100) is empty");

        long ts = 1730000000000L;
        String text = QuotaFormatter.formatUpdatedTime(ts);
        assertTrue(text != null && text.startsWith("Cập nhật lúc "), "formatUpdatedTime starts with 'Cập nhật lúc '");
        assertTrue(text.matches("^Cập nhật lúc \\d{2}:\\d{2}$"), "formatUpdatedTime matches pattern HH:mm: " + text);
        System.out.println("PASS: testFormatUpdatedTime");
    }
}
