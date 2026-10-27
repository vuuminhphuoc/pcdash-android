package vn.vuumi.pcdash;

import vn.vuumi.pcdash.ui.SystemBarInsets;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
                    final PcConn.PcStatus status = PcConn.status();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            updateStatus(status);
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

    private void updateStatusDot(int color) {
        if (statusDot == null) return;
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        statusDot.setBackground(dot);
    }

    private void updateStatus(PcConn.PcStatus status) {
        if (statusView == null || status == null) return;
        statusView.setText(status.line);
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

        TextView title = new TextView(this);
        title.setText("PCDash");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(230, 237, 243));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText(L10n.t("Remote control for your PC", "Điều khiển PC từ xa"));
        sub.setTextSize(13);
        sub.setTextColor(Color.rgb(139, 148, 158));
        sub.setPadding(0, dp(4), 0, dp(18));
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

        Button pcSettingsBtn = makeStyledButton(L10n.t("PC settings", "Cài đặt PC"), Color.rgb(33, 38, 45), Color.rgb(48, 54, 61), Color.rgb(201, 209, 217), 48, "pc_settings");
        LinearLayout.LayoutParams psLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        psLp.setMargins(0, 0, 0, dp(14));
        pcSettingsBtn.setLayoutParams(psLp);
        root.addView(pcSettingsBtn);

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

        healthCard.addView(makeStyledButton(L10n.t("Autostart settings (Xiaomi / MIUI)", "Cài đặt tự khởi chạy (Xiaomi / MIUI)"), Color.rgb(33, 38, 45), Color.rgb(48, 54, 61), Color.rgb(201, 209, 217), 40, "xiaomi_autostart"));

        root.addView(healthCard);

        updateHealthState();

        TextView info = new TextView(this);
        info.setTextSize(12);
        info.setTextColor(Color.rgb(110, 118, 129));
        info.setPadding(0, dp(20), 0, dp(16));
        info.setText(WebDashboard.infoText(this));
        root.addView(info);

        setContentView(scroll);
        SystemBarInsets.pad(findViewById(android.R.id.content), SystemBarInsets.ALL);

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
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(tailnetPoll);
        ui.post(tailnetPoll);
        PcSettings pcSettings = new PcSettings(this);
        PcConn.configure(pcSettings);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final PcConn.PcStatus status = PcConn.status();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        updateStatus(status);
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
        if (Build.VERSION.SDK_INT >= 21) {
            b.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), gd, null));
        } else {
            b.setBackground(gd);
        }
        b.setMinimumHeight(dp(minHeightDp));
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setTag(action);
        b.setOnClickListener(this);
        return b;
    }

    @Override
    public void onClick(View v) {
        Object tag = v.getTag();
        if (tag instanceof String) {
            String action = (String) tag;
            if ("pc_settings".equals(action)) {
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
                act(action);
            }
        }
    }

    private void updateHealthState() {
        boolean running = DashService.isServiceRunning();
        if (healthServiceView != null) {
            healthServiceView.setText(L10n.t("Host service: ", "Dịch vụ host: ") + (running ? L10n.t("Running", "Đang chạy") : L10n.t("Stopped", "Đã dừng")));
            healthServiceView.setTextColor(running ? Color.rgb(46, 160, 67) : Color.rgb(248, 81, 73));
        }
        boolean listening8091 = DashService.isPort8091Listening();
        if (healthPortView != null) {
            healthPortView.setText(L10n.t("Port :8091: ", "Cổng :8091: ") + (listening8091 ? L10n.t("Listening", "Đang lắng nghe") : L10n.t("Not ready", "Chưa sẵn sàng")));
            healthPortView.setTextColor(listening8091 ? Color.rgb(46, 160, 67) : Color.rgb(219, 109, 40));
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean ignoring = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        if (healthBatteryView != null) {
            healthBatteryView.setText(L10n.t("Battery optimization: ", "Tối ưu pin: ") + (ignoring ? L10n.t("Off for PCDash", "Đã loại trừ") : L10n.t("On (may stop PCDash)", "Chưa loại trừ")));
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

    private void act(final String action) {
        if (!PcConn.isConfigured()) {
            Toast.makeText(this, L10n.t("PC not set up. Open PC settings first", "Chưa cấu hình PC. Vui lòng vào Cài đặt PC"), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, L10n.t("Sending: ", "Đang gửi: ") + action, Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String r = PcConn.doAction(action);
                final PcConn.PcStatus status = PcConn.status();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, r, Toast.LENGTH_LONG).show();
                        updateStatus(status);
                    }
                });
            }
        }).start();
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
