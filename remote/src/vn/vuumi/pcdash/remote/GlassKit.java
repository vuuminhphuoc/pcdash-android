package vn.vuumi.pcdash.remote;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.os.PowerManager;
import android.view.Window;
import android.view.WindowManager;
import java.util.function.Consumer;

/**
 * Liquid-glass helpers. Real window blur only runs on API 31+ when the system allows it; everywhere else
 * (and in Battery Saver) the same surface falls back to an opaque fill, so nothing becomes unreadable.
 */
final class GlassKit {

    static final int GLASS_FILL = Color.argb(214, 16, 20, 26);
    static final int GLASS_SOLID_ALPHA = 248;
    private static final int BLUR_RADIUS_PX = 28;

    private GlassKit() {}

    /** True when the system will really blur what is behind a window. */
    static boolean blurAvailable(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) {
            return false;
        }
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isPowerSaveMode()) {
                return false;
            }
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            return wm != null && wm.isCrossWindowBlurEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    static GlassDrawable surface(Context ctx, int topRadiusDp, int bottomRadiusDp) {
        return new GlassDrawable(GLASS_FILL, GLASS_SOLID_ALPHA,
                UiKit.dp(ctx, topRadiusDp), UiKit.dp(ctx, bottomRadiusDp), Math.max(1f, UiKit.dp(ctx, 1)));
    }

    /** Soft tinted pill that marks the selected tab (state color, not decoration). */
    static Drawable selectedPill(Context ctx, int rgb) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.argb(34, Color.red(rgb), Color.green(rgb), Color.blue(rgb)));
        d.setStroke(UiKit.dp(ctx, 1), Color.argb(70, Color.red(rgb), Color.green(rgb), Color.blue(rgb)));
        d.setCornerRadius(UiKit.dp(ctx, 16));
        return d;
    }

    /** Card with a faint state tint and a matching rim (update available, error). */
    static Drawable tintedCard(Context ctx, int fill, int stroke) {
        return UiKit.roundedOutline(ctx, fill, stroke, 12);
    }

    /**
     * Blur behind a dialog window and keep the glass surface in sync with the system's blur switch.
     * Call after {@code dialog.show()} is not required; call before show.
     */
    static void applyDialogBlur(final Dialog dialog, final GlassDrawable surface) {
        final Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        final Context ctx = dialog.getContext();
        window.setDimAmount(0.35f);
        applyBlurState(window, surface, blurAvailable(ctx));
        if (Build.VERSION.SDK_INT < 31) {
            return;
        }
        final WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return;
        }
        final Consumer<Boolean> listener = new Consumer<Boolean>() {
            @Override
            public void accept(Boolean enabled) {
                applyBlurState(window, surface, enabled != null && enabled && blurAvailable(ctx));
            }
        };
        try {
            wm.addCrossWindowBlurEnabledListener(ctx.getMainExecutor(), listener);
            dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(android.content.DialogInterface d) {
                    try {
                        wm.removeCrossWindowBlurEnabledListener(listener);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void applyBlurState(Window window, GlassDrawable surface, boolean blur) {
        surface.setSolid(!blur);
        if (Build.VERSION.SDK_INT >= 31) {
            WindowManager.LayoutParams lp = window.getAttributes();
            if (blur) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                lp.setBlurBehindRadius(BLUR_RADIUS_PX);
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                lp.setBlurBehindRadius(0);
            }
            window.setAttributes(lp);
        }
    }

    /** Glass background for a floating AlertDialog: rounded all around, inset from the screen edge. */
    static void styleAlert(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        Context ctx = dialog.getContext();
        GlassDrawable surface = surface(ctx, 20, 20);
        window.setBackgroundDrawable(new InsetDrawable(surface, UiKit.dp(ctx, 6)));
        applyDialogBlur(dialog, surface);
    }

    static Drawable transparent() {
        return new ColorDrawable(Color.TRANSPARENT);
    }
}
