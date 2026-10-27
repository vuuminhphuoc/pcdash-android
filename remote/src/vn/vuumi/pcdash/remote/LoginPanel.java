package vn.vuumi.pcdash.remote;

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
        header.setText("1. Đăng nhập Tailscale");
        header.setTextSize(16);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(Color.rgb(88, 166, 255));
        header.setPadding(0, 0, 0, dpToPx(context, 6));
        addView(header);

        TextView desc = new TextView(context);
        desc.setText("Đăng nhập tài khoản Tailscale (dùng chung với máy host)");
        desc.setTextSize(12);
        desc.setTextColor(Color.rgb(139, 148, 158));
        desc.setPadding(0, 0, 0, dpToPx(context, 8));
        addView(desc);

        statusView = new TextView(context);
        statusView.setText("Đang kiểm tra trạng thái Tailscale...");
        statusView.setTextSize(13);
        statusView.setTextColor(Color.rgb(139, 148, 158));
        statusView.setPadding(0, 0, 0, dpToPx(context, 10));
        addView(statusView);

        loginBtn = new Button(context);
        loginBtn.setText("🔗 ĐĂNG NHẬP TAILSCALE");
        loginBtn.setTextColor(Color.rgb(255, 255, 255));
        loginBtn.setBackgroundColor(Color.rgb(26, 84, 176));
        loginBtn.setTextSize(14);
        loginBtn.setTypeface(Typeface.DEFAULT_BOLD);
        loginBtn.setVisibility(GONE);
        int bPadH = dpToPx(context, 16);
        int bPadV = dpToPx(context, 10);
        loginBtn.setPadding(bPadH, bPadV, bPadH, bPadV);
        loginBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callback != null && currentAuthUrl != null && !currentAuthUrl.isEmpty()) {
                    callback.onLoginClicked(currentAuthUrl);
                }
            }
        });
        addView(loginBtn);

        Button diagBtn = new Button(context);
        diagBtn.setText("Chẩn đoán kết nối");
        diagBtn.setTextColor(Color.rgb(139, 148, 158));
        diagBtn.setBackgroundColor(Color.TRANSPARENT);
        diagBtn.setTextSize(12);
        diagBtn.setOnClickListener(new OnClickListener() {
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
            statusView.setText("Tailscale đã dừng");
            statusView.setTextColor(Color.rgb(139, 148, 158));
            loginBtn.setVisibility(GONE);
            return;
        }

        if (state.isStarting()) {
            ready = false;
            currentAuthUrl = "";
            statusView.setText("Đang khởi động Tailscale...");
            statusView.setTextColor(Color.rgb(139, 148, 158));
            loginBtn.setVisibility(GONE);
            return;
        }

        if (state.isNeedsLogin()) {
            ready = false;
            currentAuthUrl = state.authUrl != null ? state.authUrl : "";
            statusView.setText("Cần đăng nhập Tailscale. Bấm nút bên dưới để mở trình duyệt.");
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
            statusView.setText("✓ Đã đăng nhập Tailscale" + (!ip.isEmpty() ? " (" + ip + ")" : ""));
            statusView.setTextColor(Color.rgb(46, 160, 67));
            loginBtn.setVisibility(GONE);
            return;
        }

        if (state.isError()) {
            ready = false;
            currentAuthUrl = "";
            String err = state.error != null && !state.error.isEmpty() ? state.error : "Không xác định";
            statusView.setText("Lỗi kết nối Tailscale: " + err);
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
