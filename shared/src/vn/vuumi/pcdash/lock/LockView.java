package vn.vuumi.pcdash.lock;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import vn.vuumi.pcdash.i18n.L10n;

/** Full-screen 6-digit keypad, used for unlocking and for setting or checking the passcode. */
public final class LockView extends LinearLayout {

    public interface Listener {
        void onCode(String code);

        void onBiometric();
    }

    private static final int TEXT = Color.rgb(230, 237, 243);
    private static final int MUTED = Color.rgb(139, 148, 158);
    private static final int ERROR = Color.rgb(248, 81, 73);
    private static final int KEY_FILL = Color.rgb(22, 27, 34);
    private static final int KEY_BORDER = Color.rgb(48, 54, 61);
    private static final int DOT_EMPTY = Color.rgb(48, 54, 61);
    private static final int DOT_FILLED = Color.rgb(88, 166, 255);

    private final Listener listener;
    private final TextView titleView;
    private final TextView messageView;
    private final TextView errorView;
    private final LinearLayout dotsRow;
    private final View[] dots = new View[AppLock.CODE_LENGTH];
    private final TextView biometricButton;
    private final StringBuilder entered = new StringBuilder();
    private boolean inputEnabled = true;
    private long retryAtMs;

    private final Runnable countdown = new Runnable() {
        @Override
        public void run() {
            long left = retryAtMs - System.currentTimeMillis();
            if (left <= 0) {
                setInputEnabled(true);
                errorView.setText("");
                return;
            }
            long s = (left + 999) / 1000;
            errorView.setText(L10n.t("Too many wrong codes. Try again in ", "Sai mã quá nhiều lần. Thử lại sau ")
                    + (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60));
            postDelayed(this, 1000);
        }
    };

    public LockView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setBackgroundColor(Color.BLACK);
        // Covers the app below: swallow touches that miss the keypad.
        setClickable(true);
        setFocusable(true);
        int pad = dp(24);
        setPadding(pad, dp(48), pad, pad);

        TextView lockIcon = new TextView(context);
        lockIcon.setText("🔒");
        lockIcon.setTextSize(34);
        lockIcon.setGravity(Gravity.CENTER);
        lockIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(lockIcon);

        titleView = text(context, 22, TEXT, true);
        titleView.setPadding(0, dp(12), 0, dp(6));
        addView(titleView);

        messageView = text(context, 14, MUTED, false);
        addView(messageView);

        dotsRow = new LinearLayout(context);
        dotsRow.setOrientation(HORIZONTAL);
        dotsRow.setGravity(Gravity.CENTER);
        dotsRow.setPadding(0, dp(28), 0, dp(12));
        for (int i = 0; i < dots.length; i++) {
            View dot = new View(context);
            LayoutParams lp = new LayoutParams(dp(14), dp(14));
            lp.setMargins(dp(8), 0, dp(8), 0);
            dot.setLayoutParams(lp);
            dots[i] = dot;
            dotsRow.addView(dot);
        }
        addView(dotsRow);

        errorView = text(context, 13, ERROR, false);
        errorView.setMinHeight(dp(36));
        errorView.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(errorView);

        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"", "0", "⌫"}};
        for (String[] row : rows) {
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            for (String label : row) {
                line.addView(key(context, label));
            }
            addView(line);
        }

        biometricButton = text(context, 15, Color.rgb(88, 166, 255), true);
        biometricButton.setText(L10n.t("Use fingerprint / face", "Dùng vân tay / khuôn mặt"));
        biometricButton.setPadding(dp(16), dp(18), dp(16), dp(12));
        biometricButton.setVisibility(GONE);
        biometricButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                LockView.this.listener.onBiometric();
            }
        });
        addView(biometricButton);

        updateDots();
    }

    public void setTitle(String title) {
        titleView.setText(title);
    }

    public void setMessage(String message) {
        messageView.setText(message);
    }

    public void showError(String error) {
        errorView.setText(error);
    }

    public void setBiometricVisible(boolean visible) {
        biometricButton.setVisibility(visible ? VISIBLE : GONE);
    }

    /** Clears the typed digits (keeps the error text). */
    public void reset() {
        entered.setLength(0);
        updateDots();
    }

    /** Disables the keypad until the given wall-clock time, with a countdown. */
    public void lockUntil(long retryAt) {
        retryAtMs = retryAt;
        reset();
        setInputEnabled(false);
        removeCallbacks(countdown);
        countdown.run();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(countdown);
        super.onDetachedFromWindow();
    }

    private void setInputEnabled(boolean enabled) {
        inputEnabled = enabled;
        dotsRow.setAlpha(enabled ? 1f : 0.4f);
    }

    private void press(String label) {
        if (!inputEnabled) return;
        if ("⌫".equals(label)) {
            if (entered.length() > 0) {
                entered.setLength(entered.length() - 1);
            }
            updateDots();
            return;
        }
        if (entered.length() >= AppLock.CODE_LENGTH) return;
        entered.append(label);
        errorView.setText("");
        updateDots();
        if (entered.length() == AppLock.CODE_LENGTH) {
            final String code = entered.toString();
            // Let the last dot draw before the check runs.
            post(new Runnable() {
                @Override
                public void run() {
                    listener.onCode(code);
                }
            });
        }
    }

    private void updateDots() {
        for (int i = 0; i < dots.length; i++) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(i < entered.length() ? DOT_FILLED : DOT_EMPTY);
            dots[i].setBackground(d);
        }
        dotsRow.setContentDescription(entered.length() + " / " + AppLock.CODE_LENGTH);
    }

    private View key(Context context, final String label) {
        TextView key = new TextView(context);
        LayoutParams lp = new LayoutParams(dp(72), dp(72));
        lp.setMargins(dp(10), dp(6), dp(10), dp(6));
        key.setLayoutParams(lp);
        if (label.isEmpty()) {
            key.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            return key;
        }
        key.setText(label);
        key.setTextSize(26);
        key.setTextColor(TEXT);
        key.setGravity(Gravity.CENTER);
        if ("⌫".equals(label)) {
            key.setContentDescription(L10n.t("Delete", "Xóa"));
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(KEY_FILL);
        bg.setStroke(dp(1), KEY_BORDER);
        key.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(70, 255, 255, 255)), bg, null));
        key.setClickable(true);
        key.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                press(label);
            }
        });
        return key;
    }

    private TextView text(Context context, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(context);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
