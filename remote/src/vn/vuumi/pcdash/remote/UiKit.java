package vn.vuumi.pcdash.remote;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.TextView;

/** Small helpers shared by the tab views so cards and buttons look the same everywhere. */
final class UiKit {

    static final int BG = Color.rgb(0, 0, 0);
    static final int CARD = Color.rgb(16, 20, 26);
    static final int BORDER = Color.rgb(36, 42, 50);
    static final int TEXT = Color.rgb(230, 237, 243);
    static final int MUTED = Color.rgb(139, 148, 158);
    static final int ACCENT = Color.rgb(88, 166, 255);
    static final int OK = Color.rgb(63, 185, 80);
    static final int WARN = Color.rgb(210, 153, 34);
    static final int ERROR = Color.rgb(248, 81, 73);

    private UiKit() {}

    static int dp(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    static Drawable roundedFill(Context ctx, int fill, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(ctx, radiusDp));
        return d;
    }

    static Drawable roundedOutline(Context ctx, int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setStroke(dp(ctx, 1), stroke);
        d.setCornerRadius(dp(ctx, radiusDp));
        return d;
    }

    static Drawable withRipple(Drawable base) {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), base, null);
    }

    static void styleCard(View card) {
        Context ctx = card.getContext();
        card.setBackground(roundedOutline(ctx, CARD, BORDER, 12));
    }

    static TextView sectionLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(MUTED);
        tv.setLetterSpacing(0.04f);
        tv.setPadding(dp(ctx, 4), dp(ctx, 6), 0, dp(ctx, 8));
        return tv;
    }
}
