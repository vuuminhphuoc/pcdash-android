package vn.vuumi.pcdash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Intent svc = new Intent(c, DashService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(svc);
        } else {
            c.startService(svc);
        }
        WatchdogReceiver.schedule(c);
    }
}
