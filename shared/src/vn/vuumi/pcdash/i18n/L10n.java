package vn.vuumi.pcdash.i18n;

import java.util.Locale;

/**
 * UI text in English and Vietnamese. Vietnamese is used when the language is "vi", English
 * otherwise. The language comes from, in order: the current thread (the host sets it per API
 * request from the remote's Accept-Language), {@link #setOverride}, then the system locale,
 * which on Android 13+ follows the per-app language chosen in system settings.
 */
public final class L10n {
    public static final String EN = "en";
    public static final String VI = "vi";

    private static final ThreadLocal<String> threadLanguage = new ThreadLocal<String>();
    private static volatile String override = null;

    private L10n() {}

    public static String t(String en, String vi) {
        return isVietnamese() ? vi : en;
    }

    public static boolean isVietnamese() {
        return VI.equals(language());
    }

    /** "vi" or "en". */
    public static String language() {
        String lang = threadLanguage.get();
        if (lang == null) lang = override;
        if (lang == null) lang = Locale.getDefault().getLanguage();
        return VI.equals(normalize(lang)) ? VI : EN;
    }

    /** Forces a language for the whole process (tests); null follows the system locale again. */
    public static void setOverride(String lang) {
        override = lang != null ? normalize(lang) : null;
    }

    /** Uses the first language of an Accept-Language header on this thread; null clears it. */
    public static void setThreadLanguage(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.trim().isEmpty()) {
            threadLanguage.remove();
        } else {
            threadLanguage.set(normalize(acceptLanguage));
        }
    }

    static String normalize(String lang) {
        String s = lang.trim().toLowerCase(Locale.ROOT);
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ',' || c == ';' || c == '-' || c == '_' || c == ' ') {
                cut = i;
                break;
            }
        }
        return s.substring(0, cut);
    }
}
