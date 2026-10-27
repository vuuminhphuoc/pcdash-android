package vn.vuumi.pcdash.remote;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * A small coloured dot that sends out a soft ring now and then while something is alive (the PC is
 * on, a connection is being made) and sits still otherwise.
 */
final class StatusDot extends FrameLayout {
    private final View dot;
    private final View halo;
    private final GradientDrawable dotShape = new GradientDrawable();
    private final GradientDrawable haloShape = new GradientDrawable();
    private AnimatorSet ping;
    private long pingMs;

    StatusDot(Context context) {
        super(context);
        setClipChildren(false);
        int size = UiKit.dp(context, 10);
        dotShape.setShape(GradientDrawable.OVAL);
        haloShape.setShape(GradientDrawable.OVAL);
        halo = new View(context);
        halo.setBackground(haloShape);
        halo.setAlpha(0f);
        addView(halo, new LayoutParams(size, size, Gravity.CENTER));
        dot = new View(context);
        dot.setBackground(dotShape);
        addView(dot, new LayoutParams(size, size, Gravity.CENTER));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * @param pingEveryMs how often a ring goes out; 0 for a still dot
     */
    void show(int color, long pingEveryMs) {
        dotShape.setColor(color);
        haloShape.setColor(color);
        if (pingEveryMs == pingMs && (pingMs == 0 || (ping != null && ping.isStarted()))) return;
        stopPing();
        pingMs = pingEveryMs;
        if (pingEveryMs > 0 && isAttachedToWindow()) {
            startPing();
        }
    }

    private void startPing() {
        ObjectAnimator sx = ObjectAnimator.ofFloat(halo, View.SCALE_X, 1f, 2.8f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(halo, View.SCALE_Y, 1f, 2.8f);
        ObjectAnimator a = ObjectAnimator.ofFloat(halo, View.ALPHA, 0.55f, 0f);
        for (ObjectAnimator o : new ObjectAnimator[]{sx, sy, a}) {
            o.setRepeatCount(ValueAnimator.INFINITE);
            o.setDuration(pingMs);
        }
        ping = new AnimatorSet();
        ping.playTogether(sx, sy, a);
        ping.setInterpolator(new DecelerateInterpolator(1.4f));
        ping.start();
    }

    private void stopPing() {
        if (ping != null) {
            ping.cancel();
            ping = null;
        }
        halo.setAlpha(0f);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (pingMs > 0 && (ping == null || !ping.isStarted())) startPing();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPing();
        super.onDetachedFromWindow();
    }
}
