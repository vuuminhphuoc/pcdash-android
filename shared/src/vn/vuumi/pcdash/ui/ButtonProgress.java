package vn.vuumi.pcdash.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.View;
import android.widget.TextView;

/**
 * Shows on a text button that its action is running (new label, a slow pulse, taps blocked), then how
 * it went for a moment, then puts the original label back.
 */
public final class ButtonProgress {
    /** How long the outcome stays on the button before the label comes back. */
    public static final long RESULT_HOLD_MS = 2000L;

    private final TextView button;
    private CharSequence idleText;
    private ObjectAnimator pulse;
    private boolean busy;
    private final Runnable restore = new Runnable() {
        @Override
        public void run() {
            idle();
        }
    };

    public ButtonProgress(TextView button) {
        this.button = button;
        button.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {}

            @Override
            public void onViewDetachedFromWindow(View v) {
                // A repeating animation would keep running for a closed screen.
                stopPulse();
                v.removeCallbacks(restore);
            }
        });
    }

    /** True from {@link #busy} until the label is back (including while the outcome shows). */
    public boolean isBusy() {
        return busy;
    }

    public void busy(CharSequence text) {
        button.removeCallbacks(restore);
        remember();
        busy = true;
        button.setText(text);
        button.setEnabled(false);
        button.announceForAccessibility(text);
        if (pulse == null) {
            pulse = ObjectAnimator.ofFloat(button, View.ALPHA, 1f, 0.55f);
            pulse.setDuration(650);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
        }
        if (!pulse.isStarted()) {
            pulse.start();
        }
    }

    public void done(boolean ok, CharSequence text) {
        stopPulse();
        remember();
        busy = true;
        button.setText((ok ? "✓ " : "✕ ") + text);
        button.setEnabled(false);
        Tap.outcome(button, ok);
        button.announceForAccessibility(button.getText());
        button.removeCallbacks(restore);
        button.postDelayed(restore, RESULT_HOLD_MS);
    }

    public void idle() {
        button.removeCallbacks(restore);
        stopPulse();
        if (idleText != null) {
            button.setText(idleText);
            idleText = null;
        }
        busy = false;
        button.setEnabled(true);
    }

    private void remember() {
        if (idleText == null) {
            idleText = button.getText();
        }
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.cancel();
        }
        button.setAlpha(1f);
    }
}
