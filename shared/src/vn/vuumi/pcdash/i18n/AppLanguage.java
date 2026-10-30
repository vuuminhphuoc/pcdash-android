package vn.vuumi.pcdash.i18n;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.LocaleManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.LocaleList;

import vn.vuumi.pcdash.ui.DialogStyle;

/**
 * The language choice made inside the app: the phone's language (the default), English or Vietnamese.
 * From Android 13 the system keeps it (the same value as Settings > Apps > Language); older Android has
 * no such setting, so the choice is saved here and given to {@link L10n}.
 */
public final class AppLanguage {
    /** Follow the phone's language. */
    public static final String SYSTEM = "";

    private static final String PREFS = "pcdash_language";
    private static final String KEY = "lang";

    private AppLanguage() {}

    /** Call once when the app process starts, before any text is built. */
    public static void apply(Context context) {
        if (Build.VERSION.SDK_INT >= 33) return;
        String saved = saved(context);
        L10n.setOverride(saved.isEmpty() ? null : saved);
    }

    /** {@link #SYSTEM}, {@link L10n#EN} or {@link L10n#VI}. */
    public static String current(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager lm = context.getSystemService(LocaleManager.class);
            LocaleList list = lm != null ? lm.getApplicationLocales() : null;
            if (list == null || list.isEmpty()) return SYSTEM;
            return L10n.VI.equals(list.get(0).getLanguage()) ? L10n.VI : L10n.EN;
        }
        return saved(context);
    }

    /** Switches the language; the screen is rebuilt in it. */
    public static void set(Activity activity, String choice) {
        if (Build.VERSION.SDK_INT >= 33) {
            // The system restarts the app's screens with the new language.
            LocaleManager lm = activity.getSystemService(LocaleManager.class);
            if (lm != null) {
                lm.setApplicationLocales(choice.isEmpty() ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(choice));
            }
            return;
        }
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, choice).apply();
        L10n.setOverride(choice.isEmpty() ? null : choice);
        activity.recreate();
    }

    /** A dialog with the three choices. */
    public static void showPicker(final Activity activity) {
        String chosen = current(activity);
        final String[] values = {SYSTEM, L10n.EN, L10n.VI};
        final String[] names = {
                L10n.t("Phone's language", "Theo ngôn ngữ điện thoại"),
                "English",
                "Tiếng Việt", // l10n-ok: a language is named in itself
        };
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(chosen)) checked = i;
        }
        AlertDialog.Builder b = DialogStyle.builder(activity)
                .setTitle(L10n.t("Language", "Ngôn ngữ"))
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (!values[which].equals(current(activity))) set(activity, values[which]);
                    }
                })
                .setNegativeButton(L10n.t("Cancel", "Hủy"), null);
        DialogStyle.show(b, false);
    }

    private static String saved(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String v = p.getString(KEY, SYSTEM);
        return L10n.EN.equals(v) || L10n.VI.equals(v) ? v : SYSTEM;
    }
}
