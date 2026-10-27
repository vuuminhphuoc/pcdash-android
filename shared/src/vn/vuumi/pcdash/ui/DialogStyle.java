package vn.vuumi.pcdash.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;

/**
 * Yes/no dialogs that stand out from the pure-black screens: a lighter surface than the page, a visible
 * border, light text, and a blue confirm button (red when the action is destructive). Without this the
 * apps' default light dialog theme drew dark text, and the surface was almost the page's black.
 */
public final class DialogStyle {
    /** Raised above the black page (GitHub dark "overlay" tone). */
    public static final int SURFACE = Color.rgb(33, 38, 45);
    public static final int BORDER = Color.rgb(72, 80, 90);
    public static final int TITLE = Color.rgb(230, 237, 243);
    public static final int BODY = Color.rgb(201, 209, 217);
    public static final int ACCENT = Color.rgb(88, 166, 255);
    public static final int DANGER = Color.rgb(248, 81, 73);
    public static final int MUTED = Color.rgb(139, 148, 158);

    private DialogStyle() {}

    /** Builder on the dark dialog theme, so every default text color is already light. */
    public static AlertDialog.Builder builder(Context context) {
        return new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    /** Creates, styles with {@link #applySolid} and shows the dialog. */
    public static AlertDialog show(AlertDialog.Builder builder, boolean destructive) {
        AlertDialog dialog = builder.create();
        applySolid(dialog, destructive);
        dialog.show();
        return dialog;
    }

    /** Solid surface with a border, then {@link #colorText}. Call before show(). */
    public static void applySolid(AlertDialog dialog, boolean destructive) {
        Window window = dialog.getWindow();
        if (window != null) {
            Context ctx = dialog.getContext();
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(SURFACE);
            bg.setStroke(dp(ctx, 1), BORDER);
            bg.setCornerRadius(dp(ctx, 16));
            window.setBackgroundDrawable(new InsetDrawable(bg, dp(ctx, 16)));
        }
        colorText(dialog, destructive);
    }

    /** Title, message and button colors, applied once the dialog's views exist. Call before show(). */
    public static void colorText(final AlertDialog dialog, final boolean destructive) {
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                int titleId = dialog.getContext().getResources().getIdentifier("alertTitle", "id", "android");
                TextView title = titleId != 0 ? (TextView) dialog.findViewById(titleId) : null;
                if (title != null) {
                    title.setTextColor(TITLE);
                }
                TextView message = (TextView) dialog.findViewById(android.R.id.message);
                if (message != null) {
                    message.setTextColor(BODY);
                }
                color(dialog.getButton(DialogInterface.BUTTON_POSITIVE), destructive ? DANGER : ACCENT);
                color(dialog.getButton(DialogInterface.BUTTON_NEGATIVE), BODY);
                color(dialog.getButton(DialogInterface.BUTTON_NEUTRAL), MUTED);
            }
        });
    }

    private static void color(Button button, int color) {
        if (button != null) {
            button.setTextColor(color);
        }
    }

    private static int dp(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
