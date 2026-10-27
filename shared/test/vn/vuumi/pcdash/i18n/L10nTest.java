package vn.vuumi.pcdash.i18n;

import java.util.Locale;

public class L10nTest {

    private static void assertEquals(Object actual, Object expected, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Assertion failed for " + message + ": expected <" + expected + "> but got <" + actual + ">");
    }

    private static void testFollowsSystemLocale() {
        Locale saved = Locale.getDefault();
        try {
            L10n.setOverride(null);
            Locale.setDefault(Locale.US);
            assertEquals(L10n.t("Wake", "Bật"), "Wake", "English system locale");
            Locale.setDefault(new Locale("vi", "VN"));
            assertEquals(L10n.t("Wake", "Bật"), "Bật", "Vietnamese system locale");
            Locale.setDefault(Locale.FRANCE);
            assertEquals(L10n.language(), L10n.EN, "Other languages fall back to English");
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("PASS: testFollowsSystemLocale");
    }

    private static void testOverrideWinsOverSystem() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            L10n.setOverride("vi");
            assertEquals(L10n.t("Wake", "Bật"), "Bật", "Override vi");
            L10n.setOverride(null);
            assertEquals(L10n.t("Wake", "Bật"), "Wake", "Cleared override");
        } finally {
            L10n.setOverride(null);
            Locale.setDefault(saved);
        }
        System.out.println("PASS: testOverrideWinsOverSystem");
    }

    private static void testThreadLanguageFromAcceptLanguage() throws Exception {
        L10n.setOverride(L10n.EN);
        try {
            L10n.setThreadLanguage("vi-VN,vi;q=0.9,en;q=0.8");
            assertEquals(L10n.language(), L10n.VI, "Accept-Language vi-VN");
            final String[] other = new String[1];
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    other[0] = L10n.language();
                }
            });
            t.start();
            t.join();
            assertEquals(other[0], L10n.EN, "Thread language does not leak to other threads");
            L10n.setThreadLanguage("en-GB");
            assertEquals(L10n.language(), L10n.EN, "Accept-Language en-GB");
            L10n.setThreadLanguage("VI");
            assertEquals(L10n.language(), L10n.VI, "Upper case code");
            L10n.setThreadLanguage(null);
            assertEquals(L10n.language(), L10n.EN, "Cleared thread language");
            L10n.setThreadLanguage("   ");
            assertEquals(L10n.language(), L10n.EN, "Blank header is ignored");
        } finally {
            L10n.setThreadLanguage(null);
            L10n.setOverride(null);
        }
        System.out.println("PASS: testThreadLanguageFromAcceptLanguage");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Running L10nTest...");
        testFollowsSystemLocale();
        testOverrideWinsOverSystem();
        testThreadLanguageFromAcceptLanguage();
        System.out.println("ALL L10nTest PASSED");
    }
}
