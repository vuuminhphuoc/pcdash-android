package vn.vuumi.pcdash.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException
import java.util.Locale
import vn.vuumi.pcdash.i18n.L10n

/**
 * Optional notification when the home phone has a newer PCDash Remote than this phone runs (the host
 * carries the remote's APK for self-update). Off by default, like the alerts: the background job of
 * [HostWatch] asks the host now and then. Each version is announced once, and not at all if the app
 * already showed it on its update card; tapping the notification opens that card.
 */
object UpdateNotice {
    const val NOTIFY_ID = 4
    const val CHANNEL_ID = "updates"

    /** How often the job runs when only this is on (the alerts need it every 15 minutes). */
    const val INTERVAL_MS = 6 * 60 * 60 * 1000L

    /** Intent extra: opened from the notification, show the update card. */
    const val EXTRA_SHOW_UPDATE = "vn.vuumi.pcdash.remote.SHOW_UPDATE"

    private const val PREFS = "pcdash_update_notice"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SEEN = "seenVersionCode"

    @JvmStatic
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /** Turns the notice on or off. Not on the UI thread (JobScheduler and prefs I/O). */
    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit()
        HostWatch.reschedule(context)
        if (!enabled) cancel(context)
    }

    /** Whether to announce [info]: newer than this app and not announced or shown before. */
    @JvmStatic
    fun shouldNotify(info: UpdateInfo?, installedVersionCode: Int, seenVersionCode: Int): Boolean =
        info != null && UpdateChecker.isUpdateAvailable(info, installedVersionCode) && info.versionCode > seenVersionCode

    /**
     * The app checked for itself: a new version is now on its update card ([newVersionCode]), or there
     * is none (0). Either way a notification about it has nothing left to say. Not on the UI thread.
     */
    @JvmStatic
    fun checkedInApp(context: Context, newVersionCode: Int) {
        val p = prefs(context)
        if (newVersionCode > p.getInt(KEY_SEEN, 0)) {
            p.edit().putInt(KEY_SEEN, newVersionCode).commit()
        }
        cancel(context)
    }

    /** One check from the background job, with the tailnet up. */
    @JvmStatic
    fun check(context: Context, controller: RemoteController) {
        val info = try {
            controller.updateInfo
        } catch (e: IOException) {
            return
        }
        val installed = installedVersionCode(context)
        val p = prefs(context)
        if (!shouldNotify(info, installed, p.getInt(KEY_SEEN, 0))) {
            // Updated meanwhile, or the host no longer offers it.
            if (!UpdateChecker.isUpdateAvailable(info, installed)) cancel(context)
            return
        }
        post(context, info)
        p.edit().putInt(KEY_SEEN, info.versionCode).commit()
    }

    private fun post(context: Context, info: UpdateInfo) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, L10n.t("App updates", "Bản cập nhật app"), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val version = "v" + info.versionName
        val sizeMb = String.format(Locale.US, "%.1f", info.size / (1024.0 * 1024.0))
        val title = L10n.t("PCDash Remote $version is ready", "Đã có PCDash Remote $version")
        val text = L10n.t("The home phone has a new version ($sizeMb MB). Tap to open the app and update.",
            "Máy ở nhà có bản mới ($sizeMb MB). Chạm để mở app và cập nhật.")
        val open = Intent(context, RemoteMainActivity::class.java)
            .putExtra(EXTRA_SHOW_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(
            context, NOTIFY_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID) else Notification.Builder(context)
        builder.setSmallIcon(HostWatch.smallIcon(context))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
        // Missing POST_NOTIFICATIONS permission (Android 13+) only makes this silent.
        nm.notify(NOTIFY_ID, builder.build())
    }

    private fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFY_ID)
    }

    private fun installedVersionCode(context: Context): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    } catch (e: PackageManager.NameNotFoundException) {
        Int.MAX_VALUE // unknown: announce nothing
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
