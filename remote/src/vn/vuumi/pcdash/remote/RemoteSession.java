package vn.vuumi.pcdash.remote;

import android.content.Context;
import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import vn.vuumi.pcdash.net.PcnetBridge;

/**
 * The one RemoteController of the process, shared by the activity and the host-alert job, since there
 * is one embedded tsnet node. Each user acquires it; the tailnet stops only when the last one releases,
 * so a background check never cuts off the open app and the other way round.
 *
 * acquire() is a plain counter so the UI thread can call it. release() runs off the UI thread and holds
 * the controller's lock while it decides to close; open() takes the same lock, so a user that acquired
 * after the count reached zero always opens after that close.
 *
 * The app keeps the tailnet for {@link #APP_KEEP_ALIVE_MS} after it leaves the screen, so coming back
 * soon (another app, the screen off and on) finds it up instead of starting it again.
 */
final class RemoteSession {
    private static RemoteController controller;
    private static RemoteConfigStore store;
    private static final AtomicInteger users = new AtomicInteger();
    private static volatile boolean activityVisible;

    static final long APP_KEEP_ALIVE_MS = 3 * 60_000L;
    private static final ScheduledExecutorService closer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "pcdash-tailnet-closer");
            t.setDaemon(true);
            return t;
        }
    });
    private static ScheduledFuture<?> pendingClose;

    private RemoteSession() {}

    static synchronized RemoteController controller(Context context) {
        if (controller == null) {
            File filesDir = context.getApplicationContext().getFilesDir();
            store = new RemoteConfigStore(new File(filesDir, "remote_config.json"));
            controller = new RemoteController(new PairingClient(), new PcnetBridge(), store,
                    new RemoteApiClient(), new File(filesDir, "tsnet"));
        }
        return controller;
    }

    static synchronized RemoteConfigStore store(Context context) {
        controller(context);
        return store;
    }

    static void acquire() {
        users.incrementAndGet();
        cancelPendingClose();
    }

    /**
     * Drops one user; closes the tailnet when none is left and closing is allowed (the activity keeps
     * it while a Tailscale sign-in is in progress), after {@code keepAliveMs} if no one comes back.
     * Never on the UI thread: close() can block.
     */
    static void release(final RemoteController c, boolean mayClose, long keepAliveMs) {
        synchronized (c) {
            if (users.decrementAndGet() > 0) return;
            users.set(0);
            if (!mayClose) return;
            if (keepAliveMs <= 0) {
                cancelPendingClose();
                c.close();
                return;
            }
        }
        synchronized (RemoteSession.class) {
            cancelPendingClose();
            pendingClose = closer.schedule(new Runnable() {
                @Override
                public void run() {
                    synchronized (c) {
                        // Someone acquired in the meantime: their open() keeps using the tailnet.
                        if (users.get() == 0) {
                            c.close();
                        }
                    }
                }
            }, keepAliveMs, TimeUnit.MILLISECONDS);
        }
    }

    private static synchronized void cancelPendingClose() {
        if (pendingClose != null) {
            pendingClose.cancel(false);
            pendingClose = null;
        }
    }

    static void setActivityVisible(boolean visible) {
        activityVisible = visible;
    }

    static boolean isActivityVisible() {
        return activityVisible;
    }
}
