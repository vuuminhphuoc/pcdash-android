package vn.vuumi.pcdash.remote;

import android.content.Context;
import java.io.File;
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
 */
final class RemoteSession {
    private static RemoteController controller;
    private static RemoteConfigStore store;
    private static final AtomicInteger users = new AtomicInteger();
    private static volatile boolean activityVisible;

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
    }

    /**
     * Drops one user; closes the tailnet when none is left and closing is allowed (the activity keeps
     * it while a Tailscale sign-in is in progress). Never on the UI thread: close() can block.
     */
    static void release(RemoteController c, boolean mayClose) {
        synchronized (c) {
            if (users.decrementAndGet() <= 0) {
                users.set(0);
                if (mayClose) {
                    c.close();
                }
            }
        }
    }

    static void setActivityVisible(boolean visible) {
        activityVisible = visible;
    }

    static boolean isActivityVisible() {
        return activityVisible;
    }
}
