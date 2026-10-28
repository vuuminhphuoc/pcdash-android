package vn.vuumi.pcdash;

import android.app.Application;

import vn.vuumi.pcdash.i18n.AppLanguage;

public class PcDashApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppLanguage.apply(this);
        // The APK of an update that has been installed (or never was) is no longer needed.
        new Thread(new Runnable() {
            @Override
            public void run() {
                HostUpdater.clearCache(PcDashApp.this);
            }
        }).start();
    }
}
