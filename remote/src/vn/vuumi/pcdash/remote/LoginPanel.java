package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.ui.Tap;
import vn.vuumi.pcdash.i18n.L10n;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import vn.vuumi.pcdash.net.PcnetState;

public class LoginPanel extends LinearLayout {

    public interface Callback {
        void onLoginClicked(String authUrl);

        void onDiagnosticsClicked();
    }

    private final TextView statusView;
    private final Button loginBtn;
    private final TextView diagView;
    private final Callback callback;
    private String currentAuthUrl = "";
    private volatile boolean ready = false;

    public LoginPanel(Context context, final Callback callback) {
        super(context);
        this.callback = callback;
        setOrientation(VERTICAL);

        int pad = dpToPx(context, 16);
        setPadding(pad, pad, pad, pad);
        setBackgroundColor(Color.rgb(16, 20, 26));

        TextView header = new TextView(context);
        header.setText(L10n.t("1. Sign in to Tailscale", "1. Đăng nhập Tailscale"));
        header.setTextSize(16);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(Color.rgb(88, 166, 255));
        header.setPadding(0, 0, 0, dpToPx(context, 6));
        addView(header);

        TextView desc = new TextView(context);
        desc.setText(L10n.t("Sign in to Tailscale (the same account as the host phone)", "Đăng nhập tài khoản Tailscale (dùng chung với máy host)"));
        desc.setTextSize(12);
        desc.setTextColor(Color.rgb(139, 148, 158));
        desc.setPadding(0, 0, 0, dpToPx(context, 8));
        addView(desc);

        statusView = new TextView(context);
        statusView.setText(L10n.t("Checking Tailscale...", "Đang kiểm tra trạng thái Tailscale..."));
        statusView.setTextSize(13);
        statusView.setTextColor(Color.rgb(139, 148, 158));
        statusView.setPadding(0, 0, 0, dpToPx(context, 10));
        addView(statusView);

        loginBtn = new Button(context);
        loginBtn.setText(L10n.t("🔗 SIGN IN TO TAILSCALE", "🔗 ĐĂNG NHẬP TAILSCALE"));
        loginBtn.setTextColor(Color.rgb(255, 255, 255));
        loginBtn.setBackgroundColor(Color.rgb(26, 84, 176));
        loginBtn.setTextSize(14);
        loginBtn.setTypeface(Typeface.DEFAULT_BOLD);
        loginBtn.setVisibility(GONE);
        int bPadH = dpToPx(context, 16);
        int bPadV = dpToPx(context, 10);
        loginBtn.setPadding(bPadH, bPadV, bPadH, bPadV);
        Tap.wire(loginBtn, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callback != null && currentAuthUrl != null && !currentAuthUrl.isEmpty()) {
                    callback.onLoginClicked(currentAuthUrl);
                }
            }
        });
        addView(loginBtn);

        Button diagBtn = new Button(context);
        diagBtn.setText(L10n.t("Connection diagnostics", "Chẩn đoán kết nối"));
        diagBtn.setTextColor(Color.rgb(139, 148, 158));
        diagBtn.setBackgroundColor(Color.TRANSPARENT);
        diagBtn.setTextSize(12);
        Tap.wire(diagBtn, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callback != null) {
                    callback.onDiagnosticsClicked();
                }
            }
        });
        addView(diagBtn);

        diagView = new TextView(context);
        diagView.setTextSize(11);
        diagView.setTextColor(Color.rgb(139, 148, 158));
        diagView.setVisibility(GONE);
        addView(diagView);
    }

    public void showDiagnostics(String text) {
        diagView.setText(text);
        diagView.setVisibility(VISIBLE);
    }

    public void updateState(PcnetState state) {
        if (state == null || state.isStopped()) {
            ready = false;
            currentAuthUrl = "";
            statusView.setText(L10n.t("Tailscale stopped", "Tailscale đã dừng"));
            statusView.setTextColor(Color.rgb(139, 148, 158));
            loginBtn.setVisibility(GONE);
            return;
        }

        if (state.isStarting()) {
            ready = false;
            currentAuthUrl = "";
            statusView.setText(L10n.t("Starting Tailscale...", "Đang khởi động Tailscale..."));
            statusView.setTextColor(Color.rgb(139, 148, 158));
            loginBtn.setVisibility(GONE);
            return;
        }

        if (state.isNeedsLogin()) {
            ready = false;
            currentAuthUrl = state.authUrl != null ? state.authUrl : "";
            statusView.setText(L10n.t("Tailscale sign-in needed. Tap the button below to open the browser.", "Cần đăng nhập Tailscale. Bấm nút bên dưới để mở trình duyệt."));
            statusView.setTextColor(Color.rgb(210, 153, 34));
            loginBtn.setVisibility(VISIBLE);
            return;
        }

        if (state.isRunning()) {
            ready = true;
            currentAuthUrl = "";
            String ip = "";
            if (state.tailnetIps != null && !state.tailnetIps.isEmpty()) {
                ip = state.tailnetIps.get(0);
            }
            statusView.setText(L10n.t("✓ Signed in to Tailscale", "✓ Đã đăng nhập Tailscale") + (!ip.isEmpty() ? " (" + ip + ")" : ""));
            statusView.setTextColor(Color.rgb(46, 160, 67));
            loginBtn.setVisibility(GONE);
            return;
        }

        if (state.isError()) {
            ready = false;
            currentAuthUrl = "";
            String err = state.error != null && !state.error.isEmpty() ? state.error : L10n.t("Unknown", "Không xác định");
            statusView.setText(L10n.t("Tailscale connection error: ", "Lỗi kết nối Tailscale: ") + err);
            statusView.setTextColor(Color.rgb(248, 81, 73));
            loginBtn.setVisibility(GONE);
            return;
        }

        ready = false;
        loginBtn.setVisibility(GONE);
    }

    public boolean isTailscaleReady() {
        return ready;
    }

    public String getCurrentAuthUrl() {
        return currentAuthUrl;
    }

    private static int dpToPx(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
