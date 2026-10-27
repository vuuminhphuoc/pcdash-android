package vn.vuumi.pcdash.lock;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import vn.vuumi.pcdash.i18n.L10n;

/** Fingerprint or face unlock through the system BiometricPrompt (Android 10+). */
public final class BiometricUnlock {

    private BiometricUnlock() {}

    /** True when the phone has a biometric enrolled that can unlock the app. */
    public static boolean available(Context context) {
        if (Build.VERSION.SDK_INT < 29) {
            return false;
        }
        try {
            BiometricManager bm = context.getSystemService(BiometricManager.class);
            if (bm == null) return false;
            int result = Build.VERSION.SDK_INT >= 30
                    ? bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    : bm.canAuthenticate();
            return result == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shows the system prompt; runs onSuccess on the main thread when it accepts. */
    public static void prompt(Activity activity, final Runnable onSuccess) {
        if (Build.VERSION.SDK_INT < 29 || activity.isFinishing()) {
            return;
        }
        try {
            BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
                    .setTitle(L10n.t("Unlock PCDash", "Mở khóa PCDash"))
                    .setNegativeButton(L10n.t("Use passcode", "Dùng mã"), activity.getMainExecutor(),
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    // The keypad stays on screen.
                                }
                            });
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK);
            }
            builder.build().authenticate(new CancellationSignal(), activity.getMainExecutor(),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            onSuccess.run();
                        }
                    });
        } catch (Throwable ignored) {
            // No prompt (for example the activity is going away); the keypad still works.
        }
    }
}
