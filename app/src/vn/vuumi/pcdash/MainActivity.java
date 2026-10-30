package vn.vuumi.pcdash;

import vn.vuumi.pcdash.lock.AppLockSettings;
import vn.vuumi.pcdash.lock.AppLockGate;
import vn.vuumi.pcdash.ui.ButtonProgress;
import vn.vuumi.pcdash.ui.SystemBarInsets;
import vn.vuumi.pcdash.ui.Tap;
import vn.vuumi.pcdash.i18n.AppLanguage;
import vn.vuumi.pcdash.i18n.L10n;
import vn.vuumi.pcdash.net.PcnetState;
import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.os.StrictMode;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity implements View.OnClickListener {
    private View statusDot;
    private TextView statusView;
    private TextView healthServiceView;
    private TextView healthPortView;
    private TextView healthBatteryView;
    private Button btnBattery;
    private TextView tailscaleStatusView;
    private Button tailscaleLoginBtn;
    private String currentTailscaleAuthUrl = "";
    private AppLockGate lockGate;
    private static final String UI_PREFS = "pcdash_host_ui";
    private static final String KEY_SELECTED_PC = "selected_pc";
    private volatile PcSettings hostSettings;
    // The PC the power buttons act on when there are several (null: the first).
    private volatile String selectedPcId;
    private HorizontalScrollView pcChipsScroll;
    private LinearLayout pcChips;
    private String shownChips = "";
    // PCs seen by the last status refresh (read off the UI thread: tokens are decrypted).
    private volatile int lastPcCount;
    // One power command at a time; each power button shows its own progress.
    private boolean actionRunning;
    private final Map<View, ButtonProgress> buttonProgress = new HashMap<View, ButtonProgress>();

    private final Runnable tailnetPoll = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    HostTailnet tailnet = DashService.getHostTailnet();
                    final PcnetState s = tailnet != null ? tailnet.state() : null;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            updateTailscaleCard(s);
                        }
                    });
                }
            }, "pcdash-main-tailnet-poll").start();
            ui.postDelayed(this, 2000);
        }
    };
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateHealthState();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final List<PcProfile> list = hostSettings.listPcs();
                    final PcProfile pc = selectedPc(list);
                    final PcConn.PcStatus status = PcConn.status(pc);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showPcChips(list, pc);
                            updateStatus(status, pc, list.size());
                        }
                    });
                }
            }).start();
            ui.postDelayed(this, 5000);
        }
    };

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

    private PcProfile selectedPc(List<PcProfile> list) {
        String id = selectedPcId;
        for (PcProfile p : list) {
            if (p.id.equals(id)) return p;
        }
        return list.isEmpty() ? null : list.get(0);
    }

    private void showPcChips(List<PcProfile> list, PcProfile selected) {
        lastPcCount = list.size();
        if (pcChips == null) return;
        StringBuilder key = new StringBuilder(selected != null ? selected.id : "");
        for (PcProfile p : list) key.append('|').append(p.id).append(':').append(p.name);
        if (key.toString().equals(shownChips)) return;
        shownChips = key.toString();
        pcChipsScroll.setVisibility(list.size() > 1 ? View.VISIBLE : View.GONE);
        pcChips.removeAllViews();
        for (final PcProfile p : list) {
            boolean on = selected != null && p.id.equals(selected.id);
            TextView chip = new TextView(this);
            chip.setText(p.name);
            chip.setTextSize(14);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setTextColor(on ? Color.WHITE : Color.rgb(201, 209, 217));
            chip.setPadding(dp(16), dp(9), dp(16), dp(9));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(on ? Color.rgb(31, 111, 235) : Color.rgb(22, 27, 34));
            bg.setStroke(dp(1), on ? Color.rgb(88, 166, 255) : Color.rgb(48, 54, 61));
            bg.setCornerRadius(dp(18));
            chip.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setSelected(on);
            Tap.wire(chip, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedPcId = p.id;
                    getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putString(KEY_SELECTED_PC, p.id).apply();
                    statusView.setText(L10n.t("Checking...", "Đang kiểm tra..."));
                    ui.removeCallbacks(tick);
                    ui.post(tick);
                }
            });
            pcChips.addView(chip);
        }
    }

    private void updateStatusDot(int color) {
        if (statusDot == null) return;
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        statusDot.setBackground(dot);
    }

    private void updateStatus(PcConn.PcStatus status, PcProfile pc, int pcCount) {
        if (statusView == null || status == null) return;
        // With several PCs, say which one the line is about.
        statusView.setText(pcCount > 1 && pc != null ? pc.name + ": " + status.line : status.line);
        if (status.state == PcConn.PcStatus.NOT_CONFIGURED) {
            updateStatusDot(Color.rgb(210, 153, 34));
            statusView.setTextColor(Color.rgb(210, 153, 34));
        } else if (status.state == PcConn.PcStatus.ON) {
            updateStatusDot(Color.rgb(46, 160, 67));
            statusView.setTextColor(Color.rgb(230, 237, 243));
        } else if (status.state == PcConn.PcStatus.OFF) {
            updateStatusDot(Color.rgb(248, 81, 73));
            statusView.setTextColor(Color.rgb(201, 209, 217));
        } else {
            updateStatusDot(Color.rgb(210, 153, 34));
            statusView.setTextColor(Color.rgb(139, 148, 158));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build());
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build());
        super.onCreate(savedInstanceState);
        PcSettings pcSettings = new PcSettings(this);
        PcConn.configure(pcSettings);
        hostSettings = pcSettings;
        selectedPcId = getSharedPreferences(UI_PREFS, MODE_PRIVATE).getString(KEY_SELECTED_PC, null);

        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Color.rgb(0, 0, 0));
            getWindow().setNavigationBarColor(Color.rgb(0, 0, 0));
        }
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
        if (getActionBar() != null) {
            getActionBar().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        root.setBackgroundColor(Color.rgb(0, 0, 0));
        scroll.addView(root);

        // The action bar already says "PCDash"; a second title only cost a screen's worth of space.
        TextView sub = new TextView(this);
        sub.setText(L10n.t("Remote control for your PC", "Điều khiển PC từ xa") + " · v" + HostUpdater.installedVersionName(this));
        sub.setTextSize(13);
        sub.setTextColor(Color.rgb(139, 148, 158));
        sub.setPadding(0, 0, 0, dp(14));
        root.addView(sub);

        // Status Card
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        styleCard(statusCard);
        statusCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scLp.setMargins(0, 0, 0, dp(16));
        statusCard.setLayoutParams(scLp);

        statusDot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotLp.rightMargin = dp(12);
        statusDot.setLayoutParams(dotLp);
        updateStatusDot(Color.rgb(210, 153, 34));
        statusCard.addView(statusDot);

        statusView = new TextView(this);
        statusView.setText(L10n.t("Checking...", "Đang kiểm tra..."));
        statusView.setTextSize(15);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setTextColor(Color.rgb(230, 237, 243));
        statusCard.addView(statusView);
        // Several PCs: pick the one the buttons below act on.
        pcChipsScroll = new HorizontalScrollView(this);
        pcChipsScroll.setHorizontalScrollBarEnabled(false);
        pcChips = new LinearLayout(this);
        pcChips.setOrientation(LinearLayout.HORIZONTAL);
        pcChipsScroll.addView(pcChips);
        LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipsLp.setMargins(0, 0, 0, dp(12));
        pcChipsScroll.setLayoutParams(chipsLp);
        pcChipsScroll.setVisibility(View.GONE);
        root.addView(pcChipsScroll);

        root.addView(statusCard);

        // Power buttons
        // 1. Wake (full width green)
        Button wakeBtn = makeStyledButton(L10n.t("Turn on / Wake PC", "Bật / Đánh thức PC"), Color.rgb(27, 102, 42), 0, Color.WHITE, 54, "wake");
        LinearLayout.LayoutParams wakeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wakeLp.setMargins(0, 0, 0, dp(10));
        wakeBtn.setLayoutParams(wakeLp);
        root.addView(wakeBtn);

        // 2. Sleep and Hibernate side by side in one row (equal weights)
        LinearLayout sleepRow = new LinearLayout(this);
        sleepRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        srLp.setMargins(0, 0, 0, dp(14));
        sleepRow.setLayoutParams(srLp);

        Button sleepBtn = makeStyledButton("Sleep", Color.rgb(26, 84, 176), 0, Color.WHITE, 50, "sleep");
        LinearLayout.LayoutParams sleepLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        sleepLp.rightMargin = dp(6);
        sleepBtn.setLayoutParams(sleepLp);
        sleepRow.addView(sleepBtn);

        Button hibernateBtn = makeStyledButton("Hibernate", Color.rgb(100, 64, 168), 0, Color.WHITE, 50, "hibernate");
        LinearLayout.LayoutParams hibLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        hibLp.leftMargin = dp(6);
        hibernateBtn.setLayoutParams(hibLp);
        sleepRow.addView(hibernateBtn);

        root.addView(sleepRow);

        // 2b. Lock and Sign out side by side (the signed-in Windows session)
        LinearLayout sessionRow = new LinearLayout(this);
        sessionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sesLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sesLp.setMargins(0, 0, 0, dp(14));
        sessionRow.setLayoutParams(sesLp);

        Button lockBtn = makeStyledButton(L10n.t("Lock", "Khóa máy"), Color.rgb(33, 38, 45), Color.rgb(48, 54, 61), Color.rgb(201, 209, 217), 46, "lock");
        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        lockLp.rightMargin = dp(6);
        lockBtn.setLayoutParams(lockLp);
        sessionRow.addView(lockBtn);

        Button logoffBtn = makeStyledButton(L10n.t("Sign out", "Đăng xuất"), Color.rgb(33, 38, 45), Color.rgb(48, 54, 61), Color.rgb(201, 209, 217), 46, "logoff");
        LinearLayout.LayoutParams logoffLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        logoffLp.leftMargin = dp(6);
        logoffBtn.setLayoutParams(logoffLp);
        sessionRow.addView(logoffBtn);

        root.addView(sessionRow);

        // 3. Danger zone caption
        TextView dangerCaption = new TextView(this);
        dangerCaption.setText(L10n.t("DANGER ZONE", "VÙNG NGUY HIỂM"));
        dangerCaption.setTextSize(12);
        dangerCaption.setTypeface(Typeface.DEFAULT_BOLD);
        dangerCaption.setTextColor(Color.rgb(139, 148, 158));
        dangerCaption.setPadding(dp(2), dp(8), 0, dp(8));
        root.addView(dangerCaption);

        // 4. Shutdown button (outlined / darker red)
        Button shutdownBtn = makeStyledButton(L10n.t("Shut down", "Tắt máy (Shutdown)"), Color.rgb(34, 20, 22), Color.rgb(248, 81, 73), Color.rgb(248, 81, 73), 50, "shutdown");
        LinearLayout.LayoutParams shutLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        shutLp.setMargins(0, 0, 0, dp(8));
        shutdownBtn.setLayoutParams(shutLp);
        root.addView(shutdownBtn);

        Button restartBtn = makeStyledButton(L10n.t("Restart", "Khởi động lại (Restart)"), Color.rgb(34, 20, 22), Color.rgb(248, 81, 73), Color.rgb(248, 81, 73), 50, "restart");
        LinearLayout.LayoutParams restartLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        restartLp.setMargins(0, 0, 0, dp(8));
        restartBtn.setLayoutParams(restartLp);
        root.addView(restartBtn);

        // 5. Abort button (smaller secondary button)
        Button abortBtn = makeStyledButton(L10n.t("Cancel shutdown", "Hủy lệnh tắt máy"), Color.rgb(16, 20, 26), Color.rgb(48, 54, 61), Color.rgb(201, 209, 217), 42, "abort");
        LinearLayout.LayoutParams abortLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        abortLp.setMargins(0, 0, 0, dp(14));
        abortBtn.setLayoutParams(abortLp);
        root.addView(abortBtn);

        // 6. Remote devices button
        Button remoteDevBtn = makeStyledButton(L10n.t("Remote devices", "Thiết bị điều khiển từ xa"), Color.rgb(26, 84, 176), 0, Color.WHITE, 48, "remote_devices");
        LinearLayout.LayoutParams rdLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rdLp.setMargins(0, 0, 0, dp(14));
        remoteDevBtn.setLayoutParams(rdLp);
        root.addView(remoteDevBtn);

        // Settings in a 2 x 2 grid: the four quiet buttons no longer push the status cards off the screen.
        Button pcSettingsBtn = makeStyledButton(L10n.t("PC settings", "Cài đặt PC"), Color.rgb(33, 38, 45), Color.rgb(48, 54, 61), Color.rgb(201, 209, 217), 48, "pc_settings");
        Button appLockBtn = makeStyledButton(L10n.t("App lock", "Khóa ứng dụng"), Color.rgb(33, 38, 45), Color.rgb(48, 54, 61), Color.rgb(201, 209, 217), 48, "app_lock");
        root.addView(buttonPair(pcSettingsBtn, appLockBtn, 8));

        Button languageBtn = makeStyledButton(L10n.t("Language", "Ngôn ngữ"), Color.rgb(33, 38, 45), Color.rgb(48, 54, 61), Color.rgb(201, 209, 217), 48, "language");
        updateBtn = makeStyledButton(L10n.t("Check updates", "Tìm bản mới"), Color.rgb(33, 38, 45), Color.rgb(48, 54, 61), Color.rgb(201, 209, 217), 48, "check_update");
        root.addView(buttonPair(languageBtn, updateBtn, 0));

        // The result of the last update check stays on screen (a toast is gone in seconds).
        updateStatusView = new TextView(this);
        updateStatusView.setTextSize(12);
        updateStatusView.setTextColor(Color.rgb(139, 148, 158));
        updateStatusView.setPadding(dp(2), dp(2), 0, dp(14));
        updateStatusView.setText(L10n.t("Version ", "Phiên bản ") + HostUpdater.installedVersionName(this));
        root.addView(updateStatusView);

        // Tailscale card
        LinearLayout tailscaleCard = new LinearLayout(this);
        tailscaleCard.setOrientation(LinearLayout.VERTICAL);
        tailscaleCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        styleCard(tailscaleCard);
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tsLp.setMargins(0, dp(6), 0, dp(12));
        tailscaleCard.setLayoutParams(tsLp);

        TextView tsTitle = new TextView(this);
        tsTitle.setText("TAILSCALE");
        tsTitle.setTextSize(13);
        tsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tsTitle.setTextColor(Color.rgb(230, 237, 243));
        tsTitle.setPadding(0, 0, 0, dp(8));
        tailscaleCard.addView(tsTitle);

        tailscaleStatusView = new TextView(this);
        tailscaleStatusView.setText(L10n.t("Status: checking...", "Trạng thái: Đang kiểm tra..."));
        tailscaleStatusView.setTextSize(13);
        tailscaleStatusView.setTextColor(Color.rgb(139, 148, 158));
        tailscaleStatusView.setPadding(0, 0, 0, dp(8));
        tailscaleCard.addView(tailscaleStatusView);

        tailscaleLoginBtn = makeStyledButton(L10n.t("Sign in to Tailscale", "Đăng nhập Tailscale"), Color.rgb(26, 84, 176), 0, Color.WHITE, 42, "tailscale_login");
        tailscaleLoginBtn.setVisibility(View.GONE);
        tailscaleCard.addView(tailscaleLoginBtn);
        root.addView(tailscaleCard);

        // Host Health card
        LinearLayout healthCard = new LinearLayout(this);
        healthCard.setOrientation(LinearLayout.VERTICAL);
        healthCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        styleCard(healthCard);
        LinearLayout.LayoutParams healthLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        healthLp.setMargins(0, dp(6), 0, dp(12));
        healthCard.setLayoutParams(healthLp);

        TextView healthTitle = new TextView(this);
        healthTitle.setText(L10n.t("HOST HEALTH", "SỨC KHỎE HOST"));
        healthTitle.setTextSize(13);
        healthTitle.setTypeface(Typeface.DEFAULT_BOLD);
        healthTitle.setTextColor(Color.rgb(230, 237, 243));
        healthTitle.setPadding(0, 0, 0, dp(8));
        healthCard.addView(healthTitle);

        healthServiceView = new TextView(this);
        healthServiceView.setTextSize(13);
        healthServiceView.setPadding(0, dp(2), 0, dp(4));
        healthCard.addView(healthServiceView);

        healthPortView = new TextView(this);
        healthPortView.setTextSize(13);
        healthPortView.setPadding(0, dp(2), 0, dp(4));
        healthCard.addView(healthPortView);

        healthBatteryView = new TextView(this);
        healthBatteryView.setTextSize(13);
        healthBatteryView.setPadding(0, dp(2), 0, dp(8));
        healthCard.addView(healthBatteryView);

        btnBattery = makeStyledButton(L10n.t("Turn off battery optimization", "Yêu cầu bỏ tối ưu pin"), Color.rgb(33, 38, 45), Color.rgb(48, 54, 61), Color.rgb(201, 209, 217), 40, "battery_opt");
        healthCard.addView(btnBattery);

        // Xiaomi, Redmi and POCO phones kill background apps unless autostart is on; other phones have no such screen.
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
        if (maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco")) {
            TextView autostartHint = new TextView(this);
            autostartHint.setText(L10n.t("Android cannot tell whether autostart is on. Open the screen and allow PCDash, or the host may stop after a restart.",
                    "Android không báo được tự khởi chạy đã bật hay chưa. Hãy mở màn hình này và cho phép PCDash, nếu không host có thể ngừng sau khi khởi động lại."));
            autostartHint.setTextSize(12);
            autostartHint.setTextColor(Color.rgb(139, 148, 158));
            autostartHint.setPadding(dp(2), dp(4), 0, dp(4));
            healthCard.addView(autostartHint);
            healthCard.addView(makeStyledButton(L10n.t("Open autostart settings", "Mở cài đặt tự khởi chạy"), Color.rgb(33, 38, 45), Color.rgb(48, 54, 61), Color.rgb(201, 209, 217), 40, "xiaomi_autostart"));
        }

        root.addView(healthCard);

        updateHealthState();

        TextView info = new TextView(this);
        info.setTextSize(13);
        info.setTextColor(Color.rgb(139, 148, 158));
        info.setPadding(0, dp(20), 0, dp(16));
        info.setText(WebDashboard.infoText(this));
        root.addView(info);

        setContentView(scroll);
        SystemBarInsets.pad(findViewById(android.R.id.content), SystemBarInsets.ALL);
        lockGate = new AppLockGate(this, 0);

        requestExtras();

        Intent svc = new Intent(this, DashService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        ui.post(tick);
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

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(tailnetPoll);
        ui.post(tailnetPoll);
        PcSettings pcSettings = new PcSettings(this);
        PcConn.configure(pcSettings);
        hostSettings = pcSettings;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<PcProfile> list = hostSettings.listPcs();
                final PcProfile pc = selectedPc(list);
                final PcConn.PcStatus status = PcConn.status(pc);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        showPcChips(list, pc);
                        updateStatus(status, pc, list.size());
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tailnetPoll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(tick);
        ui.removeCallbacks(tailnetPoll);
        super.onDestroy();
    }

    private Button makeStyledButton(String text, int fill, int stroke, int textColor, int minHeightDp, String action) {
        Button b = new Button(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        b.setLayoutParams(lp);
        b.setText(text);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setTextColor(textColor);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        if (stroke != 0) {
            gd.setStroke(dp(1), stroke);
        }
        gd.setCornerRadius(dp(12));
        if (isPowerAction(action)) {
            // Power buttons light up where they are touched, and glow while their command runs.
            b.setBackground(gd);
            Tap.glow(b, stroke != 0 ? textColor : fill, dp(12));
        } else {
            b.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), gd, null));
        }
        b.setMinimumHeight(dp(minHeightDp));
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setTag(action);
        Tap.wire(b, this);
        return b;
    }

    private static final String NL = System.getProperty("line.separator", "\n");
    private Button updateBtn;
    private TextView updateStatusView;
    private boolean updateBusy;

    /** Two equal-width buttons side by side. */
    private LinearLayout buttonPair(Button left, Button right, int bottomMarginDp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, 0, 0, dp(bottomMarginDp));
        row.setLayoutParams(rowLp);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        leftLp.rightMargin = dp(6);
        left.setLayoutParams(leftLp);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        rightLp.leftMargin = dp(6);
        right.setLayoutParams(rightLp);
        row.addView(left);
        row.addView(right);
        return row;
    }

    private void showUpdateStatus(String text, boolean problem) {
        if (updateStatusView == null) return;
        updateStatusView.setText(text);
        updateStatusView.setTextColor(problem ? Color.rgb(219, 109, 40) : Color.rgb(139, 148, 158));
    }

    private String checkedAt() {
        return android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date());
    }

    /** Asks GitHub for a newer host; offers to download and install it. */
    private void checkForUpdate() {
        if (updateBusy) return;
        updateBusy = true;
        updateBtn.setText(L10n.t("Checking...", "Đang kiểm tra..."));
        new Thread(new Runnable() {
            @Override
            public void run() {
                HostUpdateCheck.Offer found = null;
                String error = null;
                try {
                    found = HostUpdater.check(MainActivity.this);
                } catch (Exception e) {
                    error = e.getMessage() != null ? e.getMessage() : e.toString();
                }
                final HostUpdateCheck.Offer offer = found;
                final String failure = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        updateBusy = false;
                        resetUpdateButton();
                        if (isFinishing()) return;
                        if (failure != null) {
                            showUpdateStatus(L10n.t("Could not check for updates (", "Không kiểm tra được cập nhật (") + checkedAt() + "): " + failure, true);
                        } else if (offer == null) {
                            showUpdateStatus(L10n.t("Version ", "Phiên bản ") + HostUpdater.installedVersionName(MainActivity.this)
                                    + L10n.t(" is the latest. Checked at ", " là bản mới nhất. Kiểm tra lúc ") + checkedAt(), false);
                        } else {
                            askToUpdate(offer);
                        }
                    }
                });
            }
        }).start();
    }

    private void resetUpdateButton() {
        updateBtn.setText(L10n.t("Check updates", "Tìm bản mới"));
    }

    private void askToUpdate(final HostUpdateCheck.Offer offer) {
        android.app.AlertDialog.Builder b = vn.vuumi.pcdash.ui.DialogStyle.builder(this)
                .setTitle(L10n.t("Update available", "Có bản cập nhật"))
                .setMessage(L10n.t("PCDash host ", "PCDash host ") + offer.versionName + " (" + offer.tag + ")" + NL
                        + L10n.t("You have ", "Bạn đang dùng ") + HostUpdater.installedVersionName(this) + "." + NL + NL
                        + L10n.t("It downloads from this project's GitHub release, is checked against its checksum, and Android asks you to confirm the install. The home phone is unreachable for a moment while it restarts.",
                                "Tải từ release GitHub của dự án, được kiểm tra mã SHA-256, rồi Android hỏi bạn xác nhận cài đặt. Máy ở nhà sẽ mất kết nối một lúc khi khởi động lại."))
                .setPositiveButton(L10n.t("Update", "Cập nhật"), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        downloadAndInstall(offer);
                    }
                })
                .setNegativeButton(L10n.t("Cancel", "Hủy"), null);
        vn.vuumi.pcdash.ui.DialogStyle.show(b, false);
    }

    private void downloadAndInstall(final HostUpdateCheck.Offer offer) {
        if (!HostUpdater.canInstall(this)) {
            Toast.makeText(this, L10n.t("Allow PCDash to install apps, then tap Update again", "Hãy cho phép PCDash cài ứng dụng, rồi bấm Cập nhật lại"), Toast.LENGTH_LONG).show();
            startActivity(HostUpdater.installPermissionIntent(this));
            return;
        }
        if (updateBusy) return;
        updateBusy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    java.io.File apk = HostUpdater.download(MainActivity.this, offer, new HostUpdater.Progress() {
                        @Override
                        public void onPercent(final int percent) {
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    updateBtn.setText(L10n.t("Downloading ", "Đang tải ") + percent + "%");
                                }
                            });
                        }
                    });
                    HostUpdater.install(MainActivity.this, apk);
                } catch (Exception e) {
                    error = e.getMessage() != null ? e.getMessage() : e.toString();
                }
                final String failure = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        updateBusy = false;
                        resetUpdateButton();
                        if (failure != null && !isFinishing()) {
                            showUpdateStatus(L10n.t("Update failed: ", "Cập nhật thất bại: ") + failure, true);
                        }
                    }
                });
            }
        }).start();
    }

    private static boolean isPowerAction(String action) {
        return "wake".equals(action) || "sleep".equals(action) || "hibernate".equals(action)
                || "shutdown".equals(action) || "restart".equals(action) || "lock".equals(action)
                || "logoff".equals(action) || "abort".equals(action);
    }

    @Override
    public void onClick(View v) {
        Object tag = v.getTag();
        if (tag instanceof String) {
            String action = (String) tag;
            if ("check_update".equals(action)) {
                checkForUpdate();
            } else if ("language".equals(action)) {
                AppLanguage.showPicker(this);
            } else if ("app_lock".equals(action)) {
                AppLockSettings.show(this, lockGate);
            } else if ("pc_settings".equals(action)) {
                Intent it = new Intent(this, PcSettingsActivity.class);
                startActivity(it);
            } else if ("remote_devices".equals(action)) {
                startActivity(new Intent(this, RemoteDevicesActivity.class));
            } else if ("tailscale_login".equals(action)) {
                if (currentTailscaleAuthUrl != null && !currentTailscaleAuthUrl.isEmpty()) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(currentTailscaleAuthUrl)));
                }
            } else if ("battery_opt".equals(action)) {
                try {
                    Intent it = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(it);
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Exception ignored) {}
                }
            } else if ("xiaomi_autostart".equals(action)) {
                Intent it = new Intent();
                it.setComponent(new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                try {
                    startActivity(it);
                } catch (Exception e) {
                    try {
                        Intent appSettings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        appSettings.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(appSettings);
                    } catch (Exception ignored) {}
                }
            } else {
                act(action, v);
            }
        }
    }

    private void updateHealthState() {
        boolean running = DashService.isServiceRunning();
        if (healthServiceView != null) {
            healthServiceView.setText((running ? "✓ " : "✗ ") + L10n.t("Host service: ", "Dịch vụ host: ") + (running ? L10n.t("Running", "Đang chạy") : L10n.t("Stopped", "Đã dừng")));
            healthServiceView.setTextColor(running ? Color.rgb(46, 160, 67) : Color.rgb(248, 81, 73));
        }
        boolean listening8091 = DashService.isPort8091Listening();
        if (healthPortView != null) {
            healthPortView.setText((listening8091 ? "✓ " : "! ") + L10n.t("Port :8091: ", "Cổng :8091: ") + (listening8091 ? L10n.t("Listening", "Đang lắng nghe") : L10n.t("Not ready", "Chưa sẵn sàng")));
            healthPortView.setTextColor(listening8091 ? Color.rgb(46, 160, 67) : Color.rgb(219, 109, 40));
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean ignoring = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        if (healthBatteryView != null) {
            healthBatteryView.setText((ignoring ? "✓ " : "! ") + L10n.t("Battery optimization: ", "Tối ưu pin: ") + (ignoring ? L10n.t("Off for PCDash", "Đã loại trừ") : L10n.t("On (may stop PCDash)", "Chưa loại trừ")));
            healthBatteryView.setTextColor(ignoring ? Color.rgb(46, 160, 67) : Color.rgb(219, 109, 40));
        }
        if (btnBattery != null) {
            btnBattery.setVisibility(ignoring ? View.GONE : View.VISIBLE);
        }
    }

    private void updateTailscaleCard(PcnetState s) {
        if (tailscaleStatusView == null) return;
        if (s == null) {
            tailscaleStatusView.setText(L10n.t("Status: not ready", "Trạng thái: Chưa sẵn sàng"));
            tailscaleStatusView.setTextColor(Color.rgb(139, 148, 158));
            if (tailscaleLoginBtn != null) tailscaleLoginBtn.setVisibility(View.GONE);
            return;
        }
        if (s.isRunning()) {
            String ip = (s.tailnetIps != null && !s.tailnetIps.isEmpty()) ? s.tailnetIps.get(0) : "";
            tailscaleStatusView.setText(L10n.t("Status: running", "Trạng thái: Đang chạy") + (!ip.isEmpty() ? " (" + ip + ")" : ""));
            tailscaleStatusView.setTextColor(Color.rgb(46, 160, 67));
            if (tailscaleLoginBtn != null) tailscaleLoginBtn.setVisibility(View.GONE);
        } else if (s.isNeedsLogin()) {
            tailscaleStatusView.setText(L10n.t("Status: Tailscale sign-in needed", "Trạng thái: Cần đăng nhập Tailscale"));
            tailscaleStatusView.setTextColor(Color.rgb(219, 109, 40));
            currentTailscaleAuthUrl = s.authUrl != null ? s.authUrl : "";
            if (tailscaleLoginBtn != null) {
                tailscaleLoginBtn.setVisibility(!currentTailscaleAuthUrl.isEmpty() ? View.VISIBLE : View.GONE);
            }
        } else if (s.isStarting()) {
            tailscaleStatusView.setText(L10n.t("Status: starting...", "Trạng thái: Đang khởi động..."));
            tailscaleStatusView.setTextColor(Color.rgb(139, 148, 158));
            if (tailscaleLoginBtn != null) tailscaleLoginBtn.setVisibility(View.GONE);
        } else if (s.isError()) {
            tailscaleStatusView.setText(L10n.t("Status: error (", "Trạng thái: Lỗi (") + (s.error != null ? s.error : "") + ")");
            tailscaleStatusView.setTextColor(Color.rgb(248, 81, 73));
            if (tailscaleLoginBtn != null) tailscaleLoginBtn.setVisibility(View.GONE);
        } else {
            tailscaleStatusView.setText(L10n.t("Status: stopped", "Trạng thái: Đã dừng"));
            tailscaleStatusView.setTextColor(Color.rgb(139, 148, 158));
            if (tailscaleLoginBtn != null) tailscaleLoginBtn.setVisibility(View.GONE);
        }
    }

    private void act(final String action, View source) {
        if (!PcConn.isConfigured() && lastPcCount == 0) {
            Toast.makeText(this, L10n.t("PC not set up. Open PC settings first", "Chưa cấu hình PC. Vui lòng vào Cài đặt PC"), Toast.LENGTH_LONG).show();
            return;
        }
        if (actionRunning) {
            Toast.makeText(this, L10n.t("Still sending the last command...", "Đang gửi lệnh trước, đợi một chút..."), Toast.LENGTH_SHORT).show();
            return;
        }
        actionRunning = true;
        final ButtonProgress progress = progressFor(source);
        if (progress != null) {
            progress.busy(L10n.t("Sending...", "Đang gửi..."));
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<PcProfile> list = hostSettings.listPcs();
                final PcProfile pc = selectedPc(list);
                final String r = PcConn.doAction(pc, action);
                final boolean ok = !RemoteActionExecutor.isFailure(r);
                final PcConn.PcStatus status = PcConn.status(pc);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        actionRunning = false;
                        if (isFinishing() || isDestroyed()) return;
                        if (progress != null) {
                            progress.done(ok, ok ? L10n.t("Sent", "Đã gửi") : L10n.t("Failed", "Lỗi"));
                        }
                        if (!ok) {
                            Toast.makeText(MainActivity.this, r, Toast.LENGTH_LONG).show();
                        }
                        updateStatus(status, pc, list.size());
                    }
                });
            }
        }, "pcdash-main-action").start();
    }

    private ButtonProgress progressFor(View source) {
        if (!(source instanceof TextView)) return null;
        ButtonProgress p = buttonProgress.get(source);
        if (p == null) {
            p = new ButtonProgress((TextView) source);
            buttonProgress.put(source, p);
        }
        return p;
    }

    private void requestExtras() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                Intent it = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(it);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception ignored) {}
            }
        }
    }
}
