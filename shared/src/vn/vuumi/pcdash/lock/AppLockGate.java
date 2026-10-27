package vn.vuumi.pcdash.lock;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import vn.vuumi.pcdash.i18n.L10n;
import vn.vuumi.pcdash.ui.SystemBarInsets;

/**
 * Puts the lock screen over one activity while the app is locked. Call onStart/onStop from the
 * activity's own. Every activity of the app needs a gate, so back or a launcher shortcut never lands on
 * an unlocked screen; unlocking in one activity unlocks the whole app.
 *
 * The lock screen covers the activity's content frame (android.R.id.content), which already sits
 * below the action bar and inside the system bars the activity pads for.
 */
public final class AppLockGate {

    private final Activity activity;
    private final AppLock lock;
    private final int padSides;
    private ScrollView overlay;
    private LockView lockView;
    private Runnable pendingAfterUnlock;
    // The fingerprint prompt opens by itself once per lock; cancelling it must not reopen it on the
    // next onStart (some phones show the prompt as an activity, which stops and restarts ours).
    private boolean biometricOffered;
    // The screen turned off with this activity on screen and that closed the lock: cover it now, so
    // the app is hidden before the screen comes back even if the activity was not stopped.
    private final Runnable onScreenLock = new Runnable() {
        @Override
        public void run() {
            refresh(false);
        }
    };

    /**
     * @param padSides system bar sides ({@link SystemBarInsets}) the content frame does not pad itself,
     *                 so the lock screen pads them (0 when the activity pads the content frame on all sides)
     */
    public AppLockGate(Activity activity, int padSides) {
        this.activity = activity;
        this.lock = AndroidAppLock.get(activity);
        this.padSides = padSides;
    }

    public void onStart() {
        lock.onActivityStarted(SystemClock.elapsedRealtime());
        AndroidAppLock.addLockListener(onScreenLock);
        refresh(true);
    }

    public void onStop() {
        AndroidAppLock.removeLockListener(onScreenLock);
        lock.onActivityStopped(SystemClock.elapsedRealtime(), activity.isChangingConfigurations());
    }

    public boolean isLocked() {
        return lock.isLocked();
    }

    /** Runs the task now, or once the user unlocks (replacing an earlier waiting task). */
    public void runWhenUnlocked(Runnable task) {
        if (!lock.isLocked()) {
            task.run();
        } else {
            pendingAfterUnlock = task;
        }
    }

    /** Shows or removes the lock screen to match the lock state (call after changing settings). */
    public void refresh(boolean offerBiometric) {
        if (Build.VERSION.SDK_INT >= 33) {
            // Keep the app's content out of the recent-apps preview while a lock is set.
            activity.setRecentsScreenshotEnabled(!lock.isEnabled());
        }
        if (!lock.isLocked()) {
            biometricOffered = false;
            hide();
            return;
        }
        show();
        long until = lock.lockedUntil(System.currentTimeMillis());
        if (until > 0) {
            lockView.lockUntil(until);
        } else if (offerBiometric && !biometricOffered && biometricUsable()) {
            biometricOffered = true;
            BiometricUnlock.prompt(activity, new Runnable() {
                @Override
                public void run() {
                    lock.unlockWithBiometric();
                    unlocked();
                }
            });
        }
    }

    private boolean biometricUsable() {
        return lock.isBiometricEnabled() && BiometricUnlock.available(activity);
    }

    private void show() {
        if (overlay == null) {
            lockView = new LockView(activity, new LockView.Listener() {
                @Override
                public void onCode(String code) {
                    AppLock.Result r = lock.check(code, System.currentTimeMillis());
                    if (r.kind == AppLock.Result.OK) {
                        unlocked();
                    } else if (r.kind == AppLock.Result.LOCKED_OUT) {
                        lockView.lockUntil(r.retryAtMs);
                    } else {
                        lockView.reset();
                        lockView.showError(L10n.t("Wrong passcode. Tries left before a wait: ", "Sai mã. Số lần thử còn lại: ")
                                + r.attemptsLeft);
                    }
                }

                @Override
                public void onBiometric() {
                    BiometricUnlock.prompt(activity, new Runnable() {
                        @Override
                        public void run() {
                            lock.unlockWithBiometric();
                            unlocked();
                        }
                    });
                }
            });
            lockView.setTitle(L10n.t("PCDash is locked", "PCDash đang khóa"));
            lockView.setMessage(L10n.t("Enter your 6-digit passcode", "Nhập mã 6 số để mở"));
            overlay = new ScrollView(activity);
            overlay.setFillViewport(true);
            overlay.setBackgroundColor(Color.BLACK);
            overlay.addView(lockView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (padSides != 0) {
                SystemBarInsets.pad(overlay, padSides);
            }
        }
        lockView.setBiometricVisible(biometricUsable());
        if (overlay.getParent() == null) {
            lockView.reset();
            lockView.showError("");
            ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
            if (content == null) return;
            content.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            setContentHidden(true);
            overlay.requestFocus();
        }
    }

    private void hide() {
        if (overlay != null && overlay.getParent() != null) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
            setContentHidden(false);
        }
    }

    private void unlocked() {
        biometricOffered = false;
        hide();
        Runnable task = pendingAfterUnlock;
        pendingAfterUnlock = null;
        if (task != null) {
            task.run();
        }
    }

    // Screen readers must not read the app behind the lock screen.
    private void setContentHidden(boolean hidden) {
        ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
        if (content == null) return;
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child != overlay) {
                child.setImportantForAccessibility(hidden
                        ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            }
        }
    }
}
