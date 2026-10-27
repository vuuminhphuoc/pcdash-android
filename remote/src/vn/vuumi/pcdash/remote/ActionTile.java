package vn.vuumi.pcdash.remote;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import vn.vuumi.pcdash.ui.Tap;

/**
 * Icon + label tile for a power action that shows, on itself, what its command is doing: a spinner and
 * a short status while it is sent and while the PC changes state, then a tick or a cross for a moment.
 */
final class ActionTile {
    static final long RESULT_HOLD_MS = 2500L;

    final LinearLayout view;
    private final ImageView icon;
    private final ProgressBar spinner;
    private final TextView label;
    private final String idleLabel;
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
        Drawable bg = stroke != 0
                ? UiKit.roundedOutline(ctx, fill, stroke, 12)
                : UiKit.roundedFill(ctx, fill, 12);
        view.setBackground(UiKit.withRipple(bg));
        view.setMinimumHeight(UiKit.dp(ctx, heightDp));
        int padH = UiKit.dp(ctx, 12);
        view.setPadding(padH, UiKit.dp(ctx, 8), padH, UiKit.dp(ctx, 8));

        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(UiKit.dp(ctx, 22), UiKit.dp(ctx, 22));
        iconLp.rightMargin = UiKit.dp(ctx, 10);
        icon = new ImageView(ctx);
        icon.setImageDrawable(new IconDrawable(iconType, contentColor));
        icon.setLayoutParams(iconLp);
        view.addView(icon);

        spinner = new ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(contentColor));
        spinner.setLayoutParams(new LinearLayout.LayoutParams(iconLp));
        spinner.setVisibility(View.GONE);
        spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.addView(spinner);

        label = new TextView(ctx);
        label.setText(text);
        label.setTextColor(contentColor);
        label.setTextSize(15);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        view.addView(label);

        view.setContentDescription(text);
        Tap.wire(view, listener);
    }

    /** Command in flight or PC changing state: spinner, short status, taps blocked. */
    void busy(String status) {
        view.removeCallbacks(restore);
        icon.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        label.setText(status);
        view.setEnabled(false);
        view.setAlpha(1f);
        view.setContentDescription(idleLabel + ": " + status);
        view.announceForAccessibility(status);
    }

    /** How it went, for a moment; then the tile is back to normal. */
    void result(boolean ok, String status) {
        view.removeCallbacks(restore);
        spinner.setVisibility(View.GONE);
        icon.setVisibility(View.VISIBLE);
        label.setText((ok ? "✓ " : "✕ ") + status);
        view.setEnabled(false);
        view.setAlpha(1f);
        view.setContentDescription(idleLabel + ": " + status);
        Tap.outcome(view, ok);
        view.announceForAccessibility(status);
        view.postDelayed(restore, RESULT_HOLD_MS);
    }

    void idle() {
        view.removeCallbacks(restore);
        spinner.setVisibility(View.GONE);
        icon.setVisibility(View.VISIBLE);
        label.setText(idleLabel);
        view.setContentDescription(idleLabel);
        setAvailable(true);
    }

    boolean isIdle() {
        return idleLabel.contentEquals(label.getText()) && spinner.getVisibility() != View.VISIBLE;
    }

    /** Dimmed and not tappable while another command is being sent. */
    void setAvailable(boolean available) {
        view.setEnabled(available);
        view.setAlpha(available ? 1f : 0.45f);
    }
}
