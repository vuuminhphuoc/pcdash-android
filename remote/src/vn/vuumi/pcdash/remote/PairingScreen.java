package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.i18n.L10n;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** The unpaired screen: Tailscale sign-in (LoginPanel), host discovery, IP and pairing code. */
public class PairingScreen extends ScrollView {

    public interface Callback {
        void onFindHostClicked();

        void onPairClicked();
    }

    final LoginPanel loginPanel;
    final EditText deviceNameInput;
    final LinearLayout discoveredHostsLayout;
    final EditText ipInput;
    final EditText codeInput;
    final ProgressBar progressBar;
    final TextView statusView;

    public PairingScreen(Context context, String versionName, LoginPanel.Callback loginCallback, final Callback callback) {
        super(context);
        setBackgroundColor(Color.rgb(0, 0, 0));

        LinearLayout rootContainer = new LinearLayout(context);
        rootContainer.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(context, 24);
        rootContainer.setPadding(pad, pad, pad, pad);
        addView(rootContainer);

        TextView title = new TextView(context);
        title.setText(L10n.t("Pair this phone", "Ghép đôi thiết bị"));
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(230, 237, 243));
        rootContainer.addView(title);

        TextView sub = new TextView(context);
        sub.setText(L10n.t("Sign in to Tailscale and connect to the home phone on the LAN", "Đăng nhập Tailscale và kết nối với máy tính trên mạng LAN"));
        sub.setTextSize(13);
        sub.setTextColor(Color.rgb(139, 148, 158));
        sub.setPadding(0, UiKit.dp(context, 4), 0, UiKit.dp(context, 16));
        rootContainer.addView(sub);

        // Bước 1: Đăng nhập Tailscale
        loginPanel = new LoginPanel(context, loginCallback);
        rootContainer.addView(loginPanel);

        addSpacing(rootContainer, UiKit.dp(context, 20));

        // Bước 2: Ghép đôi qua LAN
        TextView pairSectionHeader = new TextView(context);
        pairSectionHeader.setText(L10n.t("2. Pair over Wi-Fi", "2. Ghép đôi thiết bị qua Wi-Fi"));
        pairSectionHeader.setTextSize(16);
        pairSectionHeader.setTypeface(Typeface.DEFAULT_BOLD);
        pairSectionHeader.setTextColor(Color.rgb(88, 166, 255));
        pairSectionHeader.setPadding(0, 0, 0, UiKit.dp(context, 6));
        rootContainer.addView(pairSectionHeader);

        TextView nameLabel = new TextView(context);
        nameLabel.setText(L10n.t("Device name (shown on the host):", "Tên thiết bị (hiển thị trên host):"));
        nameLabel.setTextSize(12);
        nameLabel.setTextColor(Color.rgb(139, 148, 158));
        nameLabel.setPadding(0, 0, 0, UiKit.dp(context, 4));
        rootContainer.addView(nameLabel);

        deviceNameInput = new EditText(context);
        String defaultName = android.os.Build.MODEL != null ? android.os.Build.MODEL : "Android Remote";
        deviceNameInput.setText(defaultName);
        deviceNameInput.setTextColor(Color.rgb(230, 237, 243));
        deviceNameInput.setBackgroundColor(Color.rgb(16, 20, 26));
        deviceNameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        deviceNameInput.setPadding(UiKit.dp(context, 12), UiKit.dp(context, 12), UiKit.dp(context, 12), UiKit.dp(context, 12));
        rootContainer.addView(deviceNameInput);

        addSpacing(rootContainer, UiKit.dp(context, 14));

        // Discovery section
        TextView discHeader = new TextView(context);
        discHeader.setText(L10n.t("Find the host automatically:", "Tìm host tự động:"));
        discHeader.setTextSize(13);
        discHeader.setTextColor(Color.rgb(201, 209, 217));
        discHeader.setPadding(0, 0, 0, UiKit.dp(context, 4));
        rootContainer.addView(discHeader);

        discoveredHostsLayout = new LinearLayout(context);
        discoveredHostsLayout.setOrientation(LinearLayout.VERTICAL);
        rootContainer.addView(discoveredHostsLayout);

        Button searchBtn = new Button(context);
        searchBtn.setText(L10n.t("🔍 Find the host on this Wi-Fi", "🔍 Tìm host tự động trên Wi-Fi"));
        searchBtn.setTextColor(Color.rgb(230, 237, 243));
        searchBtn.setBackgroundColor(Color.rgb(33, 38, 45));
        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                callback.onFindHostClicked();
            }
        });
        rootContainer.addView(searchBtn);

        addSpacing(rootContainer, UiKit.dp(context, 18));

        // Manual IP section
        TextView ipHeader = new TextView(context);
        ipHeader.setText(L10n.t("Or enter the IP:", "Hoặc nhập IP thủ công:"));
        ipHeader.setTextSize(13);
        ipHeader.setTextColor(Color.rgb(201, 209, 217));
        rootContainer.addView(ipHeader);

        TextView ipLabel = new TextView(context);
        ipLabel.setText(L10n.t("IP address of the phone running PCDash host (same Wi-Fi)", "Địa chỉ IP máy chạy PCDash (cùng mạng Wi-Fi)"));
        ipLabel.setTextSize(12);
        ipLabel.setTextColor(Color.rgb(139, 148, 158));
        ipLabel.setPadding(0, 0, 0, UiKit.dp(context, 4));
        rootContainer.addView(ipLabel);

        ipInput = new EditText(context);
        ipInput.setHint(L10n.t("Example: 192.168.1.100", "Ví dụ: 192.168.1.100"));
        ipInput.setHintTextColor(Color.rgb(110, 118, 129));
        ipInput.setTextColor(Color.rgb(230, 237, 243));
        ipInput.setBackgroundColor(Color.rgb(16, 20, 26));
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT);
        ipInput.setPadding(UiKit.dp(context, 12), UiKit.dp(context, 12), UiKit.dp(context, 12), UiKit.dp(context, 12));
        rootContainer.addView(ipInput);

        addSpacing(rootContainer, UiKit.dp(context, 18));

        // Code Section
        TextView codeHeader = new TextView(context);
        codeHeader.setText(L10n.t("6-character pairing code shown on the host:", "Mã ghép đôi 6 ký tự hiển thị trên PC:"));
        codeHeader.setTextSize(13);
        codeHeader.setTextColor(Color.rgb(201, 209, 217));
        codeHeader.setPadding(0, 0, 0, UiKit.dp(context, 4));
        rootContainer.addView(codeHeader);

        codeInput = new EditText(context);
        codeInput.setHint(L10n.t("ABCDEF (6 characters)", "ABCDEF (tối đa 6 ký tự)"));
        codeInput.setHintTextColor(Color.rgb(110, 118, 129));
        codeInput.setTextColor(Color.rgb(230, 237, 243));
        codeInput.setTextSize(22);
        codeInput.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeInput.setGravity(Gravity.CENTER);
        codeInput.setBackgroundColor(Color.rgb(16, 20, 26));
        codeInput.setPadding(UiKit.dp(context, 12), UiKit.dp(context, 16), UiKit.dp(context, 12), UiKit.dp(context, 16));

        codeInput.addTextChangedListener(new TextWatcher() {
            private boolean isUpdating = false;

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (isUpdating) return;
                isUpdating = true;
                String normalized = RemoteController.normalizeCode(s.toString());
                s.replace(0, s.length(), normalized);
                isUpdating = false;
            }
        });
        rootContainer.addView(codeInput);

        addSpacing(rootContainer, UiKit.dp(context, 24));

        Button pairBtn = new Button(context);
        pairBtn.setText(L10n.t("START PAIRING", "BẮT ĐẦU GHÉP ĐÔI"));
        pairBtn.setTextColor(Color.rgb(255, 255, 255));
        pairBtn.setTextSize(15);
        pairBtn.setTypeface(Typeface.DEFAULT_BOLD);
        pairBtn.setBackgroundColor(Color.rgb(27, 102, 42));
        int padH = UiKit.dp(context, 16);
        int padV = UiKit.dp(context, 14);
        pairBtn.setPadding(padH, padV, padH, padV);
        pairBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                callback.onPairClicked();
            }
        });
        rootContainer.addView(pairBtn);

        progressBar = new ProgressBar(context);
        progressBar.setVisibility(View.GONE);
        progressBar.setPadding(0, UiKit.dp(context, 12), 0, UiKit.dp(context, 12));
        rootContainer.addView(progressBar);

        statusView = new TextView(context);
        statusView.setText("");
        statusView.setTextSize(13);
        statusView.setTextColor(Color.rgb(210, 153, 34));
        statusView.setPadding(0, UiKit.dp(context, 8), 0, 0);
        rootContainer.addView(statusView);

        addSpacing(rootContainer, UiKit.dp(context, 24));
        TextView versionFooter = new TextView(context);
        versionFooter.setText(L10n.t("Version ", "Phiên bản ") + versionName);
        versionFooter.setTextSize(12);
        versionFooter.setTextColor(Color.rgb(110, 118, 129));
        versionFooter.setGravity(Gravity.CENTER);
        versionFooter.setPadding(0, UiKit.dp(context, 8), 0, UiKit.dp(context, 16));
        rootContainer.addView(versionFooter);

    }

    private static void addSpacing(LinearLayout layout, int heightPx) {
        View spacer = new View(layout.getContext());
        spacer.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx));
        layout.addView(spacer);
    }
}
