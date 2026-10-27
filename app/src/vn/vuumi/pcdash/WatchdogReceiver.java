package vn.vuumi.pcdash;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class WatchdogReceiver extends BroadcastReceiver {
    private static final String TAG = "WatchdogReceiver";
    public static final long INTERVAL_MS = 15 * 60 * 1000L; // 15 phút

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent svc = new Intent(context, DashService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting DashService from watchdog: " + e.getMessage());
        }
        schedule(context);
    }

    public static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, WatchdogReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(
                context, 102, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long triggerAt = System.currentTimeMillis() + INTERVAL_MS;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (SecurityException e) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } catch (Exception ignored) {}
        }
    }

    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, WatchdogReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(
                context, 102, i,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) {
            am.cancel(pi);
            pi.cancel();
        }
    }
}
