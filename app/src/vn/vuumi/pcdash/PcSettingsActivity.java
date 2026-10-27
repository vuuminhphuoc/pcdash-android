package vn.vuumi.pcdash;

import vn.vuumi.pcdash.ui.Tap;
import vn.vuumi.pcdash.lock.AppLockGate;
import vn.vuumi.pcdash.ui.DialogStyle;
import vn.vuumi.pcdash.ui.SystemBarInsets;
import vn.vuumi.pcdash.i18n.L10n;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class PcSettingsActivity extends Activity implements View.OnClickListener {
    private AppLockGate lockGate;
    private static final int REQ_SCAN_QR = 72;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText ipInput;
    private EditText macInput;
    private EditText portInput;
    private EditText tokenInput;
    private TextView webTokenView;
    private Button scanBtn;
    private Button saveBtn;
    private Button copyWebTokenBtn;
    private Button regenWebTokenBtn;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void styleCard(View v) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.rgb(16, 20, 26));
        gd.setStroke(dp(1), Color.rgb(36, 42, 50));
        gd.setCornerRadius(dp(12));
        v.setBackground(gd);
    }

    private EditText makeStyledInput(String hint, int inputType) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(inputType);
        et.setTextColor(Color.rgb(230, 237, 243));
        et.setHintTextColor(Color.rgb(110, 118, 129));
        et.setTextSize(14);
        et.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.rgb(22, 27, 34));
        gd.setStroke(dp(1), Color.rgb(48, 54, 61));
        gd.setCornerRadius(dp(8));
        et.setBackground(gd);
        return et;
    }

    private Button makeStyledButton(String text, int bgColor, int strokeColor, int textColor, int heightDp) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        if (strokeColor != 0) {
            gd.setStroke(dp(1), strokeColor);
        }
        gd.setCornerRadius(dp(8));
        if (Build.VERSION.SDK_INT >= 21) {
            b.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(Color.argb(40, 255, 255, 255)), gd, null));
        } else {
            b.setBackground(gd);
        }
        b.setMinHeight(dp(heightDp));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        return b;
    }

    private TextView makeFieldLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.rgb(139, 148, 158));
        tv.setPadding(dp(2), dp(10), 0, dp(6));
        return tv;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Color.rgb(0, 0, 0));
            getWindow().setNavigationBarColor(Color.rgb(0, 0, 0));
        }
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        if (getActionBar() != null) {
            getActionBar().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            getActionBar().setTitle(L10n.t("PC settings", "Cài đặt PC"));
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));
        root.setBackgroundColor(Color.rgb(0, 0, 0));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText(L10n.t("PC settings", "Cài đặt PC"));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(230, 237, 243));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText(L10n.t("Connection to the PC and access keys", "Cấu hình kết nối tới PC và khóa bảo vệ"));
        sub.setTextSize(13);
        sub.setTextColor(Color.rgb(139, 148, 158));
        sub.setPadding(0, dp(4), 0, dp(16));
        root.addView(sub);

        // Card 1: PC Connection Settings
        LinearLayout connCard = new LinearLayout(this);
        connCard.setOrientation(LinearLayout.VERTICAL);
        connCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        styleCard(connCard);
        LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ccLp.setMargins(0, 0, 0, dp(16));
        connCard.setLayoutParams(ccLp);

        TextView connTitle = new TextView(this);
        connTitle.setText(L10n.t("PC & AGENT CONNECTION", "KẾT NỐI PC & AGENT"));
        connTitle.setTextSize(13);
        connTitle.setTypeface(Typeface.DEFAULT_BOLD);
        connTitle.setTextColor(Color.rgb(230, 237, 243));
        connTitle.setPadding(0, 0, 0, dp(4));
        connCard.addView(connTitle);

        TextView scanHint = new TextView(this);
        scanHint.setText(L10n.t("On the PC open the \"PCDash - Ma QR cai dat\" shortcut (Start menu) or run agent\\show-qr.bat, then scan the QR code once. The fields below fill in by themselves.", "Trên PC mở lối tắt \"PCDash - Ma QR cai dat\" (Start menu) hoặc chạy agent\\show-qr.bat, rồi quét mã QR một lần. Các ô bên dưới sẽ tự điền."));
        scanHint.setTextSize(12);
        scanHint.setTextColor(Color.rgb(139, 148, 158));
        scanHint.setPadding(0, 0, 0, dp(10));
        connCard.addView(scanHint);

        scanBtn = makeStyledButton(L10n.t("Scan QR code from PC", "Quét mã QR từ PC"), Color.rgb(31, 111, 235), 0, Color.WHITE, 48);
        Tap.wire(scanBtn, this);
        connCard.addView(scanBtn);

        connCard.addView(makeFieldLabel(L10n.t("PC IPv4 address (private, RFC1918):", "Địa chỉ IPv4 PC (RFC1918 nội bộ):")));
        ipInput = makeStyledInput(L10n.t("Example: 192.168.1.50", "Ví dụ: 192.168.1.50"), InputType.TYPE_CLASS_TEXT);
        connCard.addView(ipInput);

        connCard.addView(makeFieldLabel(L10n.t("PC MAC address (for Wake-on-LAN):", "Địa chỉ MAC PC (dành cho WOL):")));
        macInput = makeStyledInput(L10n.t("Example: 00:11:22:33:44:55", "Ví dụ: 00:11:22:33:44:55"), InputType.TYPE_CLASS_TEXT);
        connCard.addView(macInput);

        connCard.addView(makeFieldLabel(L10n.t("PC agent port (default 8799):", "Cổng Agent PC (mặc định 8799):")));
        portInput = makeStyledInput("8799", InputType.TYPE_CLASS_NUMBER);
        connCard.addView(portInput);

        connCard.addView(makeFieldLabel(L10n.t("Agent token (X-PcAgent-Token):", "Mã Token Agent (X-PcAgent-Token):")));
        tokenInput = makeStyledInput(L10n.t("Secret token of the PC agent", "Mã token bí mật của Agent PC"),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        connCard.addView(tokenInput);

        saveBtn = makeStyledButton(L10n.t("Save settings", "Lưu cài đặt"), Color.rgb(27, 102, 42), 0, Color.WHITE, 48);
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sbLp.setMargins(0, dp(16), 0, 0);
        saveBtn.setLayoutParams(sbLp);
        Tap.wire(saveBtn, this);
        connCard.addView(saveBtn);

        root.addView(connCard);

        // Card 2: Web Dashboard Access Code
        LinearLayout webCard = new LinearLayout(this);
        webCard.setOrientation(LinearLayout.VERTICAL);
        webCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        styleCard(webCard);
        LinearLayout.LayoutParams wcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wcLp.setMargins(0, 0, 0, dp(16));
        webCard.setLayoutParams(wcLp);

        TextView webTitle = new TextView(this);
        webTitle.setText(L10n.t("WEB DASHBOARD ACCESS CODE", "MÃ TRUY CẬP WEB DASHBOARD"));
        webTitle.setTextSize(13);
        webTitle.setTypeface(Typeface.DEFAULT_BOLD);
        webTitle.setTextColor(Color.rgb(230, 237, 243));
        webTitle.setPadding(0, 0, 0, dp(4));
        webCard.addView(webTitle);

        TextView webSub = new TextView(this);
        webSub.setText(L10n.t("Used to authorize PC control from a web browser (X-PCDash-Token):", "Dùng để xác thực khi điều khiển PC từ trình duyệt Web (X-PCDash-Token):"));
        webSub.setTextSize(12);
        webSub.setTextColor(Color.rgb(139, 148, 158));
        webSub.setPadding(0, 0, 0, dp(10));
        webCard.addView(webSub);

        webTokenView = new TextView(this);
        webTokenView.setTextSize(15);
        webTokenView.setTypeface(Typeface.MONOSPACE);
        webTokenView.setTextColor(Color.rgb(88, 166, 255));
        webTokenView.setPadding(dp(12), dp(12), dp(12), dp(12));
        webTokenView.setGravity(Gravity.CENTER);
        GradientDrawable codeBox = new GradientDrawable();
        codeBox.setColor(Color.rgb(13, 17, 23));
        codeBox.setStroke(dp(1), Color.rgb(48, 54, 61));
        codeBox.setCornerRadius(dp(8));
        webTokenView.setBackground(codeBox);
        webCard.addView(webTokenView);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        brLp.setMargins(0, dp(12), 0, 0);
        btnRow.setLayoutParams(brLp);

        copyWebTokenBtn = makeStyledButton(L10n.t("Copy", "Sao chép"), Color.rgb(33, 38, 45), Color.rgb(48, 54, 61), Color.rgb(201, 209, 217), 42);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        copyLp.rightMargin = dp(6);
        copyWebTokenBtn.setLayoutParams(copyLp);
        Tap.wire(copyWebTokenBtn, this);
        btnRow.addView(copyWebTokenBtn);

        regenWebTokenBtn = makeStyledButton(L10n.t("New code", "Tạo mã mới"), Color.rgb(33, 38, 45), Color.rgb(48, 54, 61), Color.rgb(248, 81, 73), 42);
        LinearLayout.LayoutParams regenLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        regenLp.leftMargin = dp(6);
        regenWebTokenBtn.setLayoutParams(regenLp);
        Tap.wire(regenWebTokenBtn, this);
        btnRow.addView(regenWebTokenBtn);

        webCard.addView(btnRow);
        root.addView(webCard);

        setContentView(scroll);
        SystemBarInsets.pad(findViewById(android.R.id.content), SystemBarInsets.ALL);
        lockGate = new AppLockGate(this, 0);

        loadSettingsAsync();
    }

    private void loadSettingsAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final PcSettings s = new PcSettings(PcSettingsActivity.this);
                final String ip = s.getPcIp();
                final String mac = s.getPcMac();
                final int port = s.getAgentPort();
                final String token = s.getAgentToken();
                final String webToken = s.getWebToken();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        ipInput.setText(ip);
                        macInput.setText(mac);
                        portInput.setText(String.valueOf(port));
                        tokenInput.setText(token);
                        webTokenView.setText(webToken);
                    }
                });
            }
        }, "pcdash-settings-load").start();
    }

    @Override
    public void onClick(View v) {
        if (v == saveBtn) {
            handleSave();
        } else if (v == scanBtn) {
            startActivityForResult(new Intent(this, QrScanActivity.class), REQ_SCAN_QR);
        } else if (v == copyWebTokenBtn) {
            handleCopyWebToken();
        } else if (v == regenWebTokenBtn) {
            handleRegenWebToken();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SCAN_QR || resultCode != RESULT_OK || data == null) return;
        PcProvisioning setup = PcProvisioning.parse(data.getStringExtra(QrScanActivity.EXTRA_TEXT));
        if (setup == null) {
            Toast.makeText(this, L10n.t("Invalid QR code", "Mã QR không hợp lệ"), Toast.LENGTH_SHORT).show();
            return;
        }
        ipInput.setText(setup.ip);
        macInput.setText(setup.mac);
        portInput.setText(String.valueOf(setup.port));
        tokenInput.setText(setup.token);
        saveBtn.requestFocus();
        Toast.makeText(this, L10n.t("Scanned. Check the values, then tap Save settings", "Đã quét xong. Kiểm tra rồi bấm Lưu cài đặt"), Toast.LENGTH_LONG).show();
    }

    private void handleSave() {
        final String ip = ipInput.getText() != null ? ipInput.getText().toString().trim() : "";
        final String mac = macInput.getText() != null ? macInput.getText().toString().trim() : "";
        final String portStr = portInput.getText() != null ? portInput.getText().toString().trim() : "";
        final String agentToken = tokenInput.getText() != null ? tokenInput.getText().toString().trim() : "";

        if (!PcSettings.isValidRfc1918Ip(ip)) {
            Toast.makeText(this, L10n.t("The IP must be a private IPv4 address (RFC1918: 10.x, 172.16-31.x, 192.168.x)", "Địa chỉ IP phải là IPv4 nội bộ hợp lệ (RFC1918: 10.x, 172.16-31.x, 192.168.x)"), Toast.LENGTH_LONG).show();
            return;
        }
        if (!mac.isEmpty() && !PcSettings.isValidMac(mac)) {
            Toast.makeText(this, L10n.t("Invalid MAC address format (XX:XX:XX:XX:XX:XX or XX-XX-XX-XX-XX-XX)", "Địa chỉ MAC không đúng định dạng (XX:XX:XX:XX:XX:XX hoặc XX-XX-XX-XX-XX-XX)"), Toast.LENGTH_LONG).show();
            return;
        }
        int port = PcSettings.DEFAULT_AGENT_PORT;
        try {
            port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) {
                Toast.makeText(this, L10n.t("The agent port must be between 1 and 65535", "Cổng Agent phải từ 1 đến 65535"), Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, L10n.t("Invalid agent port", "Cổng Agent không hợp lệ"), Toast.LENGTH_SHORT).show();
            return;
        }

        final int finalPort = port;
        new Thread(new Runnable() {
            @Override
            public void run() {
                PcSettings s = new PcSettings(PcSettingsActivity.this);
                s.save(ip, mac, finalPort, agentToken);
                PcConn.configure(s);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcSettingsActivity.this, L10n.t("PC settings saved", "Đã lưu cài đặt PC thành công"), Toast.LENGTH_SHORT).show();
                        finish();
                    }
                });
            }
        }, "pcdash-settings-save").start();
    }

    private void handleCopyWebToken() {
        CharSequence text = webTokenView.getText();
        if (text == null || text.length() == 0) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            ClipData clip = ClipData.newPlainText("PCDash Web Token", text);
            cm.setPrimaryClip(clip);
            Toast.makeText(this, L10n.t("Access code copied", "Đã sao chép mã truy cập"), Toast.LENGTH_SHORT).show();
        }
    }

    private void handleRegenWebToken() {
        DialogStyle.show(DialogStyle.builder(this)
                .setTitle(L10n.t("New code", "Tạo mã mới"))
                .setMessage(L10n.t("Create a new web dashboard access code? Open browser sessions will need the new code.", "Tạo mã truy cập mới cho Web Dashboard? Các phiên đang kết nối sẽ cần nhập lại mã mới."))
                .setPositiveButton(L10n.t("New code", "Tạo mã mới"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                PcSettings s = new PcSettings(PcSettingsActivity.this);
                                final String newToken = s.regenerateWebToken();
                                PcConn.configure(s);
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        webTokenView.setText(newToken);
                                        Toast.makeText(PcSettingsActivity.this, L10n.t("New access code created", "Đã tạo mã truy cập mới"), Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        }, "pcdash-settings-regen").start();
                    }
                })
                .setNegativeButton(L10n.t("Cancel", "Hủy"), null), true);
    }
    @Override
    protected void onStart() {
        super.onStart();
        lockGate.onStart();
    }

    @Override
    protected void onStop() {
        lockGate.onStop();
        super.onStop();
    }
}
