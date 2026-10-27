package vn.vuumi.pcdash;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class DashService extends Service implements RemoteActionExecutor {
    private static final String TAG = "DashService";
    private static volatile boolean serviceRunning = false;
    private static volatile DashService instance = null;

    private WebDashboard web;
    private RemoteHostController remoteController;
    private String remoteStartError = null;
    private PhoneMetricsCollector phoneCollector;
    private PcMetricsClient pcClient;
    private PcQuotaClient pcQuotaClient;
    private final QuotaStore quotaStore = new QuotaStore();
    private CommandLog commandLog;
    private MetricsHistory metricsHistory;
    private ScheduledExecutorService metricsScheduler;
    private int sampleCount = 0;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private final LocalBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public DashService getService() {
            return DashService.this;
        }

        public RemoteHostController getRemoteHostController() {
            return remoteController;
        }

        public CommandLog getCommandLog() {
            return commandLog;
        }

        public MetricsHistory getMetricsHistory() {
            return metricsHistory;
        }
    }

    public static boolean isServiceRunning() {
        return serviceRunning;
    }

    public static boolean isPort8091Listening() {
        DashService s = instance;
        return serviceRunning && s != null && s.remoteController != null
                && s.remoteController.apiServer() != null
                && s.remoteController.apiServer().isRunning();
    }

    public static HostTailnet getHostTailnet() {
        DashService s = instance;
        return (s != null && s.remoteController != null) ? s.remoteController.getHostTailnet() : null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        serviceRunning = true;

        String channel = "pcdash";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(channel, "PCDash", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Dashboard quản lý PC");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            n = new Notification.Builder(this, channel)
                    .setSmallIcon(android.R.drawable.ic_lock_power_off)
                    .setContentTitle("PCDash")
                    .setContentText("Dashboard đang chạy tại port " + WebDashboard.PORT)
                    .setOngoing(true)
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_lock_power_off)
                    .setContentTitle("PCDash")
                    .setContentText("Dashboard đang chạy tại port " + WebDashboard.PORT)
                    .setOngoing(true)
                    .build();
        }
        startForeground(1, n);

        // WakeLock + WifiLock
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pcdash:service");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not acquire wake lock: " + e.getMessage());
        }

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pcdash:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not acquire wifi lock: " + e.getMessage());
        }

        PcSettings pcSettings = new PcSettings(this);
        PcConn.configure(pcSettings);

        commandLog = new CommandLog(new File(getFilesDir(), "command_log.jsonl"));
        metricsHistory = new MetricsHistory(new File(getFilesDir(), "metrics_history.json"));

        web = new WebDashboard(this, commandLog, pcSettings);
        web.start();

        remoteStartError = null;
        RemoteHostController controller = null;
        try {
            phoneCollector = new PhoneMetricsCollector(this);
            pcClient = new PcMetricsClient(pcSettings);
            pcQuotaClient = new PcQuotaClient(pcSettings);
            controller = new RemoteHostController(getFilesDir(), this, new AndroidUpdateAssetSource(getAssets()), commandLog, metricsHistory);
            controller.start();
            remoteController = controller;
            final HostTailnet tailnet = controller.getHostTailnet();
            if (tailnet != null) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        File tsnetDir = new File(getFilesDir(), "tsnet");
                        tailnet.begin(tsnetDir, "pcdash-host", vn.vuumi.pcdash.net.PcnetBridge.MODE_HOST_LISTEN, "", 8091, 8091);
                    }
                }, "pcdash-host-tailnet").start();
            }
        } catch (IOException e) {
            if (controller != null) {
                controller.close();
            }
            remoteController = null;
            String msg = e.getMessage();
            if (msg != null && msg.length() > 200) {
                msg = msg.substring(0, 200);
            }
            remoteStartError = e.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
            Log.e(TAG, "Failed to start RemoteApiServer on port " + RemoteApiServer.PORT + ": " + remoteStartError);
        }

        startMetricsSampling();
        WatchdogReceiver.schedule(this);
    }

    private void startMetricsSampling() {
        metricsScheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "pcdash-metrics-sampler");
                t.setDaemon(true);
                return t;
            }
        });

        metricsScheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    String pcJson = pcClient != null ? pcClient.getMetricsJson() : null;
                    MetricsHistory.Sample sample = MetricsHistory.parseAgentMetrics(System.currentTimeMillis(), pcJson);
                    if (metricsHistory != null) {
                        metricsHistory.add(sample);
                        sampleCount++;
                        if (sampleCount % 10 == 0) {
                            metricsHistory.save();
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error sampling metrics: " + e.getMessage());
                }
            }
        }, 0, 30, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Intent restartIntent = new Intent(getApplicationContext(), DashService.class);
        PendingIntent pendingIntent;
        if (Build.VERSION.SDK_INT >= 26) {
            pendingIntent = PendingIntent.getForegroundService(
                    getApplicationContext(), 101, restartIntent,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            pendingIntent = PendingIntent.getService(
                    getApplicationContext(), 101, restartIntent,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        }
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null) {
            long triggerAt = System.currentTimeMillis() + 1000L;
            try {
                if (Build.VERSION.SDK_INT >= 23) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        serviceRunning = false;
        instance = null;

        if (metricsScheduler != null) {
            metricsScheduler.shutdown();
            metricsScheduler = null;
        }

        if (metricsHistory != null) {
            metricsHistory.save();
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
            wakeLock = null;
        }

        if (wifiLock != null && wifiLock.isHeld()) {
            try { wifiLock.release(); } catch (Exception ignored) {}
            wifiLock = null;
        }

        if (remoteController != null) {
            remoteController.close();
            remoteController = null;
        }
        if (web != null) web.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public RemoteHostController getRemoteHostController() {
        return remoteController;
    }

    public CommandLog getCommandLog() {
        return commandLog;
    }

    public MetricsHistory getMetricsHistory() {
        return metricsHistory;
    }

    public String getRemoteStartError() {
        return remoteStartError;
    }

    @Override
    public String statusJson() {
        return PcConn.statusJson();
    }

    @Override
    public String doAction(String action) {
        return PcConn.doAction(action);
    }

    @Override
    public String metricsJson() {
        return MetricsJson.build(pcClient, phoneCollector);
    }

    @Override
    public String quotaJson(boolean refresh) {
        String fresh = pcQuotaClient != null ? pcQuotaClient.getQuotaJson(refresh) : null;
        return quotaStore.resolve(fresh);
    }
}
