package vn.vuumi.pcdash.ui;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * How a tap feels in both apps: a ripple, a slight shrink while the finger is down, a light tick of
 * vibration (only if touch vibration is on in Android settings), and a guard so a double tap or an
 * impatient second tap on the same control does not run its action twice.
 */
public final class Tap {
    /** Taps on the same control closer together than this count as one. */
    public static final long REPEAT_GUARD_MS = 800L;
    private static final float PRESSED_SCALE = 0.97f;
    private static final int RIPPLE = Color.argb(64, 255, 255, 255);

    private Tap() {}

    /** Press feel plus a click listener behind the repeat guard. */
    public static void wire(View view, View.OnClickListener listener) {
        feel(view);
        view.setOnClickListener(guarded(listener));
    }

    /** Wraps a listener: ignores repeat taps within {@link #REPEAT_GUARD_MS}, ticks on each real tap. */
    public static View.OnClickListener guarded(final View.OnClickListener listener) {
        return new View.OnClickListener() {
            private long lastTapMs;
            private boolean tapped;

            @Override
            public void onClick(View v) {
                long now = SystemClock.uptimeMillis();
                if (tapped && now - lastTapMs < REPEAT_GUARD_MS) {
                    return;
                }
                tapped = true;
                lastTapMs = now;
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                listener.onClick(v);
            }
        };
    }

    /**
     * Ripple (unless the background already reacts to a press) and the pressed shrink. Call after the
     * background is set; setting a plain background later drops the ripple again.
     */
    public static void feel(View view) {
        Drawable bg = view.getBackground();
        Drawable fg = view.getForeground();
        boolean lit = fg != null && fg.isStateful(); // a GlowDrawable already shows the press
        if (!lit && (bg == null || !bg.isStateful())) {
            // Without a mask the ripple only shows where the background is opaque, so a clear or
            // missing background gets the view's rectangle as mask; shapes keep their own outline.
            Drawable mask = bg == null || bg instanceof ColorDrawable ? new ColorDrawable(Color.WHITE) : null;
            view.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), bg, mask));
        }
        StateListAnimator press = new StateListAnimator();
        press.addState(new int[]{android.R.attr.state_pressed, android.R.attr.state_enabled},
                scale(view, PRESSED_SCALE, 70));
        press.addState(new int[0], scale(view, 1f, 160));
        view.setStateListAnimator(press);
        view.setClickable(true);
    }

    /**
     * Lights the view up where it is touched (see {@link GlowDrawable}); call before {@link #wire}.
     *
     * @param buttonColor the button's fill; the glow uses a lighter shade of it
     */
    public static GlowDrawable glow(View view, int buttonColor, float cornerPx) {
        float density = view.getResources().getDisplayMetrics().density;
        GlowDrawable g = new GlowDrawable(GlowDrawable.lightOf(buttonColor), cornerPx, 1.5f * density);
        view.setForeground(g);
        return g;
    }

    /** A firm tick when an action worked, a short double buzz when it failed. */
    public static void outcome(View view, boolean ok) {
        int feedback;
        if (Build.VERSION.SDK_INT >= 30) {
            feedback = ok ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.REJECT;
        } else {
            feedback = ok ? HapticFeedbackConstants.VIRTUAL_KEY : HapticFeedbackConstants.LONG_PRESS;
        }
        view.performHapticFeedback(feedback);
    }

    private static Animator scale(View view, float to, long ms) {
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, to),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, to));
        a.setDuration(ms);
        a.setInterpolator(new DecelerateInterpolator());
        return a;
    }
}
