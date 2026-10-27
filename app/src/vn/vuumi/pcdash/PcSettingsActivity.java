package vn.vuumi.pcdash;

import vn.vuumi.pcdash.qr.QrScanActivity;
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
import java.util.ArrayList;
import java.util.List;

public class PcSettingsActivity extends Activity implements View.OnClickListener {
    private AppLockGate lockGate;
    private static final int REQ_SCAN_QR = 72;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText nameInput;
    private EditText ipInput;
    private EditText macInput;
    private EditText portInput;
    private EditText tokenInput;
    private TextView webTokenView;
    private Button scanBtn;
    private Button saveBtn;
    private Button copyWebTokenBtn;
    private Button regenWebTokenBtn;
    private Button addPcBtn;
    private Button deletePcBtn;
    private LinearLayout pcListView;
    private TextView formTitle;
    // The PC the form edits; null while adding a new one.
    private String editingId;
    private List<PcProfile> pcs = new ArrayList<PcProfile>();

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
        sub.setText(L10n.t("Your PCs, their connection and access keys", "Các PC, cấu hình kết nối và khóa bảo vệ"));
        sub.setTextSize(13);
        sub.setTextColor(Color.rgb(139, 148, 158));
        sub.setPadding(0, dp(4), 0, dp(16));
        root.addView(sub);

        // Card 0: the PCs (tap one to edit it)
        LinearLayout listCard = new LinearLayout(this);
        listCard.setOrientation(LinearLayout.VERTICAL);
        listCard.setPadding(dp(16), dp(16), dp(16), dp(12));
        styleCard(listCard);
        LinearLayout.LayoutParams lcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lcLp.setMargins(0, 0, 0, dp(16));
        listCard.setLayoutParams(lcLp);
        TextView listTitle = new TextView(this);
        listTitle.setText(L10n.t("PCS", "DANH SÁCH PC"));
        listTitle.setTextSize(13);
        listTitle.setTypeface(Typeface.DEFAULT_BOLD);
        listTitle.setTextColor(Color.rgb(230, 237, 243));
        listTitle.setPadding(0, 0, 0, dp(8));
        listCard.addView(listTitle);
        pcListView = new LinearLayout(this);
        pcListView.setOrientation(LinearLayout.VERTICAL);
        listCard.addView(pcListView);
        addPcBtn = makeStyledButton(L10n.t("+ Add a PC", "+ Thêm PC"), Color.rgb(33, 38, 45), Color.rgb(48, 54, 61), Color.rgb(88, 166, 255), 42);
        LinearLayout.LayoutParams apLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        apLp.setMargins(0, dp(8), 0, 0);
        addPcBtn.setLayoutParams(apLp);
        Tap.wire(addPcBtn, this);
        listCard.addView(addPcBtn);
        root.addView(listCard);

        // Card 1: PC Connection Settings
        LinearLayout connCard = new LinearLayout(this);
        connCard.setOrientation(LinearLayout.VERTICAL);
        connCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        styleCard(connCard);
        LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ccLp.setMargins(0, 0, 0, dp(16));
        connCard.setLayoutParams(ccLp);

        formTitle = new TextView(this);
        formTitle.setText(L10n.t("PC & AGENT CONNECTION", "KẾT NỐI PC & AGENT"));
        formTitle.setTextSize(13);
        formTitle.setTypeface(Typeface.DEFAULT_BOLD);
        formTitle.setTextColor(Color.rgb(230, 237, 243));
        formTitle.setPadding(0, 0, 0, dp(4));
        connCard.addView(formTitle);

        TextView scanHint = new TextView(this);
        scanHint.setText(L10n.t("On the PC open the \"PCDash - Ma QR cai dat\" shortcut (Start menu) or run agent\\show-qr.bat, then scan the QR code once. The fields below fill in by themselves.", "Trên PC mở lối tắt \"PCDash - Ma QR cai dat\" (Start menu) hoặc chạy agent\\show-qr.bat, rồi quét mã QR một lần. Các ô bên dưới sẽ tự điền."));
        scanHint.setTextSize(12);
        scanHint.setTextColor(Color.rgb(139, 148, 158));
        scanHint.setPadding(0, 0, 0, dp(10));
        connCard.addView(scanHint);

        scanBtn = makeStyledButton(L10n.t("Scan QR code from PC", "Quét mã QR từ PC"), Color.rgb(31, 111, 235), 0, Color.WHITE, 48);
        Tap.wire(scanBtn, this);
        connCard.addView(scanBtn);

        connCard.addView(makeFieldLabel(L10n.t("Name (shown on the remote):", "Tên PC (hiện trên remote):")));
        nameInput = makeStyledInput(L10n.t("Example: Office PC", "Ví dụ: PC phòng làm việc"), InputType.TYPE_CLASS_TEXT);
        connCard.addView(nameInput);

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

        deletePcBtn = makeStyledButton(L10n.t("Remove this PC", "Xóa PC này"), Color.rgb(34, 20, 22), Color.rgb(248, 81, 73), Color.rgb(248, 81, 73), 42);
        LinearLayout.LayoutParams dpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dpLp.setMargins(0, dp(10), 0, 0);
        deletePcBtn.setLayoutParams(dpLp);
        deletePcBtn.setVisibility(View.GONE);
        Tap.wire(deletePcBtn, this);
        connCard.addView(deletePcBtn);

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
        loadPcsAsync(null, true);
    }

    /** Reads the PCs (and the web code) off the UI thread, then shows the list and edits {@code selectId}. */
    private void loadPcsAsync(final String selectId, final boolean withWebToken) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final PcSettings s = new PcSettings(PcSettingsActivity.this);
                final List<PcProfile> list = s.listPcs();
                final String webToken = withWebToken ? s.getWebToken() : null;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        pcs = list;
                        if (webToken != null) webTokenView.setText(webToken);
                        PcProfile select = null;
                        for (PcProfile p : list) {
                            if (p.id.equals(selectId)) select = p;
                        }
                        if (select == null && !list.isEmpty()) select = list.get(0);
                        edit(select);
                    }
                });
            }
        }, "pcdash-settings-load").start();
    }

    // Shows a PC in the form (null: an empty form for a new PC) and highlights it in the list.
    private void edit(PcProfile pc) {
        editingId = pc != null ? pc.id : null;
        nameInput.setText(pc != null ? pc.name : (pcs.isEmpty() ? "PC" : "PC " + (pcs.size() + 1)));
        ipInput.setText(pc != null ? pc.ip : "");
        macInput.setText(pc != null ? pc.mac : "");
        portInput.setText(String.valueOf(pc != null ? pc.port : PcSettings.DEFAULT_AGENT_PORT));
        tokenInput.setText(pc != null ? pc.token : "");
        formTitle.setText(pc != null ? L10n.t("EDIT: ", "ĐANG SỬA: ") + pc.name.toUpperCase(java.util.Locale.ROOT)
                : L10n.t("NEW PC", "PC MỚI"));
        deletePcBtn.setVisibility(pc != null ? View.VISIBLE : View.GONE);
        addPcBtn.setVisibility(pcs.size() < PcSettings.MAX_PCS ? View.VISIBLE : View.GONE);
        renderPcList();
    }

    private void renderPcList() {
        pcListView.removeAllViews();
        if (pcs.isEmpty()) {
            TextView none = new TextView(this);
            none.setText(L10n.t("No PC yet. Scan the QR code on the PC or fill in the form below.", "Chưa có PC nào. Quét mã QR trên PC hoặc điền ô bên dưới."));
            none.setTextSize(12);
            none.setTextColor(Color.rgb(139, 148, 158));
            pcListView.addView(none);
            return;
        }
        for (final PcProfile p : pcs) {
            boolean selected = p.id.equals(editingId);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(selected ? Color.rgb(17, 36, 64) : Color.rgb(22, 27, 34));
            bg.setStroke(dp(1), selected ? Color.rgb(31, 111, 235) : Color.rgb(48, 54, 61));
            bg.setCornerRadius(dp(8));
            row.setBackground(bg);
            TextView name = new TextView(this);
            name.setText(p.name);
            name.setTextSize(15);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(Color.rgb(230, 237, 243));
            row.addView(name);
            TextView detail = new TextView(this);
            detail.setText(p.ip + (p.mac.isEmpty() ? "" : "  ·  " + p.mac));
            detail.setTextSize(12);
            detail.setTextColor(Color.rgb(139, 148, 158));
            row.addView(detail);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, 0, 0, dp(6));
            row.setLayoutParams(rowLp);
            row.setContentDescription(p.name + ", " + p.ip);
            Tap.wire(row, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    edit(p);
                }
            });
            pcListView.addView(row);
        }
    }

    private void confirmDeletePc() {
        final String id = editingId;
        if (id == null) return;
        String name = nameInput.getText() != null ? nameInput.getText().toString().trim() : "PC";
        DialogStyle.show(DialogStyle.builder(this)
                .setTitle(L10n.t("Remove this PC?", "Xóa PC này?"))
                .setMessage(L10n.t("Remove \"", "Xóa \"") + name + L10n.t("\" from this phone? The PC itself is not changed.", "\" khỏi điện thoại này? PC không bị thay đổi gì."))
                .setPositiveButton(L10n.t("Remove", "Xóa"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                PcSettings s = new PcSettings(PcSettingsActivity.this);
                                s.removePc(id);
                                PcConn.configure(s);
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(PcSettingsActivity.this, L10n.t("PC removed", "Đã xóa PC"), Toast.LENGTH_SHORT).show();
                                    }
                                });
                                loadPcsAsync(null, false);
                            }
                        }, "pcdash-settings-remove").start();
                    }
                })
                .setNegativeButton(L10n.t("Cancel", "Hủy"), null), true);
    }

    @Override
    public void onClick(View v) {
        if (v == saveBtn) {
            handleSave();
        } else if (v == addPcBtn) {
            edit(null);
            nameInput.requestFocus();
        } else if (v == deletePcBtn) {
            confirmDeletePc();
        } else if (v == scanBtn) {
            startActivityForResult(QrScanActivity.intent(this, PcProvisioning.SCHEME_PREFIX,
                    L10n.t("Point the camera at the QR code on the PC screen", "Hướng camera vào mã QR đang hiện trên màn hình PC"),
                    L10n.t("You can type the values in PC settings instead.", "Có thể nhập tay trong Cài đặt PC.")), REQ_SCAN_QR);
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
        // The QR code names its PC by MAC: fill in that PC, or a new one when the form shows another PC.
        PcProfile current = null;
        PcProfile sameMac = null;
        for (PcProfile p : pcs) {
            if (p.id.equals(editingId)) current = p;
            if (!p.mac.isEmpty() && p.mac.equalsIgnoreCase(setup.mac)) sameMac = p;
        }
        if (sameMac != null && sameMac != current) {
            edit(sameMac);
        } else if (sameMac == null && current != null && !current.mac.isEmpty()) {
            edit(null);
        }
        ipInput.setText(setup.ip);
        macInput.setText(setup.mac);
        portInput.setText(String.valueOf(setup.port));
        tokenInput.setText(setup.token);
        saveBtn.requestFocus();
        Toast.makeText(this, L10n.t("Scanned. Check the values, then tap Save settings", "Đã quét xong. Kiểm tra rồi bấm Lưu cài đặt"), Toast.LENGTH_LONG).show();
    }

    private void handleSave() {
        final String name = nameInput.getText() != null ? nameInput.getText().toString().trim() : "";
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
        final String id = editingId;
        new Thread(new Runnable() {
            @Override
            public void run() {
                PcSettings s = new PcSettings(PcSettingsActivity.this);
                String savedId = id;
                boolean tooMany = false;
                if (id == null) {
                    try {
                        savedId = s.addPc(name, ip, mac, finalPort, agentToken);
                    } catch (IllegalStateException e) {
                        tooMany = true;
                    }
                } else {
                    s.updatePc(id, name, ip, mac, finalPort, agentToken);
                }
                PcConn.configure(s);
                final boolean single = s.listPcs().size() == 1;
                final boolean full = tooMany;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (full) {
                            Toast.makeText(PcSettingsActivity.this, L10n.t("At most ", "Tối đa ") + PcSettings.MAX_PCS + " PC", Toast.LENGTH_LONG).show();
                            return;
                        }
                        Toast.makeText(PcSettingsActivity.this, L10n.t("PC settings saved", "Đã lưu cài đặt PC thành công"), Toast.LENGTH_SHORT).show();
                        // One PC: done, as before. Several: stay to set up the next.
                        if (single) finish();
                    }
                });
                if (!single && !full) loadPcsAsync(savedId, false);
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
