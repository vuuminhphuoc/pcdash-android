package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.i18n.L10n;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Build;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.view.Gravity;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;

public class BottomNavBar extends LinearLayout {

    private final TabNavigator.Listener listener;
    private final ImageView[] icons = new ImageView[MainTab.values().length];
    private final TextView[] labels = new TextView[MainTab.values().length];
    private final LinearLayout row;
    private final LinearLayout[] items = new LinearLayout[MainTab.values().length];
    private final GlassDrawable glass;
    private final View pill;
    private final OvershootInterpolator overshoot = new OvershootInterpolator(0.9f);
    private ValueAnimator pillAnimator;
    private int selectedIndex = -1;
    private float pillX;
    private static final int MIN_SIDE_DP = 24;
    private static final int MIN_BOTTOM_DP = 12;
    private static final int ROW_VERTICAL_DP = 6;

    // White vector glyphs in res/drawable (looked up by name: this build has no R class), tinted by the selection.
    private static final String[] TAB_ICONS = new String[]{"ic_tab_control", "ic_tab_monitor", "ic_tab_quota", "ic_tab_log", "ic_tab_more"};
    private static String[] tabLabels() {
        return new String[]{
                L10n.t("Control", "Điều khiển"),
                L10n.t("Monitor", "Giám sát"),
                "Quota",
                L10n.t("Log", "Nhật ký"),
                L10n.t("More", "Thêm")
        };
    }

    public BottomNavBar(Context context, final TabNavigator.Listener listener) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        glass = GlassKit.surface(context, 20, 0);
        setBackground(glass);

        row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        applyInsets(0, 0);

        MainTab[] tabs = MainTab.values();
        String[] labelTexts = tabLabels();
        for (int i = 0; i < tabs.length; i++) {
            final MainTab tab = tabs[i];
            LinearLayout itemLayout = new LinearLayout(context);
            itemLayout.setOrientation(VERTICAL);
            itemLayout.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f);
            itemLp.setMargins(dpToPx(2), 0, dpToPx(2), 0);
            itemLayout.setLayoutParams(itemLp);
            itemLayout.setPadding(0, dpToPx(4), 0, dpToPx(4));
            items[i] = itemLayout;

            ImageView iconTv = new ImageView(context);
            iconTv.setImageResource(context.getResources().getIdentifier(TAB_ICONS[i], "drawable", context.getPackageName()));
            iconTv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            itemLayout.addView(iconTv, new LinearLayout.LayoutParams(dpToPx(22), dpToPx(22)));

            TextView labelTv = new TextView(context);
            labelTv.setText(labelTexts[i]);
            labelTv.setTextSize(10);
            labelTv.setGravity(Gravity.CENTER);
            labelTv.setSingleLine(true);
            labelTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labelTv.setPadding(0, dpToPx(2), 0, 0);
            itemLayout.addView(labelTv);

            icons[i] = iconTv;
            labels[i] = labelTv;

            itemLayout.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (BottomNavBar.this.listener != null) {
                        BottomNavBar.this.listener.onTabSelected(tab);
                    }
                }
            });

            row.addView(itemLayout);
        }

        // The glass pill sits behind the row and slides between tabs instead of switching instantly.
        pill = new View(context);
        pill.setBackground(GlassKit.selectedPill(context, Color.rgb(88, 166, 255)));
        pill.setVisibility(INVISIBLE);
        FrameLayout stack = new FrameLayout(context);
        stack.addView(pill, new FrameLayout.LayoutParams(0, 0));
        stack.addView(row, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        addView(stack, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        row.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (pillAnimator == null && selectedIndex >= 0) {
                    snapPill(selectedIndex);
                }
            }
        });
        setSelected(MainTab.CONTROL);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        int bottomInset = 0;
        int cornerRadius = 0;
        if (insets != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                // Only the navigation bar: while the keyboard is open the activity root already sits
                // above it (SystemBarInsets.IME), and the keyboard covers the navigation bar.
                bottomInset = insets.isVisible(WindowInsets.Type.ime())
                        ? 0 : insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            if (Build.VERSION.SDK_INT >= 31) {
                cornerRadius = Math.max(cornerOf(insets, RoundedCorner.POSITION_BOTTOM_LEFT),
                        cornerOf(insets, RoundedCorner.POSITION_BOTTOM_RIGHT));
            }
        }
        applyInsets(bottomInset, cornerRadius);
        return super.onApplyWindowInsets(insets);
    }

    private static int cornerOf(WindowInsets insets, int position) {
        RoundedCorner c = insets.getRoundedCorner(position);
        return c != null ? c.getRadius() : 0;
    }

    // Góc bo của màn hình che nội dung sát mép: đệm hai bên theo bán kính góc, đệm dưới theo thanh hệ thống.
    private void applyInsets(int bottomInsetPx, int cornerRadiusPx) {
        int side = Math.max(dpToPx(MIN_SIDE_DP), (int) (cornerRadiusPx * 0.6f));
        int bottom = Math.max(dpToPx(MIN_BOTTOM_DP), bottomInsetPx) + dpToPx(ROW_VERTICAL_DP);
        row.setPadding(side, dpToPx(ROW_VERTICAL_DP), side, bottom);
    }

    public void setSelected(MainTab selectedTab) {
        if (selectedTab == null || selectedTab == MainTab.MORE) {
            return;
        }
        MainTab[] tabs = MainTab.values();
        for (int i = 0; i < tabs.length; i++) {
            boolean isSelected = (tabs[i] == selectedTab);
            int color = isSelected ? Color.rgb(88, 166, 255) : Color.rgb(139, 148, 158);
            if (icons[i] != null) {
                icons[i].setColorFilter(color);
            }
            if (labels[i] != null) {
                labels[i].setTextColor(color);
                labels[i].setTypeface(isSelected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
        }
        movePillTo(selectedTab.ordinal());
    }

    private void movePillTo(int index) {
        if (index < 0 || index >= items.length || items[index] == null) {
            return;
        }
        boolean firstPlacement = selectedIndex < 0 || pill.getVisibility() != VISIBLE;
        int from = selectedIndex;
        selectedIndex = index;
        LinearLayout item = items[index];
        if (item.getWidth() == 0) {
            return; // not laid out yet, the layout listener will snap it
        }
        if (firstPlacement || from == index || from < 0 || items[from] == null) {
            snapPill(index);
            return;
        }
        final float x0 = pillX;
        final float x1 = row.getLeft() + item.getLeft();
        final float dist = Math.abs(x1 - x0);
        final float itemW = item.getWidth();
        if (pillAnimator != null) {
            pillAnimator.cancel();
        }
        pillAnimator = ValueAnimator.ofFloat(0f, 1f);
        pillAnimator.setDuration(420);
        pillAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float f = (Float) a.getAnimatedValue();
                float eased = overshoot.getInterpolation(f);
                pillX = x0 + (x1 - x0) * eased;
                pill.setTranslationX(pillX);
                // liquid stretch: widest mid-flight, then settles back
                float bump = (float) Math.sin(Math.PI * f);
                float reach = Math.min(1f, dist / (itemW * 2f));
                pill.setScaleX(1f + 0.4f * bump * reach);
                pill.setScaleY(1f - 0.12f * bump * reach);
            }
        });
        pillAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                pillAnimator = null;
                pill.setScaleX(1f);
                pill.setScaleY(1f);
                if (selectedIndex >= 0) {
                    snapPill(selectedIndex);
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                pillAnimator = null;
            }
        });
        pillAnimator.start();
    }

    private void snapPill(int index) {
        if (index < 0 || index >= items.length || items[index] == null || items[index].getWidth() == 0) {
            return;
        }
        LinearLayout item = items[index];
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pill.getLayoutParams();
        int h = item.getHeight();
        if (lp.width != item.getWidth() || lp.height != h) {
            lp.width = item.getWidth();
            lp.height = h;
            pill.setLayoutParams(lp);
        }
        pillX = row.getLeft() + item.getLeft();
        pill.setTranslationX(pillX);
        pill.setTranslationY(row.getTop() + item.getTop());
        pill.setVisibility(VISIBLE);
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
