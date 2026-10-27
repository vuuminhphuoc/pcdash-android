package vn.vuumi.pcdash.ui;

import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

/**
 * Pads a view by the status bar, navigation bar and display cutout insets it receives. With target
 * SDK 35+ on Android 15+ windows draw behind the system bars, so content needs this to stay clear of
 * them; on older phones the insets arrive already applied by the window and nothing changes.
 */
public final class SystemBarInsets {
    public static final int LEFT = 1;
    public static final int TOP = 2;
    public static final int RIGHT = 4;
    public static final int BOTTOM = 8;
    public static final int ALL = LEFT | TOP | RIGHT | BOTTOM;

    private SystemBarInsets() {}

    /** Adds the insets on the given sides to the view's own padding. Insets still reach children. */
    public static void pad(View view, final int sides) {
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        if (view instanceof ViewGroup) {
            // Scrolling content may pass under the bars but comes to rest inside them.
            ((ViewGroup) view).setClipToPadding(false);
        }
        view.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int l, t, r, b;
                if (Build.VERSION.SDK_INT >= 30) {
                    Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    l = bars.left;
                    t = bars.top;
                    r = bars.right;
                    b = bars.bottom;
                } else {
                    l = insets.getSystemWindowInsetLeft();
                    t = insets.getSystemWindowInsetTop();
                    r = insets.getSystemWindowInsetRight();
                    b = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(
                        left + ((sides & LEFT) != 0 ? l : 0),
                        top + ((sides & TOP) != 0 ? t : 0),
                        right + ((sides & RIGHT) != 0 ? r : 0),
                        bottom + ((sides & BOTTOM) != 0 ? b : 0));
                return insets;
            }
        });
        view.requestApplyInsets();
    }
}
