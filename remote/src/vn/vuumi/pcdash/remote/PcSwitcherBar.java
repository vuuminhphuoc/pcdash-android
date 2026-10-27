package vn.vuumi.pcdash.remote;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import vn.vuumi.pcdash.i18n.L10n;
import vn.vuumi.pcdash.ui.Tap;

/**
 * Chips across the top when the home phone controls more than one PC: one per PC, with a dot that is
 * green (pinging) while that PC is on. Tapping a chip makes every tab show and act on that PC.
 */
final class PcSwitcherBar extends HorizontalScrollView {

    interface Listener {
        void onPcSelected(String id);
    }

    private final LinearLayout row;
    private final Listener listener;
    private String shownKey = "";

    PcSwitcherBar(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setHorizontalScrollBarEnabled(false);
        setClipToPadding(false);
        int pad = UiKit.dp(context, 16);
        setPadding(pad, UiKit.dp(context, 10), pad, UiKit.dp(context, 4));
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row);
    }

    /** Draws the chips again only when the PCs, their state or the choice changed. */
    void show(PcList list, String selectedId) {
        StringBuilder key = new StringBuilder(String.valueOf(selectedId));
        for (PcList.Pc p : list.pcs) {
            key.append('|').append(p.id).append(':').append(p.name).append(':').append(p.online);
        }
        if (key.toString().equals(shownKey)) return;
        shownKey = key.toString();
        row.removeAllViews();
        Context ctx = getContext();
        for (final PcList.Pc p : list.pcs) {
            boolean on = p.id.equals(selectedId);
            LinearLayout chip = new LinearLayout(ctx);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(UiKit.dp(ctx, 12), UiKit.dp(ctx, 8), UiKit.dp(ctx, 16), UiKit.dp(ctx, 8));
            chip.setBackground(on
                    ? GlassKit.selectedPill(ctx, UiKit.ACCENT)
                    : UiKit.roundedOutline(ctx, UiKit.CARD, UiKit.BORDER, 16));

            StatusDot dot = new StatusDot(ctx);
            dot.show(p.online ? UiKit.OK : UiKit.ERROR, p.online ? 2200 : 0);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(UiKit.dp(ctx, 12), UiKit.dp(ctx, 12));
            dotLp.rightMargin = UiKit.dp(ctx, 8);
            chip.addView(dot, dotLp);

            TextView name = new TextView(ctx);
            name.setText(p.name);
            name.setTextSize(14);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(on ? Color.WHITE : UiKit.TEXT);
            name.setSingleLine(true);
            chip.addView(name);

            chip.setContentDescription(p.name + ", " + (p.online ? L10n.t("on", "đang bật") : L10n.t("off or unreachable", "tắt hoặc mất kết nối")));
            chip.setSelected(on);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = UiKit.dp(ctx, 8);
            chip.setLayoutParams(lp);
            Tap.wire(chip, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    listener.onPcSelected(p.id);
                }
            });
            row.addView(chip);
        }
    }
}
