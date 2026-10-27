package vn.vuumi.pcdash;

import vn.vuumi.pcdash.ui.Tap;
import vn.vuumi.pcdash.lock.AppLockGate;
import vn.vuumi.pcdash.ui.DialogStyle;
import vn.vuumi.pcdash.ui.SystemBarInsets;
import vn.vuumi.pcdash.i18n.L10n;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import vn.vuumi.pcdash.net.PcnetState;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RemoteDevicesActivity extends Activity implements ServiceConnection, View.OnClickListener {
    private AppLockGate lockGate;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private RemoteHostController remoteController;
    private boolean isBound = false;
    private PairingManager.PairingCode activeCode = null;

    private Button addDeviceButton;
    private TextView tailscaleStatusView;
    private Button tailscaleLoginBtn;
    private String currentAuthUrl = "";
    private Button cancelPairingBtn;
    private LinearLayout pairingCodeCard;
    private TextView codeView;
    private TextView countdownView;
    private LinearLayout deviceListContainer;
    private LinearLayout commandLogContainer;
    private Button clearLogButton;
    private TextView statusHeaderView;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            updateViewState();
            if (activeCode != null && activeCode.expiresAtEpochMs > System.currentTimeMillis()) {
                ui.postDelayed(this, 1000);
            }
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

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        if (service instanceof DashService.LocalBinder) {
            DashService.LocalBinder binder = (DashService.LocalBinder) service;
            remoteController = binder.getRemoteHostController();
            statusHeaderView.setText(remoteController != null
                    ? L10n.t("Host service ready", "Dịch vụ host sẵn sàng")
                    : L10n.t("Host service unavailable", "Dịch vụ host không khả dụng"));
            updateViewState();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        remoteController = null;
        statusHeaderView.setText(L10n.t("Lost connection to DashService", "Mất kết nối với DashService"));
    }

    @Override
    public void onClick(View v) {
        if (v == addDeviceButton) {
            startPairing();
        } else if (v == tailscaleLoginBtn) {
            if (currentAuthUrl != null && !currentAuthUrl.isEmpty()) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(currentAuthUrl)));
            }
        } else if (v == cancelPairingBtn) {
            cancelPairing();
        } else if (v == clearLogButton) {
            confirmClearCommandLog();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
        title.setText(L10n.t("Remote devices", "Thiết bị điều khiển từ xa"));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(230, 237, 243));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText(L10n.t("Manage paired phones and add new ones", "Quản lý thiết bị đã ghép đôi và thêm thiết bị mới"));
        sub.setTextSize(13);
        sub.setTextColor(Color.rgb(139, 148, 158));
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        statusHeaderView = new TextView(this);
        statusHeaderView.setText(L10n.t("Connecting to the host service...", "Đang kết nối dịch vụ host..."));
        statusHeaderView.setTextSize(12);
        statusHeaderView.setTextColor(Color.rgb(110, 118, 129));
        statusHeaderView.setPadding(0, 0, 0, dp(16));
        root.addView(statusHeaderView);

        addDeviceButton = makeStyledButton(L10n.t("Add device", "Thêm thiết bị"), Color.rgb(27, 102, 42), 0, Color.WHITE);
        root.addView(addDeviceButton);

        LinearLayout tailscaleCard = new LinearLayout(this);
        tailscaleCard.setOrientation(LinearLayout.VERTICAL);
        tailscaleCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        styleCard(tailscaleCard);
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tsLp.setMargins(0, dp(10), 0, dp(12));
        tailscaleCard.setLayoutParams(tsLp);

        TextView tsTitle = new TextView(this);
        tsTitle.setText(L10n.t("TAILSCALE STATUS", "TRẠNG THÁI TAILSCALE"));
        tsTitle.setTextSize(13);
        tsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tsTitle.setTextColor(Color.rgb(230, 237, 243));
        tsTitle.setPadding(0, 0, 0, dp(8));
        tailscaleCard.addView(tsTitle);

        tailscaleStatusView = new TextView(this);
        tailscaleStatusView.setText(L10n.t("Checking...", "Đang kiểm tra..."));
        tailscaleStatusView.setTextSize(13);
        tailscaleStatusView.setTextColor(Color.rgb(139, 148, 158));
        tailscaleCard.addView(tailscaleStatusView);

        tailscaleLoginBtn = makeStyledButton(L10n.t("Sign in to Tailscale", "Đăng nhập Tailscale"), Color.rgb(26, 84, 176), 0, Color.WHITE);
        tailscaleLoginBtn.setVisibility(View.GONE);
        tailscaleCard.addView(tailscaleLoginBtn);

        root.addView(tailscaleCard);

        // Pairing code box (hidden until L10n.t("Add device", "Thêm thiết bị") is pressed)
        pairingCodeCard = new LinearLayout(this);
        pairingCodeCard.setOrientation(LinearLayout.VERTICAL);
        pairingCodeCard.setPadding(dp(20), dp(20), dp(20), dp(20));
        styleCard(pairingCodeCard);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, dp(12), 0, dp(16));
        pairingCodeCard.setLayoutParams(cardLp);
        pairingCodeCard.setVisibility(View.GONE);

        TextView cardTitle = new TextView(this);
        cardTitle.setText(L10n.t("PAIRING CODE (OPEN FOR 5 MINUTES)", "MÃ GHÉP ĐÔI (MỞ TRONG 5 PHÚT)"));
        cardTitle.setTextSize(12);
        cardTitle.setTextColor(Color.rgb(139, 148, 158));
        cardTitle.setGravity(Gravity.CENTER);
        pairingCodeCard.addView(cardTitle);

        codeView = new TextView(this);
        codeView.setTextSize(34);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setTextColor(Color.rgb(88, 166, 255));
        codeView.setGravity(Gravity.CENTER);
        codeView.setPadding(0, dp(8), 0, dp(4));
        pairingCodeCard.addView(codeView);

        countdownView = new TextView(this);
        countdownView.setTextSize(15);
        countdownView.setTextColor(Color.rgb(240, 136, 62));
        countdownView.setGravity(Gravity.CENTER);
        countdownView.setPadding(0, 0, 0, dp(6));
        pairingCodeCard.addView(countdownView);

        TextView guide = new TextView(this);
        guide.setText(L10n.t("Open PCDash Remote on the other phone (same Wi-Fi) and enter this code", "Mở APK PCDash Remote trên máy điều khiển (cùng Wi-Fi) và nhập mã trên"));
        guide.setTextSize(12);
        guide.setTextColor(Color.rgb(110, 118, 129));
        guide.setGravity(Gravity.CENTER);
        guide.setPadding(0, 0, 0, dp(12));
        pairingCodeCard.addView(guide);

        cancelPairingBtn = makeStyledButton(L10n.t("Cancel pairing code", "Hủy mã ghép đôi"), Color.rgb(33, 38, 45), Color.rgb(48, 54, 61), Color.rgb(201, 209, 217));
        pairingCodeCard.addView(cancelPairingBtn);

        root.addView(pairingCodeCard);

        TextView sectionTitle = new TextView(this);
        sectionTitle.setText(L10n.t("Paired devices", "Danh sách thiết bị đã ghép"));
        sectionTitle.setTextSize(17);
        sectionTitle.setTypeface(Typeface.DEFAULT_BOLD);
        sectionTitle.setTextColor(Color.rgb(230, 237, 243));
        sectionTitle.setPadding(0, dp(16), 0, dp(10));
        root.addView(sectionTitle);

        deviceListContainer = new LinearLayout(this);
        deviceListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(deviceListContainer);

        TextView logSectionTitle = new TextView(this);
        logSectionTitle.setText(L10n.t("Command log", "Nhật ký lệnh"));
        logSectionTitle.setTextSize(17);
        logSectionTitle.setTypeface(Typeface.DEFAULT_BOLD);
        logSectionTitle.setTextColor(Color.rgb(230, 237, 243));
        logSectionTitle.setPadding(0, dp(20), 0, dp(10));
        root.addView(logSectionTitle);

        clearLogButton = makeDestructiveButton(L10n.t("Clear log", "Xóa nhật ký"));
        root.addView(clearLogButton);

        commandLogContainer = new LinearLayout(this);
        commandLogContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(commandLogContainer);
        setContentView(scroll);
        SystemBarInsets.pad(findViewById(android.R.id.content), SystemBarInsets.ALL);
        lockGate = new AppLockGate(this, 0);
    }

    @Override
    protected void onStart() {
        super.onStart();
        lockGate.onStart();
        Intent svc = new Intent(this, DashService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        isBound = bindService(svc, this, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateViewState();
    }

    @Override
    protected void onStop() {
        lockGate.onStop();
        if (isFinishing() && remoteController != null) {
            remoteController.cancelPairing();
            activeCode = null;
        }
        if (isBound) {
            unbindService(this);
            isBound = false;
        }
        ui.removeCallbacks(ticker);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(ticker);
        if (remoteController != null) {
            remoteController.cancelPairing();
            activeCode = null;
        }
        super.onDestroy();
    }

    private void startPairing() {
        if (remoteController == null) {
            Toast.makeText(this, L10n.t("Host service is not ready", "Dịch vụ host chưa sẵn sàng"), Toast.LENGTH_SHORT).show();
            return;
        }
        addDeviceButton.setEnabled(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final PairingManager.PairingCode code = remoteController.beginPairing();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            addDeviceButton.setEnabled(true);
                            activeCode = code;
                            ui.removeCallbacks(ticker);
                            ui.post(ticker);
                        }
                    });
                } catch (final PairingManager.PairingException e) {
                    final String msg = e.getMessage() != null ? e.getMessage() : L10n.t("Pairing error", "Lỗi ghép đôi");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            addDeviceButton.setEnabled(true);
                            Toast.makeText(RemoteDevicesActivity.this, msg, Toast.LENGTH_LONG).show();
                            DialogStyle.show(DialogStyle.builder(RemoteDevicesActivity.this)
                                    .setTitle(L10n.t("Pairing", "Thông báo ghép đôi"))
                                    .setMessage(msg)
                                    .setPositiveButton(L10n.t("Close", "Đóng"), null), false);
                        }
                    });
                } catch (final Exception e) {
                    final String msg = e.getMessage() != null ? e.getMessage() : L10n.t("System error", "Lỗi hệ thống");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            addDeviceButton.setEnabled(true);
                            Toast.makeText(RemoteDevicesActivity.this, msg, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void cancelPairing() {
        if (remoteController != null) {
            remoteController.cancelPairing();
        }
        activeCode = null;
        ui.removeCallbacks(ticker);
        updateViewState();
        Toast.makeText(this, L10n.t("Pairing session cancelled", "Đã hủy phiên ghép đôi"), Toast.LENGTH_SHORT).show();
    }

    private void updateViewState() {
        if (remoteController == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final PcnetState tsState = (remoteController != null && remoteController.getHostTailnet() != null)
                        ? remoteController.getHostTailnet().state()
                        : null;
                final List<RemoteDeviceStore.RemoteDevice> devs = remoteController != null
                        ? remoteController.listDevices()
                        : null;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        applyViewStateWithTailnet(devs, tsState);
                    }
                });
            }
        }).start();
    }

    private void applyViewStateWithTailnet(List<RemoteDeviceStore.RemoteDevice> devs, PcnetState tsState) {
        if (remoteController == null) return;
        long now = System.currentTimeMillis();
        RemoteDevicesPresenter.ViewState vs = RemoteDevicesPresenter.present(activeCode, devs, now, tsState);

        if (tailscaleStatusView != null) {
            tailscaleStatusView.setText(vs.tailscaleStatus);
            if (vs.tailscaleRunning) {
                tailscaleStatusView.setTextColor(Color.rgb(46, 160, 67));
            } else if (vs.showLoginButton) {
                tailscaleStatusView.setTextColor(Color.rgb(219, 109, 40));
            } else {
                tailscaleStatusView.setTextColor(Color.rgb(139, 148, 158));
            }
        }
        if (tailscaleLoginBtn != null) {
            if (vs.showLoginButton && vs.authUrl != null && !vs.authUrl.isEmpty()) {
                currentAuthUrl = vs.authUrl;
                tailscaleLoginBtn.setVisibility(View.VISIBLE);
            } else {
                tailscaleLoginBtn.setVisibility(View.GONE);
            }
        }

        if (vs.hasActiveCode) {
            pairingCodeCard.setVisibility(View.VISIBLE);
            codeView.setText(vs.pairingCode);
            countdownView.setText(L10n.t("Expires in: ", "Hết hạn sau: ") + vs.countdownText);
        } else {
            if (activeCode != null) {
                activeCode = null;
            }
            pairingCodeCard.setVisibility(View.GONE);
        }
        renderDeviceList(vs.devices);
        renderCommandLog();
    }

    private void confirmClearCommandLog() {
        DialogStyle.show(DialogStyle.builder(this)
                .setTitle(L10n.t("Confirm delete", "Xác nhận xóa"))
                .setMessage(L10n.t("Clear the whole command log?", "Xóa toàn bộ nhật ký lệnh?"))
                .setPositiveButton(L10n.t("Delete", "Xóa"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        clearCommandLog();
                    }
                })
                .setNegativeButton(L10n.t("Cancel", "Hủy"), null), true);
    }

    private void clearCommandLog() {
        if (remoteController == null || remoteController.commandLog() == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                remoteController.commandLog().clear();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        renderCommandLog();
                        Toast.makeText(RemoteDevicesActivity.this, L10n.t("Command log cleared", "Đã xóa nhật ký lệnh"), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private void renderCommandLog() {
        if (commandLogContainer == null) return;
        commandLogContainer.removeAllViews();

        CommandLog log = remoteController != null ? remoteController.commandLog() : null;
        List<CommandLog.LogEntry> entries = log != null ? log.getEntries(20) : null;

        if (entries == null || entries.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText(L10n.t("No commands yet.", "Chưa có nhật ký lệnh nào."));
            emptyView.setTextSize(14);
            emptyView.setTextColor(Color.rgb(139, 148, 158));
            emptyView.setPadding(0, dp(8), 0, dp(24));
            commandLogContainer.addView(emptyView);
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault());
        for (CommandLog.LogEntry entry : entries) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            styleCard(row);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(3), 0, dp(6));
            row.setLayoutParams(lp);

            TextView textInfo = new TextView(this);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            textInfo.setLayoutParams(textLp);
            String devDisplay = entry.device != null && !entry.device.isEmpty() ? entry.device : L10n.t("(no name)", "(không tên)");
            textInfo.setText(sdf.format(new Date(entry.ts)) + "  " + devDisplay + L10n.t("\nCommand: ", "\nLệnh: ") + entry.action);
            textInfo.setTextSize(13);
            textInfo.setTextColor(Color.rgb(201, 209, 217));
            row.addView(textInfo);

            TextView statusIcon = new TextView(this);
            statusIcon.setText(entry.ok ? "✓" : "✗");
            statusIcon.setTextSize(16);
            statusIcon.setTypeface(Typeface.DEFAULT_BOLD);
            statusIcon.setTextColor(entry.ok ? Color.rgb(46, 160, 67) : Color.rgb(248, 81, 73));
            statusIcon.setPadding(dp(12), 0, 0, 0);
            row.addView(statusIcon);

            commandLogContainer.addView(row);
        }
    }

    private void renderDeviceList(List<RemoteDevicesPresenter.DeviceRow> devices) {
        deviceListContainer.removeAllViews();

        if (devices == null || devices.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText(L10n.t("No paired devices yet.", "Chưa có thiết bị nào được ghép đôi."));
            emptyView.setTextSize(14);
            emptyView.setTextColor(Color.rgb(139, 148, 158));
            emptyView.setPadding(0, dp(8), 0, dp(24));
            deviceListContainer.addView(emptyView);
            return;
        }

        Map<String, Integer> nameCounts = new HashMap<String, Integer>();
        for (RemoteDevicesPresenter.DeviceRow row : devices) {
            String n = (row.name != null && !row.name.trim().isEmpty()) ? row.name.trim() : L10n.t("(Unnamed device)", "(Thiết bị không tên)");
            Integer c = nameCounts.get(n);
            nameCounts.put(n, c == null ? 1 : c + 1);
        }

        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());

        for (final RemoteDevicesPresenter.DeviceRow row : devices) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(16), dp(16), dp(16), dp(16));
            styleCard(item);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(6), 0, dp(12));
            item.setLayoutParams(lp);

            String baseName = (row.name != null && !row.name.trim().isEmpty()) ? row.name.trim() : L10n.t("(Unnamed device)", "(Thiết bị không tên)");
            Integer count = nameCounts.get(baseName);
            final String displayName;
            if (count != null && count > 1) {
                String suffix = "";
                if (row.nodeId != null && !row.nodeId.trim().isEmpty()) {
                    String clean = row.nodeId.trim();
                    suffix = clean.length() > 6 ? clean.substring(clean.length() - 6) : clean;
                } else if (row.id != null && !row.id.trim().isEmpty()) {
                    String clean = row.id.trim();
                    suffix = clean.length() > 6 ? clean.substring(clean.length() - 6) : clean;
                }
                displayName = !suffix.isEmpty() ? baseName + " (..." + suffix + ")" : baseName;
            } else {
                displayName = baseName;
            }

            TextView nameText = new TextView(this);
            nameText.setText(displayName);
            nameText.setTextSize(16);
            nameText.setTypeface(Typeface.DEFAULT_BOLD);
            nameText.setTextColor(Color.rgb(230, 237, 243));
            item.addView(nameText);

            TextView nodeText = new TextView(this);
            String nodeDisplay = (row.nodeId != null && !row.nodeId.trim().isEmpty()) ? row.nodeId.trim() : L10n.t("(No node ID yet)", "(Chưa có Node ID)");
            nodeText.setText("Node ID: " + nodeDisplay);
            nodeText.setTextSize(12);
            nodeText.setTextColor(Color.rgb(139, 148, 158));
            nodeText.setPadding(0, dp(2), 0, dp(4));
            item.addView(nodeText);

            String pairedStr = row.createdAt > 0L ? sdf.format(new Date(row.createdAt)) : L10n.t("Unknown", "Không rõ");
            String lastUsedStr = row.lastUsedAt > 0L ? sdf.format(new Date(row.lastUsedAt)) : L10n.t("Unknown", "Không rõ");
            TextView timeText = new TextView(this);
            timeText.setText(L10n.t("Paired: ", "Ghép đôi: ") + pairedStr + L10n.t(" · Last used: ", " · Dùng lần cuối: ") + lastUsedStr);
            timeText.setTextSize(12);
            timeText.setTextColor(Color.rgb(110, 118, 129));
            timeText.setPadding(0, 0, 0, dp(10));
            item.addView(timeText);

            Button delBtn = makeDestructiveButton(L10n.t("Remove device", "Xóa thiết bị"));
            Tap.wire(delBtn, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(row, displayName);
                }
            });
            item.addView(delBtn);

            deviceListContainer.addView(item);
        }
    }

    private void confirmDelete(final RemoteDevicesPresenter.DeviceRow row, final String displayName) {
        DialogStyle.show(DialogStyle.builder(this)
                .setTitle(L10n.t("Confirm delete", "Xác nhận xóa"))
                .setMessage(L10n.t("Remove device ", "Xóa thiết bị ") + displayName + L10n.t("? It loses control access right away.", "? Thiết bị sẽ mất quyền điều khiển ngay."))
                .setPositiveButton(L10n.t("Delete", "Xóa"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        performDelete(row);
                    }
                })
                .setNegativeButton(L10n.t("Cancel", "Hủy"), null), true);
    }

    private void performDelete(final RemoteDevicesPresenter.DeviceRow row) {
        if (remoteController == null) {
            Toast.makeText(this, L10n.t("Host service is not ready", "Dịch vụ host chưa sẵn sàng"), Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    RemoteDevicesPresenter.DeleteAction action = RemoteDevicesPresenter.createDeleteAction(row);
                    action.execute(remoteController);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(RemoteDevicesActivity.this, L10n.t("Removed device: ", "Đã xóa thiết bị: ") + row.name, Toast.LENGTH_SHORT).show();
                            updateViewState();
                        }
                    });
                } catch (final IOException e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(RemoteDevicesActivity.this, L10n.t("Could not remove device: ", "Lỗi khi xóa thiết bị: ") + e.getMessage(), Toast.LENGTH_LONG).show();
                            updateViewState();
                        }
                    });
                }
            }
        }).start();
    }

    private Button makeStyledButton(String text, int fill, int stroke, int textColor) {
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
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        Tap.wire(b, this);
        return b;
    }

    private Button makeDestructiveButton(String text) {
        Button b = new Button(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        b.setLayoutParams(lp);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        int red = Color.rgb(248, 81, 73);
        b.setTextColor(red);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.rgb(34, 20, 22));
        gd.setStroke(dp(1), red);
        gd.setCornerRadius(dp(8));
        if (Build.VERSION.SDK_INT >= 21) {
            b.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(40, 248, 81, 73)), gd, null));
        } else {
            b.setBackground(gd);
        }
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        Tap.wire(b, this);
        return b;
    }
}
