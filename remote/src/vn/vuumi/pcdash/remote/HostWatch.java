package vn.vuumi.pcdash.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.io.IOException;
import java.util.List;
import vn.vuumi.pcdash.i18n.L10n;
import vn.vuumi.pcdash.net.PcnetState;

/**
 * Optional alerts when the home phone goes offline or its battery runs low, and (a second choice) when
 * the PC turns off. Off by default: it is the one thing that makes the remote work in the background.
 * When on, a JobScheduler job checks about every 15 minutes while this phone has a network: it opens
 * the tailnet for a few seconds and reads the Tailscale device list, which tells at once whether the
 * home phone is online. Only when it is does the check ask the PCDash host app for the PC's state and
 * the battery. {@link HostAlertRules} decides what to notify.
 *
 * The job is not kept across a reboot of this phone (that would need a boot receiver); opening the
 * app schedules it again. Android may also delay it while the phone sits idle (Doze).
 */
final class HostWatch {
    static final int JOB_ID = 4201;
    static final long INTERVAL_MS = 15 * 60 * 1000L;
    static final String CHANNEL_ID = "host_alerts";
    static final int NOTIFY_CONNECTION = 1;
    static final int NOTIFY_BATTERY = 2;
    static final int NOTIFY_PC = 3;

    private static final String PREFS = "pcdash_host_watch";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_STATE = "state";
    private static final String KEY_PC_ALERTS = "pcAlerts";
    private static final long TAILNET_WAIT_MS = 30_000L;

    private HostWatch() {}

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    /** Turns the checks on or off. Not on the UI thread (JobScheduler and prefs I/O). */
    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).remove(KEY_STATE).commit();
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        if (enabled) {
            schedule(context, scheduler);
        } else {
            scheduler.cancel(JOB_ID);
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.cancel(NOTIFY_CONNECTION);
                nm.cancel(NOTIFY_BATTERY);
                nm.cancel(NOTIFY_PC);
            }
        }
    }

    /** Also alert when the PC turns off or comes back (off by default: a PC is often turned off on purpose). */
    static boolean isPcAlertsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PC_ALERTS, false);
    }

    static void setPcAlertsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PC_ALERTS, enabled).commit();
        if (!enabled) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(NOTIFY_PC);
        }
    }

    /** This app just sent Sleep, Hibernate or Shut down: the PC going off is no surprise. Not on the UI thread. */
    static synchronized void notePcOffExpected(Context context) {
        if (!isEnabled(context)) return;
        SharedPreferences p = prefs(context);
        HostAlertRules.State state = HostAlertRules.State.decode(p.getString(KEY_STATE, null));
        state.pcOffExpected = true;
        p.edit().putString(KEY_STATE, state.encode()).commit();
    }

    /** Schedules the job again if it is on but missing (after a reboot of this phone). */
    static void syncSchedule(Context context) {
        if (!isEnabled(context)) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null && scheduler.getPendingJob(JOB_ID) == null) {
            schedule(context, scheduler);
        }
    }

    private static void schedule(Context context, JobScheduler scheduler) {
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, HostWatchJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(INTERVAL_MS)
                .build();
        scheduler.schedule(job);
    }

    /** One check, on the job's worker thread. */
    static void runCheck(Context context) {
        RemoteController c = RemoteSession.controller(context);
        if (!c.isPaired()) return;
        RemoteSession.acquire();
        try {
            c.open();
            if (!waitForTailnet(c)) {
                // This phone's own tailnet is not up (no sign-in, no route): says nothing about the host.
                return;
            }
            TailnetPeers peers = TailnetPeers.parse(c.getBridge().peersJson());
            TailnetPeers.Peer host = peers != null ? peers.host() : null;
            Boolean listedOnline = host != null ? Boolean.valueOf(host.online) : null;
            // Offline in the device list: one request in case the list lags; otherwise up to three
            // (a single request can land on a blip).
            int tries = Boolean.FALSE.equals(listedOnline) ? 1 : 3;
            RemoteApiClient.RemoteStatus status = null;
            for (int attempt = 0; attempt < tries && status == null; attempt++) {
                try {
                    status = c.getStatus();
                } catch (RemoteApiClient.DeviceRevokedException e) {
                    return;
                } catch (IOException e) {
                    if (attempt < tries - 1) Thread.sleep(5000);
                }
            }
            Double battery = null;
            Boolean charging = null;
            if (status != null) {
                try {
                    MetricsSnapshot m = MetricsParser.parse(c.getMetrics());
                    if (m.phone != null && m.phone.battery != null) {
                        battery = m.phone.battery.percent;
                        charging = m.phone.battery.charging;
                    }
                } catch (IOException ignored) {}
            }
            record(context, new HostAlertRules.Check(listedOnline, status != null,
                    status != null ? Boolean.valueOf(status.online) : null, battery, charging),
                    host != null ? host.lastSeenMs : -1L);
        } catch (IOException | InterruptedException ignored) {
            // Stopped by the system or the tailnet would not start; the next run tries again.
        } finally {
            // A background check closes at once (unless the app came back meanwhile).
            RemoteSession.release(c, true, 0L);
        }
    }

    private static synchronized void record(Context context, HostAlertRules.Check check, long hostLastSeenMs) {
        SharedPreferences p = prefs(context);
        HostAlertRules.State state = HostAlertRules.State.decode(p.getString(KEY_STATE, null));
        List<HostAlertRules.Alert> alerts = HostAlertRules.apply(state, check, isPcAlertsEnabled(context));
        p.edit().putString(KEY_STATE, state.encode()).commit();
        for (HostAlertRules.Alert alert : alerts) {
            notify(context, alert, check.batteryPercent, hostLastSeenMs);
        }
    }

    private static boolean waitForTailnet(RemoteController c) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TAILNET_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            PcnetState st = c.state();
            if (st != null && st.isRunning()) return true;
            if (st != null && (st.isNeedsLogin() || st.isError())) return false;
            Thread.sleep(500);
        }
        return false;
    }

    private static void notify(Context context, HostAlertRules.Alert alert, Double battery, long hostLastSeenMs) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    L10n.t("Home phone alerts", "Cảnh báo máy ở nhà"), NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(channel);
        }
        String title;
        String text;
        int id;
        switch (alert) {
            case HOST_OFFLINE: {
                id = NOTIFY_CONNECTION;
                String ago = TailnetPeers.ago(hostLastSeenMs, System.currentTimeMillis());
                String seen = ago.isEmpty() ? "" : L10n.t(" (last seen " + ago + ")", " (lần cuối online " + ago + ")");
                title = L10n.t("Home phone is offline", "Máy ở nhà mất kết nối");
                text = L10n.t("Tailscale shows the home phone offline" + seen + ". Check its Wi-Fi and power.",
                        "Tailscale báo điện thoại ở nhà đang offline" + seen + ". Kiểm tra Wi-Fi và nguồn điện của máy.");
                break;
            }
            case HOST_NOT_ANSWERING:
                id = NOTIFY_CONNECTION;
                title = L10n.t("PCDash host is not answering", "App PCDash ở nhà không phản hồi");
                text = L10n.t("The home phone is online in Tailscale, but the PCDash host app does not answer. Open it on that phone and check its battery optimization.",
                        "Điện thoại ở nhà vẫn online trên Tailscale nhưng app PCDash host không trả lời. Mở app trên máy đó và kiểm tra tối ưu pin.");
                break;
            case PC_OFFLINE:
                id = NOTIFY_PC;
                title = L10n.t("PC is off or unreachable", "PC đã tắt hoặc mất kết nối");
                text = L10n.t("The home phone can no longer reach the PC. You can turn it on again with Wake.",
                        "Điện thoại ở nhà không còn thấy PC. Có thể bật lại bằng nút Bật / Đánh thức PC.");
                break;
            case PC_BACK_ONLINE:
                id = NOTIFY_PC;
                title = L10n.t("PC is back on", "PC đã bật lại");
                text = L10n.t("The home phone can reach the PC again.", "Điện thoại ở nhà đã thấy PC trở lại.");
                break;
            case HOST_BACK_ONLINE:
                id = NOTIFY_CONNECTION;
                title = L10n.t("Home phone is back online", "Máy ở nhà đã kết nối lại");
                text = L10n.t("PCDash host is answering again.", "PCDash host đã trả lời bình thường.");
                break;
            default:
                id = NOTIFY_BATTERY;
                String pct = battery != null ? Math.round(battery) + "%" : "?";
                title = L10n.t("Home phone battery low", "Pin máy ở nhà yếu");
                text = L10n.t(pct + " left and not charging. Plug it in so PCDash host keeps running.",
                        "Còn " + pct + ", không sạc. Cắm sạc để PCDash host tiếp tục chạy.");
                break;
        }
        Intent open = new Intent(context, RemoteMainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(context, id, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        b.setSmallIcon(smallIcon(context))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true);
        // Missing POST_NOTIFICATIONS permission (Android 13+) only makes this silent.
        nm.notify(id, b.build());
    }

    private static int smallIcon(Context context) {
        // No R class in this build; the tile's power glyph is a white icon that fits the status bar.
        int id = context.getResources().getIdentifier("ic_qs_wake", "drawable", context.getPackageName());
        return id != 0 ? id : android.R.drawable.stat_sys_warning;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
