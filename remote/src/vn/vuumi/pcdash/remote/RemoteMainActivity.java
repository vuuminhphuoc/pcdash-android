package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.ui.DialogStyle;
import vn.vuumi.pcdash.ui.SystemBarInsets;
import vn.vuumi.pcdash.i18n.L10n;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.net.Uri;
import vn.vuumi.pcdash.net.PcnetState;
import vn.vuumi.pcdash.net.PcnetBridge;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class RemoteMainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private static final ThreadFactory WORKER_THREAD_FACTORY = new ThreadFactory() {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "RemoteWorker-" + threadNumber.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    };

    private final ExecutorService executor = Executors.newFixedThreadPool(4, WORKER_THREAD_FACTORY);
    // Mở/đóng cầu tsnet có thể mất nhiều giây: chạy tuần tự ngoài luồng giao diện.
    private final ExecutorService lifecycleExecutor = Executors.newSingleThreadExecutor(WORKER_THREAD_FACTORY);
    private RemoteController controller;
    private RemoteConfigStore store;
    private static final int REQUEST_CODE_DANGEROUS_AUTH = 1002;
    private static final int REQUEST_CODE_FORGET_AUTH = 1003;
    private String pendingDangerousAction = null;
    private String pendingDangerousToast = null;

    private boolean isBusy = false;
    private boolean isPolling = false;
    private volatile boolean isStopped = false;
    private volatile boolean isDestroyed = false;

    private Future<?> pollFuture = null;
    private Future<?> actionFuture = null;
    private Future<?> discoveryFuture = null;
    private Future<?> pairingFuture = null;
    private Future<?> metricsFuture = null;
    private Future<?> historyFuture = null;
    private Future<?> quotaFuture = null;
    private MetricsSnapshot lastSnapshot = null;
    private boolean isMetricsOld = false;
    private final PollGeneration statusGeneration = new PollGeneration();
    private final PollGeneration metricsGeneration = new PollGeneration();
    private final PollGeneration historyGeneration = new PollGeneration();
    private final PollGeneration quotaGeneration = new PollGeneration();

    // Navigation & Tabs
    private FrameLayout contentContainer;
    private MainTab shownTab;
    private BottomNavBar bottomNavBar;
    private TabNavigator navigator;
    private TabContent activeTabContent = null;
    // android.window.OnBackInvokedCallback on API 33+; Object so older phones never load the class.
    private Object backToControlCallback;
    private boolean backCallbackRegistered;
    // True once controller.open() has finished for this start, so a quick action can be sent.
    private boolean sessionReady;

    private ControlTabView controlTabView;
    private MonitorTabView monitorTabView;
    private LogTabView logTabView;
    private QuotaTabView quotaTabView;
    private int chartMinutes = 60;

    // Unpaired UI views
    private LinearLayout discoveredHostsLayout;
    private EditText ipInputView;
    private EditText codeInputView;
    private TextView pairingStatusView;
    private ProgressBar progressBar;
    private LoginPanel loginPanel;
    private EditText deviceNameInputView;

    // Update state & UI
    public enum UpdateState {
        IDLE,
        AVAILABLE,
        DOWNLOADING,
        INSTALL_PERMISSION,
        ERROR
    }

    private UpdateState updateState = UpdateState.IDLE;
    private UpdateInfo availableUpdate = null;
    private int currentDownloadProgress = 0;
    private long currentDownloadBytes = 0;
    private String updateErrorMessage = null;
    private AtomicCancelFlag downloadCancelFlag = null;
    private Future<?> updateDownloadFuture = null;
    private Future<?> updateCheckFuture = null;
    private boolean manualUpdateCheck = false;
    private boolean hasCheckedUpdateInitial = false;
    private ApkInstaller apkInstaller = null;

    private static final long UPDATE_CHECK_INTERVAL_MS = 10 * 60 * 1000L; // 10 minutes
    private final Runnable periodicUpdateCheckTask = new Runnable() {
        @Override
        public void run() {
            if (isStopped || isDestroyed || controller == null || !controller.isPaired()) {
                return;
            }
            triggerUpdateCheck();
            ui.postDelayed(this, UPDATE_CHECK_INTERVAL_MS);
        }
    };

    private final BroadcastReceiver updateResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !UpdateInstaller.ACTION_UPDATE_RESULT.equals(intent.getAction())) {
                return;
            }
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String extraMsg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(confirmIntent);
                }
                return;
            }

            if (status == PackageInstaller.STATUS_SUCCESS) {
                Toast.makeText(RemoteMainActivity.this, L10n.t("Update installed!", "Cập nhật thành công!"), Toast.LENGTH_LONG).show();
                updateState = UpdateState.IDLE;
                availableUpdate = null;
                updateUpdateCardUI();
                return;
            }

            String errorMsg = PackageInstallerStatus.getStatusMessage(status, extraMsg);
            updateErrorMessage = errorMsg;
            updateState = UpdateState.ERROR;
            updateUpdateCardUI();
        }
    };

    private final Runnable pollStatusTask = new Runnable() {
        @Override
        public void run() {
            if (!isPolling || isStopped || isDestroyed || controller == null || !controller.isPaired()) {
                return;
            }
            if (pollFuture != null && !pollFuture.isDone()) {
                pollFuture.cancel(true);
            }
            int gen = statusGeneration.next();
            pollFuture = executor.submit(new PollWorker(RemoteMainActivity.this, gen));
        }
    };

    private final Runnable pollMetricsTask = new Runnable() {
        @Override
        public void run() {
            if (!isPolling || isStopped || isDestroyed || controller == null || !controller.isPaired()) {
                return;
            }
            if (metricsFuture != null && !metricsFuture.isDone()) {
                metricsFuture.cancel(true);
            }
            int gen = metricsGeneration.next();
            metricsFuture = executor.submit(new MetricsWorker(RemoteMainActivity.this, gen));
        }
    };

    private static class PollWorker implements Runnable {
        private final WeakReference<RemoteMainActivity> activityRef;
        private final int generation;
        private boolean isUiPass = false;
        private RemoteApiClient.RemoteStatus status;
        private Exception error;

        PollWorker(RemoteMainActivity activity, int generation) {
            this.activityRef = new WeakReference<RemoteMainActivity>(activity);
            this.generation = generation;
        }

        @Override
        public void run() {
            RemoteMainActivity act = activityRef.get();
            if (act == null || act.isStopped || act.isDestroyed) {
                return;
            }
            if (!isUiPass) {
                if (!act.isPolling || act.controller == null || !act.controller.isPaired() || !act.statusGeneration.isValid(generation)) {
                    return;
                }
                try {
                    status = act.controller.getStatus();
                } catch (Exception e) {
                    error = e;
                }
                if (!act.statusGeneration.isValid(generation)) {
                    return;
                }
                isUiPass = true;
                act.ui.post(this);
            } else {
                act.onStatusDeliver(status, error, generation);
            }
        }
    }

    private void onStatusDeliver(RemoteApiClient.RemoteStatus status, Exception error, int generation) {
        if (isStopped || isDestroyed || !statusGeneration.isValid(generation)) return;
        if (error instanceof RemoteApiClient.DeviceRevokedException) {
            handleDeviceRevoked();
            return;
        }
        if (status != null && !hasCheckedUpdateInitial) {
            hasCheckedUpdateInitial = true;
            triggerUpdateCheck();
        }
        if (controlTabView != null) {
            controlTabView.updateStatus(status, error);
        }
        if (!isStopped && !isDestroyed && isPolling && navigator != null && navigator.current() == MainTab.CONTROL && statusGeneration.isValid(generation)) {
            ui.postDelayed(pollStatusTask, 5000);
        }
    }

    private static class MetricsWorker implements Runnable {
        private final WeakReference<RemoteMainActivity> activityRef;
        private final int generation;
        private boolean isUiPass = false;
        private MetricsSnapshot snapshot;
        private Exception error;

        MetricsWorker(RemoteMainActivity activity, int generation) {
            this.activityRef = new WeakReference<RemoteMainActivity>(activity);
            this.generation = generation;
        }

        @Override
        public void run() {
            RemoteMainActivity act = activityRef.get();
            if (act == null || act.isStopped || act.isDestroyed) {
                return;
            }
            if (!isUiPass) {
                if (!act.isPolling || act.controller == null || !act.controller.isPaired() || !act.metricsGeneration.isValid(generation)) {
                    return;
                }
                try {
                    String json = act.controller.getMetrics();
                    snapshot = MetricsParser.parse(json);
                } catch (Exception e) {
                    error = e;
                }
                if (!act.metricsGeneration.isValid(generation)) {
                    return;
                }
                isUiPass = true;
                act.ui.post(this);
            } else {
                act.onMetricsDeliver(snapshot, error, generation);
            }
        }
    }

    private void onMetricsDeliver(MetricsSnapshot snapshot, Exception error, int generation) {
        if (isStopped || isDestroyed || !metricsGeneration.isValid(generation)) return;
        if (error instanceof RemoteApiClient.DeviceRevokedException) {
            handleDeviceRevoked();
            return;
        }
        if (error != null) {
            isMetricsOld = true;
            if (monitorTabView != null) {
                if (lastSnapshot != null) {
                    monitorTabView.updateMetrics(lastSnapshot, true);
                } else {
                    monitorTabView.updateMetricsError(L10n.t("Could not load metrics: ", "Lỗi tải số liệu: ") + (error.getMessage() != null ? error.getMessage() : "Timeout"));
                }
            }
        } else {
            lastSnapshot = snapshot;
            isMetricsOld = false;
            if (monitorTabView != null) {
                monitorTabView.updateMetrics(snapshot, false);
            }
        }
        if (!isStopped && !isDestroyed && isPolling && navigator != null && navigator.current() == MainTab.MONITOR && metricsGeneration.isValid(generation)) {
            ui.postDelayed(pollMetricsTask, 3000);
        }
    }

    private void handleDeviceRevoked() {
        isPolling = false;
        statusGeneration.invalidate();
        metricsGeneration.invalidate();
        historyGeneration.invalidate();
        ui.removeCallbacks(pollStatusTask);
        ui.removeCallbacks(pollMetricsTask);
        if (pollFuture != null) {
            pollFuture.cancel(true);
            pollFuture = null;
        }
        if (metricsFuture != null) {
            metricsFuture.cancel(true);
            metricsFuture = null;
        }
        lastSnapshot = null;
        isMetricsOld = false;
        if (activeTabContent != null) {
            activeTabContent.onHide();
        }
        if (quotaFuture != null) {
            quotaFuture.cancel(true);
            quotaFuture = null;
        }
        quotaGeneration.invalidate();
        lifecycleExecutor.submit(new Runnable() {
            @Override
            public void run() {
                if (controller != null) {
                    controller.forgetHost();
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed) return;
                        Toast.makeText(RemoteMainActivity.this, L10n.t("This phone was removed on the home phone. Pair again.", "Thiết bị đã bị xóa khỏi máy ở nhà. Hãy ghép đôi lại."), Toast.LENGTH_LONG).show();
                        renderScreen();
                        startTailscaleBegin();
                        if (pairingStatusView != null) {
                            pairingStatusView.setText(L10n.t("This phone was removed on the home phone. Pair again.", "Thiết bị đã bị xóa khỏi máy ở nhà. Hãy ghép đôi lại."));
                        }
                    }
                });
            }
        });
    }

    private void cleanupUpdateCache() {
        try {
            File[] files = getCacheDir().listFiles();
            if (files == null) return;
            for (File f : files) {
                String n = f.getName();
                if (n.equals("pcdash-remote-update.apk") || (n.startsWith("pcdash_upd_") && n.endsWith(".tmp"))) {
                    f.delete();
                }
            }
        } catch (Exception ignored) {}
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
        executor.submit(new Runnable() {
            @Override
            public void run() {
                cleanupUpdateCache();
            }
        });

        File filesDir = getFilesDir();
        File configFile = new File(filesDir, "remote_config.json");
        File stateDir = new File(filesDir, "tsnet");

        store = new RemoteConfigStore(configFile);
        PcnetBridge bridge = new PcnetBridge();
        PairingClient pairing = new PairingClient();
        RemoteApiClient api = new RemoteApiClient();
        controller = new RemoteController(pairing, bridge, store, api, stateDir);

        navigator = new TabNavigator(new TabNavigator.Listener() {
            @Override
            public void onTabSelected(MainTab tab) {
                switchTab(tab);
            }
        });

        if (savedInstanceState != null) {
            String savedTabName = savedInstanceState.getString("active_tab");
            if (savedTabName != null) {
                try {
                    MainTab savedTab = MainTab.valueOf(savedTabName);
                    navigator.select(savedTab);
                } catch (Exception ignored) {}
            }
        }

        // OLED: pure black window, status bar, navigation bar and action bar so unlit pixels stay off.
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
        if (getActionBar() != null) {
            getActionBar().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
        }

        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Color.rgb(0, 0, 0));

        contentContainer = new FrameLayout(this);
        contentContainer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
        rootLayout.addView(contentContainer);

        bottomNavBar = new BottomNavBar(this, new TabNavigator.Listener() {
            @Override
            public void onTabSelected(MainTab tab) {
                if (tab == MainTab.MORE) {
                    showMoreSheet();
                    return;
                }
                navigator.select(tab);
            }
        });
        rootLayout.addView(bottomNavBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // The tab bar pads itself for the navigation bar; the rest of the screen stays below the status
        // bar, and the content area shrinks above the keyboard (pairing screen fields).
        SystemBarInsets.pad(rootLayout, SystemBarInsets.LEFT | SystemBarInsets.TOP | SystemBarInsets.RIGHT | SystemBarInsets.IME);
        setContentView(rootLayout);

        IntentFilter filter = new IntentFilter(UpdateInstaller.ACTION_UPDATE_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updateResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateResultReceiver, filter);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (navigator != null) {
            outState.putString("active_tab", navigator.current().name());
        }
    }

    // Android 12 and older. From Android 13 the manifest opts in to OnBackInvokedCallback, so back
    // goes through updateBackCallback() and the system back animation works at the Control tab.
    @Override
    public void onBackPressed() {
        if (controller != null && controller.isPaired() && navigator != null && navigator.onBack()) {
            return;
        }
        super.onBackPressed();
    }

    private void updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        boolean wanted = !isDestroyed && controller != null && controller.isPaired()
                && navigator != null && navigator.current() != MainTab.CONTROL;
        if (wanted == backCallbackRegistered) {
            return;
        }
        if (backToControlCallback == null) {
            backToControlCallback = new android.window.OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    if (navigator != null) {
                        navigator.onBack();
                    }
                    updateBackCallback();
                }
            };
        }
        android.window.OnBackInvokedCallback callback = (android.window.OnBackInvokedCallback) backToControlCallback;
        if (wanted) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        } else {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
        }
        backCallbackRegistered = wanted;
    }

    private final Runnable pollTailscaleStateTask = new Runnable() {
        @Override
        public void run() {
            if (isStopped || isDestroyed || (controller != null && controller.isPaired())) {
                return;
            }
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    final PcnetState st = controller != null ? controller.state() : null;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isStopped || isDestroyed) return;
                            if (loginPanel != null && st != null) {
                                loginPanel.updateState(st);
                            }
                            if (!isStopped && !isDestroyed && (controller == null || !controller.isPaired())) {
                                ui.removeCallbacks(pollTailscaleStateTask);
                                ui.postDelayed(pollTailscaleStateTask, 2000);
                            }
                        }
                    });
                }
            });
        }
    };

    private void startTailscaleBegin() {
        lifecycleExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    if (controller != null && controller.getBridge() != null) {
                        File stateDir = new File(getFilesDir(), "tsnet");
                        String hostname = store != null ? store.getOrCreateHostname() : "pcdash-remote";
                        controller.getBridge().begin(stateDir, hostname, PcnetBridge.MODE_REMOTE_DIAL, "", 0, 0);
                    }
                } catch (Exception ignored) {}
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isStopped || isDestroyed) return;
                        ui.removeCallbacks(pollTailscaleStateTask);
                        ui.post(pollTailscaleStateTask);
                    }
                });
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        isStopped = false;
        updateBackCallback();
        lifecycleExecutor.submit(new Runnable() {
            @Override
            public void run() {
                boolean revoked = false;
                try {
                    controller.open();
                } catch (RemoteApiClient.DeviceRevokedException e) {
                    revoked = true;
                } catch (IOException ignored) {}
                final boolean finalRevoked = revoked;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isStopped || isDestroyed) return;
                        if (finalRevoked) {
                            handleDeviceRevoked();
                            return;
                        }
                        renderScreen();
                        if (controller.isPaired()) {
                            isPolling = true;
                            adjustPollersForTab(navigator.current());
                            ui.removeCallbacks(periodicUpdateCheckTask);
                            ui.postDelayed(periodicUpdateCheckTask, UPDATE_CHECK_INTERVAL_MS);
                        } else {
                            startTailscaleBegin();
                        }
                        sessionReady = true;
                        runPendingQuickAction();
                    }
                });
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Already open (for example the tile was used while the app was on screen).
        if (sessionReady) {
            runPendingQuickAction();
        }
    }

    // Wake or Sleep from a launcher shortcut or the Quick Settings tile (see QuickActions).
    private void runPendingQuickAction() {
        String action = QuickActions.take(System.currentTimeMillis());
        if (action == null || isStopped || isDestroyed) {
            return;
        }
        if (controller == null || !controller.isPaired()) {
            Toast.makeText(this, L10n.t("Pair this phone with the host first", "Hãy ghép đôi điện thoại này với host trước"), Toast.LENGTH_LONG).show();
            return;
        }
        if (navigator != null) {
            navigator.select(MainTab.CONTROL);
        }
        handleActionClick(action, "wake".equals(action)
                ? L10n.t("Sending the wake signal...", "Đang gửi tín hiệu bật PC...")
                : L10n.t("Putting the PC to sleep...", "Đang đưa PC vào chế độ Sleep..."));
    }

    @Override
    protected void onStop() {
        super.onStop();
        isStopped = true;
        sessionReady = false;
        isPolling = false;
        statusGeneration.invalidate();
        metricsGeneration.invalidate();
        historyGeneration.invalidate();
        quotaGeneration.invalidate();
        ui.removeCallbacks(pollStatusTask);
        ui.removeCallbacks(pollMetricsTask);
        ui.removeCallbacks(periodicUpdateCheckTask);
        ui.removeCallbacks(pollTailscaleStateTask);
        if (activeTabContent != null) {
            activeTabContent.onHide();
        }
        if (pollFuture != null) {
            pollFuture.cancel(true);
            pollFuture = null;
        }
        if (metricsFuture != null) {
            metricsFuture.cancel(true);
            metricsFuture = null;
        }
        if (actionFuture != null) {
            actionFuture.cancel(true);
            actionFuture = null;
        }
        if (discoveryFuture != null) {
            discoveryFuture.cancel(true);
            discoveryFuture = null;
        }
        if (pairingFuture != null) {
            pairingFuture.cancel(true);
            pairingFuture = null;
        }
        if (updateCheckFuture != null) {
            updateCheckFuture.cancel(true);
            updateCheckFuture = null;
        }
        if (historyFuture != null) {
            historyFuture.cancel(true);
            historyFuture = null;
        }
        if (quotaFuture != null) {
            quotaFuture.cancel(true);
            quotaFuture = null;
        }
        lifecycleExecutor.submit(new Runnable() {
            @Override
            public void run() {
                // Chưa ghép đôi: đang chờ đăng nhập Tailscale trong trình duyệt, dừng tsnet sẽ làm hỏng đăng nhập.
                if (controller.isPaired()) {
                    controller.close();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isDestroyed = true;
        isPolling = false;
        statusGeneration.invalidate();
        metricsGeneration.invalidate();
        historyGeneration.invalidate();
        try {
            unregisterReceiver(updateResultReceiver);
        } catch (Exception ignored) {}
        executor.shutdownNow();
        lifecycleExecutor.shutdown();
    }

    private void renderScreen() {
        if (controller.isPaired()) {
            bottomNavBar.setVisibility(View.VISIBLE);
            initTabViews();
            switchTab(navigator.current());
        } else {
            bottomNavBar.setVisibility(View.GONE);
            renderUnpairedScreen();
        }
    }

    private void initTabViews() {
        if (controlTabView == null) {
            controlTabView = new ControlTabView(this, new ControlTabView.Callback() {
                @Override
                public void onActionClicked(String action, String toastMsg) {
                    handleActionClick(action, toastMsg);
                }

                @Override
                public void onStartUpdate() {
                    startUpdateFlow();
                }

                @Override
                public void onCancelUpdate() {
                    if (downloadCancelFlag != null) {
                        downloadCancelFlag.cancel();
                    }
                    if (updateDownloadFuture != null) {
                        updateDownloadFuture.cancel(true);
                    }
                    updateState = UpdateState.AVAILABLE;
                    updateUpdateCardUI();
                }
            });
        }

        if (monitorTabView == null) {
            monitorTabView = new MonitorTabView(this, new MonitorTabView.Callback() {
                @Override
                public void onChartIntervalChanged(int minutes) {
                    chartMinutes = minutes;
                    fetchHistory(minutes);
                }
            });
        }

        if (logTabView == null) {
            logTabView = new LogTabView(this, new LogTabView.Callback() {
                @Override
                public void onLoadLog(final LogTabView.LogResultCallback cb) {
                    executor.submit(new Runnable() {
                        @Override
                        public void run() {
                            LogParser.LogResponse response = null;
                            Exception error = null;
                            try {
                                if (controller != null) {
                                    String json = controller.getLog(50);
                                    response = LogParser.parse(json);
                                }
                            } catch (Exception e) {
                                error = e;
                            }
                            final LogParser.LogResponse res = response;
                            final Exception err = error;
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (isDestroyed) return;
                                    cb.onResult(res, err);
                                }
                            });
                        }
                    });
                }
            });
        }

        if (quotaTabView == null) {
            quotaTabView = new QuotaTabView(this, new QuotaTabView.Callback() {
                @Override
                public void onLoadQuota(final boolean refresh, final QuotaTabView.QuotaResultCallback cb) {
                    if (quotaFuture != null && !quotaFuture.isDone()) {
                        if (!refresh) {
                            return;
                        }
                        quotaFuture.cancel(true);
                    }
                    final int gen = quotaGeneration.next();
                    quotaFuture = executor.submit(new Runnable() {
                        @Override
                        public void run() {
                            QuotaSnapshot snapshot = null;
                            Exception error = null;
                            try {
                                if (controller != null) {
                                    String json = controller.getQuota(refresh);
                                    snapshot = QuotaParser.parse(json);
                                }
                            } catch (Exception e) {
                                error = e;
                            }
                            if (!quotaGeneration.isValid(gen)) {
                                return;
                            }
                            final QuotaSnapshot res = snapshot;
                            final Exception err = error;
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (isDestroyed || !quotaGeneration.isValid(gen)) return;
                                    if (err instanceof RemoteApiClient.DeviceRevokedException) {
                                        handleDeviceRevoked();
                                        return;
                                    }
                                    cb.onResult(res, err);
                                }
                            });
                        }
                    });
                }
            });
        }
    }

    private TabContent getTabContent(MainTab tab) {
        switch (tab) {
            case CONTROL:
                return controlTabView;
            case MONITOR:
                return monitorTabView;
            case QUOTA:
                return quotaTabView;
            case LOG:
                return logTabView;
            default:
                return controlTabView;
        }
    }

    private void switchTab(MainTab tab) {
        if (activeTabContent != null) {
            activeTabContent.onHide();
        }
        contentContainer.removeAllViews();
        TabContent nextContent = getTabContent(tab);
        activeTabContent = nextContent;
        if (nextContent != null) {
            final View incoming = nextContent.view();
            contentContainer.addView(incoming);
            nextContent.onShow();
            animateTabIn(incoming, shownTab, tab);
        }
        shownTab = tab;
        if (bottomNavBar != null) {
            bottomNavBar.setSelected(tab);
        }
        adjustPollersForTab(tab);
        updateBackCallback();
    }

    // Short fade and slide toward the direction of travel, so content follows the glass pill.
    private void animateTabIn(View incoming, MainTab from, MainTab to) {
        incoming.animate().cancel();
        if (from == null || from == to) {
            incoming.setAlpha(1f);
            incoming.setTranslationX(0f);
            return;
        }
        float dir = to.ordinal() > from.ordinal() ? 1f : -1f;
        float shift = 28f * getResources().getDisplayMetrics().density;
        incoming.setAlpha(0f);
        incoming.setTranslationX(dir * shift);
        incoming.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(240)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .withLayer()
                .start();
    }

    private void adjustPollersForTab(MainTab tab) {
        if (!isPolling || controller == null || !controller.isPaired()) {
            return;
        }
        if (tab == MainTab.CONTROL) {
            metricsGeneration.invalidate();
            historyGeneration.invalidate();
            ui.removeCallbacks(pollMetricsTask);
            if (metricsFuture != null) {
                metricsFuture.cancel(true);
            }
            ui.removeCallbacks(pollStatusTask);
            ui.post(pollStatusTask);
        } else if (tab == MainTab.MONITOR) {
            statusGeneration.invalidate();
            ui.removeCallbacks(pollStatusTask);
            if (pollFuture != null) {
                pollFuture.cancel(true);
            }
            ui.removeCallbacks(pollMetricsTask);
            ui.post(pollMetricsTask);
            fetchHistory(chartMinutes);
        } else {
            statusGeneration.invalidate();
            metricsGeneration.invalidate();
            historyGeneration.invalidate();
            ui.removeCallbacks(pollStatusTask);
            ui.removeCallbacks(pollMetricsTask);
            if (pollFuture != null) {
                pollFuture.cancel(true);
            }
            if (metricsFuture != null) {
                metricsFuture.cancel(true);
            }
        }
    }

    private void showMoreSheet() {
        MoreSheet.show(this, store, getInstalledVersionName(), new MoreSheet.Callback() {
            @Override
            public void onRequireDangerousAuthChanged(final boolean required) {
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (controller != null) {
                                controller.setRequireDangerousAuth(required);
                            } else if (store != null) {
                                store.setRequireDangerousAuth(required);
                            }
                        } catch (final Exception e) {
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(RemoteMainActivity.this, L10n.t("Could not save the setting: ", "Không thể lưu cài đặt: ") + e.getMessage(), Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    }
                });
            }

            @Override
            public void onCheckUpdate() {
                startManualUpdateCheck();
            }

            @Override
            public void onForgetHost() {
                requestForgetHost();
            }
            @Override
            public void onReloginTailscale() {
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        final PcnetState st = controller != null ? controller.state() : null;
                        final String url = (st != null && st.authUrl != null) ? st.authUrl : "";
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isDestroyed) return;
                                if (url != null && !url.isEmpty()) {
                                    try {
                                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                                        startActivity(intent);
                                    } catch (Exception e) {
                                        Toast.makeText(RemoteMainActivity.this, L10n.t("Cannot open the browser: ", "Không thể mở trình duyệt: ") + e.getMessage(), Toast.LENGTH_SHORT).show();
                                    }
                                } else {
                                    Toast.makeText(RemoteMainActivity.this, L10n.t("Tailscale is working", "Tailscale đang hoạt động bình thường"), Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    }
                });
            }


            @Override
            public void onDiagnosticsRequested(final MoreSheet.DiagnosticsCallback callback) {
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        String raw = null;
                        try {
                            if (controller != null && controller.getBridge() != null) {
                                raw = controller.getBridge().diagnostics();
                            }
                        } catch (Exception ignored) {}
                        final String formatted = DiagnosticsFormatter.format(raw);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isDestroyed) return;
                                callback.onDiagnostics(formatted);
                            }
                        });
                    }
                });
            }
        });
    }

    private void handleActionClick(final String action, final String toastMsg) {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        boolean isDeviceSecure = km != null && km.isDeviceSecure();
        boolean requireAuthSetting = controller != null ? controller.isRequireDangerousAuth() : true;

        ActionGuard.AuthRequirement req = ActionGuard.checkAuthRequirement(action, requireAuthSetting, isDeviceSecure);
        if (req == ActionGuard.AuthRequirement.DEVICE_CREDENTIAL) {
            pendingDangerousAction = action;
            pendingDangerousToast = toastMsg;
            Intent intent = km.createConfirmDeviceCredentialIntent(
                    "shutdown".equals(action) ? L10n.t("Confirm PC shutdown", "Xác nhận tắt máy PC") : L10n.t("Confirm PC hibernate", "Xác nhận ngủ đông PC"),
                    L10n.t("Unlock with your PIN or fingerprint to continue", "Vui lòng xác thực mã PIN/vân tay của thiết bị trước khi thực hiện thao tác này"));
            if (intent != null) {
                startActivityForResult(intent, REQUEST_CODE_DANGEROUS_AUTH);
                return;
            }
            confirmActionDialog(action, toastMsg);
        } else if (req == ActionGuard.AuthRequirement.CONFIRM_DIALOG) {
            confirmActionDialog(action, toastMsg);
        } else {
            executeAction(action, toastMsg);
        }
    }

    private void confirmActionDialog(final String action, final String toastMsg) {
        String title = "shutdown".equals(action) ? L10n.t("Confirm shutdown", "Xác nhận tắt máy") : L10n.t("Confirm hibernate", "Xác nhận ngủ đông");
        String msg = "shutdown".equals(action)
                ? L10n.t("Shut down the PC now?", "Bạn có chắc chắn muốn tắt máy PC ngay bây giờ không?")
                : L10n.t("Hibernate the PC now?", "Bạn có chắc chắn muốn đưa PC vào chế độ ngủ đông (Hibernate) không?");
        AlertDialog confirm = DialogStyle.builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(L10n.t("Continue", "Thực hiện"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        executeAction(action, toastMsg);
                    }
                })
                .setNegativeButton(L10n.t("Cancel", "Hủy"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Toast.makeText(RemoteMainActivity.this, L10n.t("Cancelled", "Đã hủy"), Toast.LENGTH_SHORT).show();
                    }
                })
                .create();
        GlassKit.styleAlert(confirm, true);
        confirm.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_DANGEROUS_AUTH) {
            if (resultCode == RESULT_OK && pendingDangerousAction != null) {
                confirmActionDialog(pendingDangerousAction, pendingDangerousToast);
            } else {
                Toast.makeText(this, L10n.t("Authentication failed", "Xác thực không thành công"), Toast.LENGTH_SHORT).show();
            }
            pendingDangerousAction = null;
            pendingDangerousToast = null;
            return;
        }
        if (requestCode == REQUEST_CODE_FORGET_AUTH) {
            if (resultCode == RESULT_OK) {
                confirmForgetHost();
            } else {
                Toast.makeText(this, L10n.t("Authentication failed", "Xác thực không thành công"), Toast.LENGTH_SHORT).show();
            }
            return;
        }
    }

    private void requestForgetHost() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        boolean isDeviceSecure = km != null && km.isDeviceSecure();
        if (ActionGuard.checkForgetHostRequirement(isDeviceSecure) == ActionGuard.AuthRequirement.DEVICE_CREDENTIAL) {
            Intent intent = km.createConfirmDeviceCredentialIntent(L10n.t("Authenticate to forget the host", "Xác thực để quên host"), L10n.t("Unlock with your PIN or fingerprint to unpair", "Vui lòng xác thực mã PIN/vân tay của thiết bị để hủy ghép đôi"));
            if (intent != null) {
                startActivityForResult(intent, REQUEST_CODE_FORGET_AUTH);
                return;
            }
        }
        confirmForgetHost();
    }

    private void confirmForgetHost() {
        AlertDialog forget = DialogStyle.builder(this)
                .setTitle(L10n.t("Forget host", "Quên host"))
                .setMessage(L10n.t("Remove the pairing with this host? To reconnect you will need to pair again on the home LAN.", "Bạn có chắc muốn xóa ghép đôi với host này? Để kết nối lại, bạn sẽ cần ghép đôi lại trên mạng LAN."))
                .setPositiveButton(L10n.t("Forget host", "Quên host"), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        isPolling = false;
                        statusGeneration.invalidate();
                        metricsGeneration.invalidate();
                        historyGeneration.invalidate();
                        ui.removeCallbacks(pollStatusTask);
                        ui.removeCallbacks(pollMetricsTask);
                        if (pollFuture != null) {
                            pollFuture.cancel(true);
                            pollFuture = null;
                        }
                        if (metricsFuture != null) {
                            metricsFuture.cancel(true);
                            metricsFuture = null;
                        }
                        if (actionFuture != null) {
                            actionFuture.cancel(true);
                            actionFuture = null;
                        }
                        if (discoveryFuture != null) {
                            discoveryFuture.cancel(true);
                            discoveryFuture = null;
                        }
                        if (pairingFuture != null) {
                            pairingFuture.cancel(true);
                            pairingFuture = null;
                        }
                        if (updateCheckFuture != null) {
                            updateCheckFuture.cancel(true);
                            updateCheckFuture = null;
                        }
                        if (historyFuture != null) {
                            historyFuture.cancel(true);
                            historyFuture = null;
                        }
                        lastSnapshot = null;
                        isMetricsOld = false;
                        lifecycleExecutor.submit(new Runnable() {
                            @Override
                            public void run() {
                                if (controller != null) {
                                    controller.forgetHost();
                                }
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (isDestroyed) return;
                                        Toast.makeText(RemoteMainActivity.this, L10n.t("Host data removed", "Đã xóa dữ liệu host"), Toast.LENGTH_SHORT).show();
                                        renderScreen();
                                        startTailscaleBegin();
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton(L10n.t("Cancel", "Hủy"), null)
                .create();
        GlassKit.styleAlert(forget, true);
        forget.show();
    }

    private void executeAction(final String action, final String toastMsg) {
        if (isBusy || isStopped || isDestroyed) return;
        isBusy = true;
        if (toastMsg != null) {
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show();
        }
        actionFuture = executor.submit(new ActionWorker(this, action));
    }

    private static class ActionWorker implements Runnable {
        private final WeakReference<RemoteMainActivity> activityRef;
        private final String action;

        ActionWorker(RemoteMainActivity activity, String action) {
            this.activityRef = new WeakReference<RemoteMainActivity>(activity);
            this.action = action;
        }

        @Override
        public void run() {
            RemoteMainActivity act = activityRef.get();
            if (act == null || act.isStopped || act.isDestroyed) return;

            RemoteController ctrl = act.controller;
            if (ctrl == null) return;

            try {
                final RemoteApiClient.RemoteCommandResult res = ctrl.postAction(action);
                final RemoteMainActivity currentAct = activityRef.get();
                if (currentAct == null || currentAct.isStopped || currentAct.isDestroyed) return;

                currentAct.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        RemoteMainActivity a = activityRef.get();
                        if (a == null || a.isStopped || a.isDestroyed) return;
                        a.isBusy = false;
                        String msg = res.ok ? L10n.t("Done: ", "Thành công: ") + action : L10n.t("Error: ", "Lỗi: ") + res.detail;
                        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
                        a.ui.postDelayed(a.pollStatusTask, 1500);
                    }
                });
            } catch (final RemoteApiClient.DeviceRevokedException e) {
                final RemoteMainActivity currentAct = activityRef.get();
                if (currentAct == null || currentAct.isStopped || currentAct.isDestroyed) return;
                currentAct.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        RemoteMainActivity a = activityRef.get();
                        if (a == null || a.isStopped || a.isDestroyed) return;
                        a.isBusy = false;
                        a.handleDeviceRevoked();
                    }
                });
            } catch (final Exception e) {
                final RemoteMainActivity currentAct = activityRef.get();
                if (currentAct == null || currentAct.isStopped || currentAct.isDestroyed) return;

                currentAct.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        RemoteMainActivity a = activityRef.get();
                        if (a == null || a.isStopped || a.isDestroyed) return;
                        a.isBusy = false;
                        Toast.makeText(a, L10n.t("Could not send the command: ", "Lỗi gửi lệnh: ") + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        }
    }

    // =========================================================================
    // UI: CHƯA GHÉP ĐÔI (UNPAIRED)
    // =========================================================================

    private void renderUnpairedScreen() {
        contentContainer.removeAllViews();
        PairingScreen screen = new PairingScreen(this, getInstalledVersionName(), newLoginCallback(), new PairingScreen.Callback() {
            @Override
            public void onFindHostClicked() {
                startUdpDiscovery();
            }

            @Override
            public void onPairClicked() {
                startPairing();
            }
        });
        contentContainer.addView(screen);
        loginPanel = screen.loginPanel;
        deviceNameInputView = screen.deviceNameInput;
        discoveredHostsLayout = screen.discoveredHostsLayout;
        ipInputView = screen.ipInput;
        codeInputView = screen.codeInput;
        progressBar = screen.progressBar;
        pairingStatusView = screen.statusView;

        startUdpDiscovery();
    }

    // Step 1 of the unpaired screen: diagnostics and the Tailscale sign-in page.
    private LoginPanel.Callback newLoginCallback() {
        return new LoginPanel.Callback() {
            @Override
            public void onDiagnosticsClicked() {
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        String raw = null;
                        String stateJson = null;
                        try {
                            if (controller != null && controller.getBridge() != null) {
                                raw = controller.getBridge().diagnostics();
                                stateJson = controller.getBridge().stateJson();
                            }
                        } catch (Exception ignored) {}
                        final String text = L10n.t("State: ", "Trạng thái: ") + stateJson + "\n" + DiagnosticsFormatter.format(raw);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isDestroyed || loginPanel == null) return;
                                loginPanel.showDiagnostics(text);
                            }
                        });
                    }
                });
            }

            @Override
            public void onLoginClicked(String authUrl) {
                if (authUrl != null && !authUrl.isEmpty()) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl));
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(RemoteMainActivity.this, L10n.t("Cannot open the browser: ", "Không thể mở trình duyệt: ") + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }
            }
        };
    }

    void setSelectedHostIp(String ip) {
        if (ipInputView != null && ip != null) {
            ipInputView.setText(ip);
            ipInputView.setSelection(ip.length());
            ipInputView.requestFocus();
        }
        if (pairingStatusView != null) {
            pairingStatusView.setText("");
        }
    }

    private void startUdpDiscovery() {
        if (discoveredHostsLayout == null || isStopped || isDestroyed) return;
        discoveredHostsLayout.removeAllViews();
        TextView searchingTxt = new TextView(this);
        searchingTxt.setText(L10n.t("Looking for PCDash host on the LAN...", "Đang tìm host PCDash trên mạng LAN..."));
        searchingTxt.setTextSize(12);
        searchingTxt.setTextColor(Color.rgb(139, 148, 158));
        searchingTxt.setPadding(0, dpToPx(4), 0, dpToPx(6));
        discoveredHostsLayout.addView(searchingTxt);

        if (discoveryFuture != null) {
            discoveryFuture.cancel(true);
        }
        discoveryFuture = executor.submit(new DiscoveryWorker(this));
    }

    private static class DiscoveryWorker implements Runnable {
        private final WeakReference<RemoteMainActivity> activityRef;

        DiscoveryWorker(RemoteMainActivity activity) {
            this.activityRef = new WeakReference<RemoteMainActivity>(activity);
        }

        @Override
        public void run() {
            RemoteMainActivity act = activityRef.get();
            if (act == null || act.isStopped || act.isDestroyed) return;

            PairingClient pairingClient = act.controller != null ? act.controller.getPairingClient() : null;
            if (pairingClient == null) return;

            final List<InetAddress> hosts = pairingClient.discover(2500);

            final RemoteMainActivity currentAct = activityRef.get();
            if (currentAct == null || currentAct.isStopped || currentAct.isDestroyed) return;

            currentAct.ui.post(new Runnable() {
                @Override
                public void run() {
                    RemoteMainActivity a = activityRef.get();
                    if (a == null || a.isStopped || a.isDestroyed) return;
                    if (a.discoveredHostsLayout == null) return;

                    a.discoveredHostsLayout.removeAllViews();
                    if (hosts == null || hosts.isEmpty()) {
                        TextView emptyTxt = new TextView(a);
                        emptyTxt.setText(L10n.t("No host found. You can enter its IP below.", "Không tìm thấy host tự động. Bạn có thể nhập IP bên dưới."));
                        emptyTxt.setTextSize(12);
                        emptyTxt.setTextColor(Color.rgb(210, 153, 34));
                        emptyTxt.setPadding(0, a.dpToPx(4), 0, a.dpToPx(6));
                        a.discoveredHostsLayout.addView(emptyTxt);
                    } else {
                        for (final InetAddress addr : hosts) {
                            final String ipStr = addr.getHostAddress();
                            Button hostBtn = new Button(a);
                            hostBtn.setText(L10n.t("Use host: ", "Chọn host: ") + ipStr);
                            hostBtn.setTextColor(Color.rgb(230, 237, 243));
                            hostBtn.setBackgroundColor(Color.rgb(46, 160, 67));
                            hostBtn.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    RemoteMainActivity act = activityRef.get();
                                    if (act != null) {
                                        act.setSelectedHostIp(ipStr);
                                        Toast.makeText(act, L10n.t("Selected host ", "Đã chọn host ") + ipStr, Toast.LENGTH_SHORT).show();
                                    }
                                }
                            });
                            a.discoveredHostsLayout.addView(hostBtn);
                            addSpacing(a.discoveredHostsLayout, a.dpToPx(4));
                        }
                    }
                }
            });
        }
    }

    private void startPairing() {
        if (isBusy || isStopped || isDestroyed) return;

        if (loginPanel != null && !loginPanel.isTailscaleReady()) {
            Toast.makeText(this, L10n.t("Sign in to Tailscale in step 1 before pairing", "Vui lòng đăng nhập Tailscale ở Bước 1 trước khi ghép đôi"), Toast.LENGTH_LONG).show();
            if (pairingStatusView != null) {
                pairingStatusView.setText(L10n.t("Finish the Tailscale sign-in in step 1 first.", "Vui lòng hoàn tất đăng nhập Tailscale ở Bước 1 trước."));
            }
            return;
        }
        final String rawIp = ipInputView != null ? ipInputView.getText().toString().trim() : "";
        if (rawIp.isEmpty()) {
            if (pairingStatusView != null) {
                pairingStatusView.setText(L10n.t("Enter or pick the host's IPv4 address", "Vui lòng nhập hoặc chọn IPv4 host"));
            }
            Toast.makeText(this, L10n.t("Enter or pick the host's IPv4 address", "Vui lòng nhập hoặc chọn IPv4 host"), Toast.LENGTH_SHORT).show();
            return;
        }

        final InetAddress hostAddr;
        try {
            hostAddr = InetAddress.getByName(rawIp);
        } catch (Exception e) {
            Toast.makeText(this, L10n.t("Invalid IPv4 address", "Địa chỉ IPv4 không hợp lệ"), Toast.LENGTH_SHORT).show();
            return;
        }

        if (!PairingClient.isRfc1918(hostAddr)) {
            Toast.makeText(this, L10n.t("The address must be a private LAN IPv4 (RFC1918: 192.168.x, 10.x, 172.16-31.x)", "Địa chỉ phải là IPv4 mạng LAN nội bộ (RFC1918: 192.168.x, 10.x, 172.16-31.x)"), Toast.LENGTH_LONG).show();
            return;
        }

        final String code = codeInputView != null ? codeInputView.getText().toString().trim() : "";
        if (code.length() != RemoteController.CODE_LENGTH) {
            Toast.makeText(this, L10n.t("The pairing code must have 6 characters", "Mã ghép đôi phải đủ 6 ký tự"), Toast.LENGTH_SHORT).show();
            return;
        }
        final String deviceName = (deviceNameInputView != null && !deviceNameInputView.getText().toString().trim().isEmpty())
                ? deviceNameInputView.getText().toString().trim()
                : (android.os.Build.MODEL != null ? android.os.Build.MODEL : "Android Remote");

        isBusy = true;
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        if (pairingStatusView != null) pairingStatusView.setText(L10n.t("Connecting to the host...", "Đang kết nối và xác thực với host..."));

        if (pairingFuture != null) {
            pairingFuture.cancel(true);
        }
        pairingFuture = executor.submit(new PairingWorker(this, hostAddr, code, deviceName));
    }

    private static class PairingWorker implements Runnable {
        private final WeakReference<RemoteMainActivity> activityRef;
        private final InetAddress hostAddr;
        private final String code;
        private final String deviceName;

        PairingWorker(RemoteMainActivity activity, InetAddress hostAddr, String code, String deviceName) {
            this.activityRef = new WeakReference<RemoteMainActivity>(activity);
            this.hostAddr = hostAddr;
            this.code = code;
            this.deviceName = deviceName;
        }

        @Override
        public void run() {
            RemoteMainActivity act = activityRef.get();
            if (act == null || act.isStopped || act.isDestroyed) return;

            RemoteController ctrl = act.controller;
            if (ctrl == null) return;

            try {
                ctrl.pair(hostAddr, code, deviceName);

                final RemoteMainActivity currentAct = activityRef.get();
                if (currentAct == null || currentAct.isStopped || currentAct.isDestroyed) return;

                currentAct.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        RemoteMainActivity a = activityRef.get();
                        if (a == null || a.isStopped || a.isDestroyed) return;
                        a.isBusy = false;
                        if (a.progressBar != null) a.progressBar.setVisibility(View.GONE);
                        Toast.makeText(a, L10n.t("Paired!", "Ghép đôi thành công!"), Toast.LENGTH_SHORT).show();
                        a.renderScreen();
                        a.isPolling = true;
                        a.adjustPollersForTab(a.navigator.current());
                    }
                });
            } catch (final Exception e) {
                final RemoteMainActivity currentAct = activityRef.get();
                if (currentAct == null || currentAct.isStopped || currentAct.isDestroyed) return;

                currentAct.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        RemoteMainActivity a = activityRef.get();
                        if (a == null || a.isStopped || a.isDestroyed) return;
                        a.isBusy = false;
                        if (a.progressBar != null) a.progressBar.setVisibility(View.GONE);
                        if (a.pairingStatusView != null) {
                            a.pairingStatusView.setText(L10n.t("Pairing failed: ", "Lỗi ghép đôi: ") + (e.getMessage() != null ? e.getMessage() : L10n.t("Unknown", "Không xác định")));
                        }
                        Toast.makeText(a, L10n.t("Error: ", "Lỗi: ") + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        }
    }

    private void fetchHistory(final int minutes) {
        if (isStopped || isDestroyed || controller == null || !controller.isPaired()) return;
        if (navigator == null || navigator.current() != MainTab.MONITOR) return;
        if (historyFuture != null && !historyFuture.isDone()) {
            historyFuture.cancel(true);
        }
        int gen = historyGeneration.next();
        historyFuture = executor.submit(new HistoryWorker(this, minutes, gen));
    }

    private static class HistoryWorker implements Runnable {
        private final WeakReference<RemoteMainActivity> activityRef;
        private final int minutes;
        private final int generation;

        HistoryWorker(RemoteMainActivity activity, int minutes, int generation) {
            this.activityRef = new WeakReference<RemoteMainActivity>(activity);
            this.minutes = minutes;
            this.generation = generation;
        }

        @Override
        public void run() {
            RemoteMainActivity act = activityRef.get();
            if (act == null || act.isStopped || act.isDestroyed || act.controller == null) return;
            if (!act.historyGeneration.isValid(generation)) return;

            HistoryParser.HistoryData data = null;
            Exception error = null;
            try {
                String json = act.controller.getHistory(minutes);
                data = HistoryParser.parse(json);
            } catch (Exception e) {
                error = e;
            }
            if (!act.historyGeneration.isValid(generation)) return;

            final HistoryParser.HistoryData finalData = data;
            final Exception finalError = error;
            act.ui.post(new Runnable() {
                @Override
                public void run() {
                    RemoteMainActivity a = activityRef.get();
                    if (a == null || a.isStopped || a.isDestroyed) return;
                    a.onHistoryDeliver(finalData, finalError, generation);
                }
            });
        }
    }

    private void onHistoryDeliver(HistoryParser.HistoryData data, Exception error, int generation) {
        if (isStopped || isDestroyed || !historyGeneration.isValid(generation)) return;
        if (navigator == null || navigator.current() != MainTab.MONITOR) return;
        if (monitorTabView != null) {
            monitorTabView.updateHistory(data, error);
        }
    }

    // =========================================================================
    // Self-Update Helpers & Workers
    // =========================================================================

    private void triggerUpdateCheck() {
        if (isStopped || isDestroyed || controller == null || !controller.isPaired()) {
            return;
        }
        if (updateCheckFuture != null && !updateCheckFuture.isDone()) {
            return;
        }
        updateCheckFuture = executor.submit(new UpdateCheckWorker(this));
    }

    private void startManualUpdateCheck() {
        if (controller == null || !controller.isPaired()) return;
        if (updateDownloadFuture != null && !updateDownloadFuture.isDone()) {
            Toast.makeText(this, L10n.t("Downloading the update", "Đang tải bản cập nhật"), Toast.LENGTH_SHORT).show();
            return;
        }
        manualUpdateCheck = true;
        Toast.makeText(this, L10n.t("Checking for updates...", "Đang kiểm tra bản cập nhật..."), Toast.LENGTH_SHORT).show();
        triggerUpdateCheck();
    }

    private void finishManualUpdateCheck(String message) {
        manualUpdateCheck = false;
        if (message != null && !isStopped && !isDestroyed) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    private void onUpdateInfoDeliver(UpdateInfo info, Exception error) {
        final boolean manual = manualUpdateCheck;
        if (manual) finishManualUpdateCheck(null);
        if (isStopped || isDestroyed) return;

        if (error != null) {
            if (error instanceof RemoteApiClient.DeviceRevokedException) {
                handleDeviceRevoked();
                return;
            }
            if (manual) {
                Toast.makeText(this, L10n.t("Cannot check: the home phone is not answering", "Không kiểm tra được: máy ở nhà chưa phản hồi"), Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (info == null) {
            if (manual) {
                Toast.makeText(this, L10n.t("The home phone has no update", "Máy ở nhà chưa có bản cập nhật nào"), Toast.LENGTH_LONG).show();
            }
            return;
        }

        int installedVersionCode = getInstalledVersionCode();
        boolean hasNew = UpdateChecker.isUpdateAvailable(info, installedVersionCode);
        if (manual) {
            Toast.makeText(this, hasNew ? L10n.t("New version v", "Có bản mới v") + info.versionName
                    : L10n.t("You have the latest version (v", "Đang dùng bản mới nhất (v") + getInstalledVersionName() + ")", Toast.LENGTH_LONG).show();
        }
        if (hasNew) {
            this.availableUpdate = info;
            if (this.updateState != UpdateState.DOWNLOADING) {
                this.updateState = UpdateState.AVAILABLE;
                updateUpdateCardUI();
            }
        } else {
            if (this.updateState == UpdateState.AVAILABLE) {
                this.availableUpdate = null;
                this.updateState = UpdateState.IDLE;
                updateUpdateCardUI();
            }
        }
    }

    private void startUpdateFlow() {
        if (availableUpdate == null) return;
        final File apkFile = new File(getCacheDir(), "pcdash-remote-update.apk");

        ApkInstaller inst = getApkInstaller();
        if (!inst.canRequestPackageInstalls()) {
            updateState = UpdateState.INSTALL_PERMISSION;
            updateUpdateCardUI();
            inst.openInstallPermissionSettings();
            Toast.makeText(this, L10n.t("Turn on Allow from this source, then come back and tap Update", "Bật Cho phép từ nguồn này rồi quay lại bấm Cập nhật"), Toast.LENGTH_LONG).show();
            return;
        }

        if (apkFile.exists() && apkFile.length() == availableUpdate.size) {
            proceedInstall(apkFile);
            return;
        }

        updateState = UpdateState.DOWNLOADING;
        currentDownloadProgress = 0;
        currentDownloadBytes = 0;
        downloadCancelFlag = new AtomicCancelFlag();
        updateUpdateCardUI();

        updateDownloadFuture = executor.submit(new DownloadWorker(this, availableUpdate, apkFile, downloadCancelFlag));
    }

    private void proceedInstall(final File apkFile) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                ApkInstaller inst = getApkInstaller();
                UpdateFlow.startInstall(inst, apkFile, new UpdateFlow.FlowCallback() {
                    @Override
                    public void onPermissionNeeded() {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                updateState = UpdateState.INSTALL_PERMISSION;
                                updateUpdateCardUI();
                                Toast.makeText(RemoteMainActivity.this, L10n.t("Turn on Allow from this source, then come back and tap Update", "Bật Cho phép từ nguồn này rồi quay lại bấm Cập nhật"), Toast.LENGTH_LONG).show();
                            }
                        });
                    }

                    @Override
                    public void onInstallStarted() {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(RemoteMainActivity.this, L10n.t("Preparing to install...", "Đang chuẩn bị cài đặt..."), Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                    @Override
                    public void onError(final String message) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                updateErrorMessage = message;
                                updateState = UpdateState.ERROR;
                                updateUpdateCardUI();
                            }
                        });
                    }
                });
            }
        });
    }

    private void onDownloadProgress(long bytesRead, long totalBytes) {
        if (isStopped || isDestroyed || updateState != UpdateState.DOWNLOADING) return;
        currentDownloadBytes = bytesRead;
        if (totalBytes > 0) {
            currentDownloadProgress = (int) ((bytesRead * 100) / totalBytes);
        }
        updateUpdateCardUI();
    }

    private void onDownloadFinished(File apkFile, Exception error) {
        if (isStopped || isDestroyed) return;
        if (error != null) {
            if (error instanceof RemoteApiClient.DeviceRevokedException) {
                handleDeviceRevoked();
                return;
            }
            String msg = L10n.t("Network error while downloading the update", "Lỗi mạng khi tải cập nhật");
            String m = error.getMessage();
            if (m != null) {
                if (m.contains("Download cancelled")) {
                    updateState = UpdateState.AVAILABLE;
                    updateUpdateCardUI();
                    return;
                } else if (m.contains("SHA-256 mismatch")) {
                    msg = L10n.t("File check failed (SHA-256 mismatch)", "Lỗi kiểm tra tệp (SHA-256 không khớp)");
                } else if (m.contains("Content-Length mismatch")) {
                    msg = L10n.t("The file size from the server does not match", "Kích thước tệp từ máy chủ không khớp");
                } else if (m.contains("ENOSPC") || m.contains("No space")) {
                    msg = L10n.t("Not enough storage", "Không đủ dung lượng bộ nhớ");
                } else {
                    msg = L10n.t("Could not download the update: ", "Lỗi tải cập nhật: ") + m;
                }
            }
            updateErrorMessage = msg;
            updateState = UpdateState.ERROR;
            updateUpdateCardUI();
            return;
        }

        proceedInstall(apkFile);
    }

    private void updateUpdateCardUI() {
        if (controlTabView != null) {
            controlTabView.updateUpdateCardUI(updateState, availableUpdate, currentDownloadProgress, currentDownloadBytes, updateErrorMessage);
        }
    }

    private int getInstalledVersionCode() {
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) {
                return (int) pInfo.getLongVersionCode();
            } else {
                return pInfo.versionCode;
            }
        } catch (Exception e) {
            return 1;
        }
    }

    private String getInstalledVersionName() {
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0";
        }
    }

    public void setApkInstaller(ApkInstaller installer) {
        this.apkInstaller = installer;
    }

    private ApkInstaller getApkInstaller() {
        if (apkInstaller != null) return apkInstaller;
        return new UpdateInstaller(this);
    }

    private static class UpdateCheckWorker implements Runnable {
        private final WeakReference<RemoteMainActivity> activityRef;
        private UpdateInfo updateInfo;
        private Exception error;
        private boolean isUiPass = false;

        UpdateCheckWorker(RemoteMainActivity activity) {
            this.activityRef = new WeakReference<RemoteMainActivity>(activity);
        }

        @Override
        public void run() {
            RemoteMainActivity act = activityRef.get();
            if (act == null || act.isStopped || act.isDestroyed) return;

            if (!isUiPass) {
                if (act.controller == null || !act.controller.isPaired()) return;
                try {
                    updateInfo = act.controller.getUpdateInfo();
                } catch (Exception e) {
                    error = e;
                }
                isUiPass = true;
                act.ui.post(this);
            } else {
                act.onUpdateInfoDeliver(updateInfo, error);
            }
        }
    }

    private static class DownloadWorker implements Runnable {
        private final WeakReference<RemoteMainActivity> activityRef;
        private final UpdateInfo updateInfo;
        private final File destFile;
        private final AtomicCancelFlag cancelFlag;
        private Exception error;

        DownloadWorker(RemoteMainActivity activity, UpdateInfo updateInfo, File destFile, AtomicCancelFlag cancelFlag) {
            this.activityRef = new WeakReference<RemoteMainActivity>(activity);
            this.updateInfo = updateInfo;
            this.destFile = destFile;
            this.cancelFlag = cancelFlag;
        }

        @Override
        public void run() {
            RemoteMainActivity act = activityRef.get();
            if (act == null || act.controller == null) return;

            try {
                act.controller.downloadUpdate(updateInfo, destFile, new ProgressListener() {
                    private long lastPost = 0;
                    @Override
                    public void onProgress(final long bytesRead, final long totalBytes) {
                        long now = System.currentTimeMillis();
                        if (now - lastPost > 100 || bytesRead == totalBytes) {
                            lastPost = now;
                            final RemoteMainActivity a = activityRef.get();
                            if (a != null) {
                                a.ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        a.onDownloadProgress(bytesRead, totalBytes);
                                    }
                                });
                            }
                        }
                    }
                }, cancelFlag);
            } catch (Exception e) {
                error = e;
            }

            final Exception finalError = error;
            final RemoteMainActivity a = activityRef.get();
            if (a != null) {
                a.ui.post(new Runnable() {
                    @Override
                    public void run() {
                        a.onDownloadFinished(destFile, finalError);
                    }
                });
            }
        }
    }

    private static void addSpacing(LinearLayout layout, int heightPx) {
        View spacer = new View(layout.getContext());
        spacer.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx));
        layout.addView(spacer);
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
