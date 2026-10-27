package vn.vuumi.pcdash.lock;

import vn.vuumi.pcdash.ui.Tap;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.security.GeneralSecurityException;
import vn.vuumi.pcdash.i18n.L10n;
import vn.vuumi.pcdash.ui.DialogStyle;
import vn.vuumi.pcdash.ui.SystemBarInsets;

/**
 * "App lock" settings: turn the 6-digit passcode on or off, change it, choose when it locks again, and
 * allow fingerprint or face. Turning it off or changing it asks for the current code first (wrong codes
 * count toward the lockout).
 */
public final class AppLockSettings {

    private AppLockSettings() {}

    public static void show(Activity activity, AppLockGate gate) {
        Panel panel = new Panel(activity, gate);
        panel.sync();
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(panel.body);
        DialogStyle.show(DialogStyle.builder(activity)
                .setTitle(L10n.t("App lock", "Khóa ứng dụng"))
                .setView(scroll)
                .setPositiveButton(L10n.t("Done", "Xong"), null), false);
    }

    private static final class Panel {
        final Activity activity;
        final AppLockGate gate;
        final AppLock lock;
        final boolean biometricAvailable;
        final LinearLayout body;
        final Switch lockSwitch;
        final Switch bioSwitch;
        final LinearLayout relockRow;
        final TextView relockValue;
        final TextView changeCode;
        // Set while the switches are moved to match the stored state, so their listeners ignore it.
        boolean syncing;

        Panel(Activity activity, AppLockGate gate) {
            this.activity = activity;
            this.gate = gate;
            this.lock = AndroidAppLock.get(activity);
            this.biometricAvailable = BiometricUnlock.available(activity);
            int pad = dp(activity, 20);
            body = new LinearLayout(activity);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(pad, dp(activity, 8), pad, 0);

            lockSwitch = switchRow(L10n.t("Lock with a 6-digit passcode", "Khóa bằng mã 6 số"));
            bioSwitch = switchRow(L10n.t("Unlock with fingerprint / face", "Mở bằng vân tay / khuôn mặt"));
            bioSwitch.setVisibility(biometricAvailable ? View.VISIBLE : View.GONE);

            relockRow = new LinearLayout(activity);
            relockRow.setOrientation(LinearLayout.VERTICAL);
            relockRow.setPadding(0, dp(activity, 12), 0, dp(activity, 12));
            relockRow.setClickable(true);
            relockRow.setFocusable(true);
            TextView relockTitle = new TextView(activity);
            relockTitle.setText(L10n.t("Lock again", "Khóa lại khi nào"));
            relockTitle.setTextSize(15);
            relockTitle.setTextColor(DialogStyle.TITLE);
            relockRow.addView(relockTitle);
            relockValue = new TextView(activity);
            relockValue.setTextSize(13);
            relockValue.setTextColor(DialogStyle.ACCENT);
            relockValue.setTypeface(Typeface.DEFAULT_BOLD);
            relockValue.setPadding(0, dp(activity, 2), 0, 0);
            relockRow.addView(relockValue);
            body.addView(relockRow);

            changeCode = new TextView(activity);
            changeCode.setText(L10n.t("Change passcode", "Đổi mã"));
            changeCode.setTextSize(15);
            changeCode.setTextColor(DialogStyle.ACCENT);
            changeCode.setPadding(0, dp(activity, 14), 0, dp(activity, 14));
            body.addView(changeCode);

            TextView note = new TextView(activity);
            note.setText(L10n.t("The app also locks whenever Android has closed it in the background. Forgot the code? Clear the app's data in Android settings (you will need to set the app up again).",
                    "App cũng khóa mỗi khi Android đã đóng app chạy nền. Quên mã: xóa dữ liệu ứng dụng trong Cài đặt Android (sẽ phải cài đặt lại app)."));
            note.setTextSize(12);
            note.setTextColor(DialogStyle.MUTED);
            note.setPadding(0, dp(activity, 4), 0, dp(activity, 8));
            body.addView(note);

            lockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton b, boolean checked) {
                    if (syncing) return;
                    sync(); // the stored state decides; the flows below change it
                    if (checked) {
                        askNewCode(Panel.this.activity, lock, syncTask());
                    } else {
                        askCurrentCode(Panel.this.activity, lock, new Runnable() {
                            @Override
                            public void run() {
                                lock.disable();
                                sync();
                            }
                        });
                    }
                }
            });
            bioSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton b, boolean checked) {
                    if (syncing) return;
                    sync();
                    if (!checked) {
                        lock.setBiometricEnabled(false);
                        sync();
                        return;
                    }
                    // Prove the biometric works before relying on it.
                    BiometricUnlock.prompt(Panel.this.activity, new Runnable() {
                        @Override
                        public void run() {
                            lock.setBiometricEnabled(true);
                            sync();
                        }
                    });
                }
            });
            Tap.wire(relockRow, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (lock.isEnabled()) chooseRelock();
                }
            });
            Tap.wire(changeCode, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!lock.isEnabled()) return;
                    askCurrentCode(Panel.this.activity, lock, new Runnable() {
                        @Override
                        public void run() {
                            askNewCode(Panel.this.activity, lock, syncTask());
                        }
                    });
                }
            });
        }

        void sync() {
            boolean on = lock.isEnabled();
            syncing = true;
            lockSwitch.setChecked(on);
            bioSwitch.setChecked(lock.isBiometricEnabled());
            syncing = false;
            bioSwitch.setEnabled(on && biometricAvailable);
            relockValue.setText(relockLabel(lock.relockAfterMs()));
            relockRow.setEnabled(on);
            relockRow.setAlpha(on ? 1f : 0.4f);
            changeCode.setEnabled(on);
            changeCode.setAlpha(on ? 1f : 0.4f);
            gate.refresh(false);
        }

        void chooseRelock() {
            final long[] choices = AppLock.RELOCK_CHOICES;
            String[] labels = new String[choices.length];
            int selected = 0;
            long current = lock.relockAfterMs();
            for (int i = 0; i < choices.length; i++) {
                labels[i] = relockLabel(choices[i]);
                if (choices[i] == current) selected = i;
            }
            AlertDialog.Builder b = DialogStyle.builder(activity)
                    .setTitle(L10n.t("Lock again", "Khóa lại khi nào"))
                    .setSingleChoiceItems(labels, selected, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            lock.setRelockAfterMs(choices[which]);
                            sync();
                            d.dismiss();
                        }
                    })
                    .setNegativeButton(L10n.t("Cancel", "Hủy"), null);
            DialogStyle.show(b, false);
        }

        Runnable syncTask() {
            return new Runnable() {
                @Override
                public void run() {
                    sync();
                }
            };
        }

        private Switch switchRow(String label) {
            Switch s = new Switch(activity);
            s.setText(label);
            s.setTextSize(15);
            s.setTextColor(DialogStyle.TITLE);
            s.setPadding(0, dp(activity, 12), 0, dp(activity, 12));
            body.addView(s);
            return s;
        }
    }

    static String relockLabel(long ms) {
        if (ms == AppLock.RELOCK_ON_SCREEN_OFF) {
            return L10n.t("Only when the phone screen turns off", "Chỉ khi tắt màn hình / khóa máy");
        }
        if (ms == 0L) {
            return L10n.t("As soon as I leave the app", "Ngay khi rời app");
        }
        if (ms < 60_000L) {
            return L10n.t("After " + (ms / 1000) + " seconds away", "Rời app quá " + (ms / 1000) + " giây");
        }
        return L10n.t("After " + (ms / 60_000L) + " min away", "Rời app quá " + (ms / 60_000L) + " phút");
    }

    /** New code typed twice; then the lock is on with it. */
    static void askNewCode(final Activity activity, final AppLock lock, final Runnable done) {
        final Dialog dialog = fullScreenDialog(activity);
        final LockView[] view = new LockView[1];
        final String[] first = new String[1];
        view[0] = new LockView(activity, new LockView.Listener() {
            @Override
            public void onCode(String code) {
                LockView v = view[0];
                if (first[0] == null) {
                    first[0] = code;
                    v.reset();
                    v.setMessage(L10n.t("Enter it again to confirm", "Nhập lại để xác nhận"));
                    return;
                }
                if (!first[0].equals(code)) {
                    first[0] = null;
                    v.reset();
                    v.setMessage(L10n.t("Choose a 6-digit passcode", "Chọn mã 6 số"));
                    v.showError(L10n.t("The codes did not match. Start again.", "Hai lần nhập không khớp. Nhập lại từ đầu."));
                    return;
                }
                try {
                    lock.setCode(code);
                    Toast.makeText(activity, L10n.t("App lock is on", "Đã bật khóa ứng dụng"), Toast.LENGTH_SHORT).show();
                } catch (GeneralSecurityException e) {
                    Toast.makeText(activity, L10n.t("This phone cannot store the passcode securely", "Máy này không lưu được mã an toàn"), Toast.LENGTH_LONG).show();
                }
                dialog.dismiss();
                done.run();
            }

            @Override
            public void onBiometric() {}
        });
        view[0].setTitle(L10n.t("New passcode", "Mã mới"));
        view[0].setMessage(L10n.t("Choose a 6-digit passcode", "Chọn mã 6 số"));
        showFullScreen(dialog, view[0]);
    }

    /** Current code, checked like on the lock screen; runs onOk when it is right. */
    static void askCurrentCode(final Activity activity, final AppLock lock, final Runnable onOk) {
        final Dialog dialog = fullScreenDialog(activity);
        final LockView[] view = new LockView[1];
        view[0] = new LockView(activity, new LockView.Listener() {
            @Override
            public void onCode(String code) {
                AppLock.Result r = lock.check(code, System.currentTimeMillis());
                if (r.kind == AppLock.Result.OK) {
                    dialog.dismiss();
                    onOk.run();
                } else if (r.kind == AppLock.Result.LOCKED_OUT) {
                    view[0].lockUntil(r.retryAtMs);
                } else {
                    view[0].reset();
                    view[0].showError(L10n.t("Wrong passcode. Tries left before a wait: ", "Sai mã. Số lần thử còn lại: ") + r.attemptsLeft);
                }
            }

            @Override
            public void onBiometric() {}
        });
        view[0].setTitle(L10n.t("Current passcode", "Mã hiện tại"));
        view[0].setMessage(L10n.t("Enter your current 6-digit passcode", "Nhập mã 6 số hiện tại"));
        showFullScreen(dialog, view[0]);
        long until = lock.lockedUntil(System.currentTimeMillis());
        if (until > 0) {
            view[0].lockUntil(until);
        }
    }

    private static Dialog fullScreenDialog(Activity activity) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        dialog.setCancelable(true);
        return dialog;
    }

    private static void showFullScreen(Dialog dialog, LockView view) {
        ScrollView scroll = new ScrollView(dialog.getContext());
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        SystemBarInsets.pad(scroll, SystemBarInsets.ALL);
        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        }
        dialog.show();
    }

    private static int dp(Activity activity, int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
