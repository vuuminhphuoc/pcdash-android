package vn.vuumi.pcdash.remote;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers shared by the tab views so cards and buttons look the same everywhere. */
final class UiKit {

    static final int BG = Color.rgb(0, 0, 0);
    static final int CARD = Color.rgb(16, 20, 26);
    static final int BORDER = Color.rgb(36, 42, 50);
    static final int TEXT = Color.rgb(230, 237, 243);
    static final int MUTED = Color.rgb(139, 148, 158);

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

    /** Icon + label tile used for the power actions. */
    static LinearLayout actionTile(Context ctx, IconDrawable.Type icon, String label,
                                   int fill, int stroke, int contentColor, int heightDp,
                                   View.OnClickListener listener) {
        LinearLayout tile = new LinearLayout(ctx);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER);
        tile.setClickable(true);
        tile.setFocusable(true);
        Drawable bg = stroke != 0
                ? roundedOutline(ctx, fill, stroke, 12)
                : roundedFill(ctx, fill, 12);
        tile.setBackground(withRipple(bg));
        tile.setMinimumHeight(dp(ctx, heightDp));
        int padH = dp(ctx, 12);
        tile.setPadding(padH, dp(ctx, 8), padH, dp(ctx, 8));

        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(new IconDrawable(icon, contentColor));
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22));
        ivLp.rightMargin = dp(ctx, 10);
        iv.setLayoutParams(ivLp);
        tile.addView(iv);

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(contentColor);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tile.addView(tv);

        tile.setContentDescription(label);
        tile.setOnClickListener(listener);
        return tile;
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
