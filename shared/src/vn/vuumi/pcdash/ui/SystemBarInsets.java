package vn.vuumi.pcdash.ui;

import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * Pads a view by the status bar, navigation bar, display cutout and keyboard insets it receives.
 * With target SDK 35+ on Android 15+ windows draw behind the system bars and adjustResize no longer
 * shrinks the window for the keyboard, so content needs this to stay visible; on older phones the
 * window already applies these insets and the view receives none.
 */
public final class SystemBarInsets {
    public static final int LEFT = 1;
    public static final int TOP = 2;
    public static final int RIGHT = 4;
    public static final int BOTTOM = 8;
    /** Bottom padding grows to the keyboard height while it is open. */
    public static final int IME = 16;
    public static final int ALL = LEFT | TOP | RIGHT | BOTTOM | IME;

    private SystemBarInsets() {}

    /**
     * Adds the insets on the given sides to the view's own padding. Insets still reach children.
     * Pad a container rather than a ScrollView itself, so the ScrollView shrinks when the keyboard
     * opens and keeps the focused field in view.
     */
    public static void pad(View view, final int sides) {
        final int left = view.getPaddingLeft();
        final int top = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int bottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int l, t, r, b;
                int ime = 0;
                if (Build.VERSION.SDK_INT >= 30) {
                    Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    l = bars.left;
                    t = bars.top;
                    r = bars.right;
                    b = bars.bottom;
                    ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
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
                        bottom + Math.max((sides & BOTTOM) != 0 ? b : 0, (sides & IME) != 0 ? ime : 0));
                return insets;
            }
        });
        view.requestApplyInsets();
    }
}
