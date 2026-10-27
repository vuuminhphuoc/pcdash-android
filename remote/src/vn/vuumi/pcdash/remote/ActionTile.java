package vn.vuumi.pcdash.remote;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import vn.vuumi.pcdash.ui.GlowDrawable;
import vn.vuumi.pcdash.ui.Tap;

/**
 * Icon + label tile for a power action that shows, on itself, what its command is doing: the icon
 * folds into a spinner with a short status while the command is sent and the PC changes state, then
 * turns into a tick or a cross for a moment (see {@link MorphIconDrawable}). The tile lights up where
 * it is touched and its rim breathes while the command runs (see {@link GlowDrawable}).
 */
final class ActionTile {
    static final long RESULT_HOLD_MS = 2500L;

    final LinearLayout view;
    private final MorphIconDrawable icon;
    private final GlowDrawable glow;
    private final TextView label;
    private final String idleLabel;
    private boolean busy;
    // The label's text, already set even while the cross-fade to it runs.
    private String labelText;
    private final Runnable restore = new Runnable() {
        @Override
        public void run() {
            idle();
        }
    };

    ActionTile(Context ctx, IconDrawable.Type iconType, String text, int fill, int stroke, int contentColor,
               int heightDp, View.OnClickListener listener) {
        idleLabel = text;
        view = new LinearLayout(ctx);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        // The spreading ring on success may draw past the icon's own box.
        view.setClipChildren(false);
        Drawable bg = stroke != 0
                ? UiKit.roundedOutline(ctx, fill, stroke, 12)
                : UiKit.roundedFill(ctx, fill, 12);
        view.setBackground(bg);
        glow = Tap.glow(view, stroke != 0 ? contentColor : fill, UiKit.dp(ctx, 12));
        view.setMinimumHeight(UiKit.dp(ctx, heightDp));
        int padH = UiKit.dp(ctx, 12);
        view.setPadding(padH, UiKit.dp(ctx, 8), padH, UiKit.dp(ctx, 8));

        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(UiKit.dp(ctx, 24), UiKit.dp(ctx, 24));
        iconLp.rightMargin = UiKit.dp(ctx, 10);
        icon = new MorphIconDrawable(iconType, contentColor);
        ImageView iconView = new ImageView(ctx);
        iconView.setImageDrawable(icon);
        iconView.setLayoutParams(iconLp);
        iconView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.addView(iconView);

        label = new TextView(ctx);
        label.setText(text);
        labelText = text;
        label.setTextColor(contentColor);
        label.setTextSize(15);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        view.addView(label);

        view.setContentDescription(text);
        Tap.wire(view, listener);
        // Endless animations must not run while the tile is off screen (another tab, app closed).
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                if (busy) {
                    icon.toBusy();
                    glow.setBreathing(true);
                }
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                icon.stop();
                glow.setBreathing(false);
            }
        });
    }

    // Quick cross-fade so the status text does not jump.
    private void setLabel(final String text) {
        if (text.equals(labelText)) return;
        labelText = text;
        label.animate().cancel();
        label.animate().alpha(0f).setDuration(90).withEndAction(new Runnable() {
            @Override
            public void run() {
                label.setText(text);
                label.animate().alpha(1f).setDuration(140).start();
            }
        }).start();
    }

    /** Command in flight or PC changing state: spinner, short status, taps blocked. */
    void busy(String status) {
        view.removeCallbacks(restore);
        busy = true;
        icon.toBusy();
        glow.setBreathing(true);
        setLabel(status);
        view.setEnabled(false);
        view.setAlpha(1f);
        view.setContentDescription(idleLabel + ": " + status);
        view.announceForAccessibility(status);
    }

    /** How it went, for a moment; then the tile is back to normal. */
    void result(boolean ok, String status) {
        view.removeCallbacks(restore);
        busy = false;
        icon.toResult(ok);
        glow.setBreathing(false);
        setLabel(status);
        view.setEnabled(false);
        view.setAlpha(1f);
        view.setContentDescription(idleLabel + ": " + status);
        Tap.outcome(view, ok);
        view.announceForAccessibility(status);
        view.postDelayed(restore, RESULT_HOLD_MS);
    }

    void idle() {
        view.removeCallbacks(restore);
        boolean changed = busy || !idleLabel.equals(labelText);
        busy = false;
        glow.setBreathing(false);
        if (changed) {
            icon.toIdle();
        }
        setLabel(idleLabel);
        view.setContentDescription(idleLabel);
        setAvailable(true);
    }

    boolean isIdle() {
        return !busy && idleLabel.equals(labelText);
    }

    /** Dimmed and not tappable while another command is being sent. */
    void setAvailable(boolean available) {
        view.setEnabled(available);
        view.setAlpha(available ? 1f : 0.45f);
    }
}
