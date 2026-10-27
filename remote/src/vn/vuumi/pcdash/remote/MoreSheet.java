package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.i18n.L10n;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MoreSheet {

    public interface Callback {
        void onRequireDangerousAuthChanged(boolean required);
        void onCheckUpdate();
        void onForgetHost();
        void onDiagnosticsRequested(DiagnosticsCallback callback);
        default void onReloginTailscale() {}
    }

    public interface DiagnosticsCallback {
        void onDiagnostics(String formatted);
    }

    public static Dialog show(final Context context, final RemoteConfigStore store,
                              final String versionName, final Callback callback) {
        final Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView scroll = new ScrollView(context);
        final GlassDrawable glass = GlassKit.surface(context, 24, 0);
        scroll.setBackground(glass);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(context, 20);
        content.setPadding(pad, pad, pad, pad);
        scroll.addView(content);

        // Header
        TextView title = new TextView(context);
        title.setText(L10n.t("Settings & more", "Cài đặt & Thêm"));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(230, 237, 243));
        content.addView(title);

        if (store != null) {
            String hostname = store.getCachedHostname();
            if (hostname == null || hostname.isEmpty()) {
                hostname = store.getOrCreateHostname();
            }
            TextView hostInfo = new TextView(context);
            hostInfo.setText(L10n.t("Tailscale device: ", "Thiết bị Tailscale: ") + hostname);
            hostInfo.setTextSize(13);
            hostInfo.setTextColor(Color.rgb(139, 148, 158));
            hostInfo.setPadding(0, dpToPx(context, 4), 0, dpToPx(context, 8));
            content.addView(hostInfo);
        }

        addSpacing(content, dpToPx(context, 12));
        addDivider(content, context);
        addSpacing(content, dpToPx(context, 12));

        // 1. Cài đặt xác thực
        CheckBox authSwitch = new CheckBox(context);
        authSwitch.setText(L10n.t("Require unlock for Shut down/Hibernate", "Yêu cầu xác thực cho Tắt máy/Ngủ đông"));
        authSwitch.setTextColor(Color.rgb(201, 209, 217));
        authSwitch.setTextSize(13);
        boolean currentRequireAuth = true;
        if (store != null) {
            RemoteConfigStore.RemoteConfig cached = store.getCached();
            if (cached != null) {
                currentRequireAuth = cached.requireDangerousAuth;
            } else {
                try {
                    RemoteConfigStore.RemoteConfig cfg = store.load();
                    if (cfg != null) currentRequireAuth = cfg.requireDangerousAuth;
                } catch (Exception ignored) {}
            }
        }
        authSwitch.setChecked(currentRequireAuth);
        authSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (callback != null) {
                    callback.onRequireDangerousAuthChanged(isChecked);
                }
            }
        });
        content.addView(authSwitch);

        addSpacing(content, dpToPx(context, 8));

        // Đăng nhập lại Tailscale
        Button reloginBtn = new Button(context);
        reloginBtn.setText(L10n.t("Sign in to Tailscale again", "Đăng nhập lại Tailscale"));
        reloginBtn.setTextColor(Color.rgb(88, 166, 255));
        reloginBtn.setBackgroundColor(Color.rgb(33, 38, 45));
        reloginBtn.setTextSize(13);
        reloginBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (callback != null) {
                    callback.onReloginTailscale();
                }
            }
        });
        content.addView(reloginBtn);

        addSpacing(content, dpToPx(context, 12));

        // 2. Chẩn đoán
        final Button diagBtn = new Button(context);
        diagBtn.setText(L10n.t("Tailscale connection diagnostics", "Chẩn đoán kết nối Tailscale"));
        diagBtn.setTextColor(Color.rgb(88, 166, 255));
        diagBtn.setBackgroundColor(Color.rgb(33, 38, 45));
        diagBtn.setTextSize(13);
        content.addView(diagBtn);

        final LinearLayout diagContainer = new LinearLayout(context);
        diagContainer.setOrientation(LinearLayout.VERTICAL);
        diagContainer.setVisibility(View.GONE);
        diagContainer.setPadding(0, dpToPx(context, 8), 0, dpToPx(context, 8));

        final TextView diagView = new TextView(context);
        diagView.setTextIsSelectable(true);
        diagView.setTypeface(Typeface.MONOSPACE);
        diagView.setTextSize(11);
        diagView.setTextColor(Color.rgb(201, 209, 217));
        diagView.setBackgroundColor(Color.rgb(0, 0, 0));
        int diagPad = dpToPx(context, 12);
        diagView.setPadding(diagPad, diagPad, diagPad, diagPad);
        diagView.setText(L10n.t("Loading diagnostics...", "Đang tải dữ liệu chẩn đoán..."));
        diagContainer.addView(diagView);
        content.addView(diagContainer);

        diagBtn.setOnClickListener(new View.OnClickListener() {
            private boolean isOpen = false;
            @Override
            public void onClick(View v) {
                isOpen = !isOpen;
                if (isOpen) {
                    diagContainer.setVisibility(View.VISIBLE);
                    diagBtn.setText(L10n.t("Close diagnostics", "Đóng chẩn đoán"));
                    diagView.setText(L10n.t("Loading diagnostics...", "Đang tải dữ liệu chẩn đoán..."));
                    if (callback != null) {
                        callback.onDiagnosticsRequested(new DiagnosticsCallback() {
                            @Override
                            public void onDiagnostics(String formatted) {
                                if (diagView != null) {
                                    diagView.setText(formatted);
                                }
                            }
                        });
                    }
                } else {
                    diagContainer.setVisibility(View.GONE);
                    diagBtn.setText(L10n.t("Tailscale connection diagnostics", "Chẩn đoán kết nối Tailscale"));
                }
            }
        });

        addSpacing(content, dpToPx(context, 12));

        // 3. Kiểm tra cập nhật
        Button checkUpdateBtn = new Button(context);
        checkUpdateBtn.setText(L10n.t("Check for updates", "Kiểm tra cập nhật"));
        checkUpdateBtn.setTextColor(Color.rgb(88, 166, 255));
        checkUpdateBtn.setBackgroundColor(Color.rgb(33, 38, 45));
        checkUpdateBtn.setTextSize(13);
        checkUpdateBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (callback != null) {
                    callback.onCheckUpdate();
                }
            }
        });
        content.addView(checkUpdateBtn);

        addSpacing(content, dpToPx(context, 12));
        addDivider(content, context);
        addSpacing(content, dpToPx(context, 12));

        // 4. Quên host này
        Button forgetBtn = new Button(context);
        forgetBtn.setText(L10n.t("Forget this host", "Quên host này"));
        forgetBtn.setTextColor(Color.rgb(248, 81, 73));
        forgetBtn.setBackgroundColor(Color.TRANSPARENT);
        forgetBtn.setTextSize(14);
        forgetBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (callback != null) {
                    callback.onForgetHost();
                }
            }
        });
        content.addView(forgetBtn);

        // 5. Phiên bản
        TextView versionFooter = new TextView(context);
        versionFooter.setText(L10n.t("Version ", "Phiên bản ") + (versionName != null ? versionName : ""));
        versionFooter.setTextSize(12);
        versionFooter.setTextColor(Color.rgb(110, 118, 129));
        versionFooter.setGravity(Gravity.CENTER);
        versionFooter.setPadding(0, dpToPx(context, 8), 0, dpToPx(context, 4));
        content.addView(versionFooter);

        // 6. Đóng
        Button closeBtn = new Button(context);
        closeBtn.setText(L10n.t("Close", "Đóng"));
        closeBtn.setTextColor(Color.rgb(201, 209, 217));
        closeBtn.setBackgroundColor(Color.rgb(33, 38, 45));
        closeBtn.setTextSize(13);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        content.addView(closeBtn);

        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        dialog.setCanceledOnTouchOutside(true);
        GlassKit.applyDialogBlur(dialog, glass);
        dialog.show();
        return dialog;
    }

    private static void addDivider(LinearLayout layout, Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(Color.rgb(48, 54, 61));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(context, 1)));
        layout.addView(divider);
    }

    private static void addSpacing(LinearLayout layout, int heightPx) {
        View spacer = new View(layout.getContext());
        spacer.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx));
        layout.addView(spacer);
    }

    private static int dpToPx(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
