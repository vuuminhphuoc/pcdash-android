package vn.vuumi.pcdash;

import android.app.Application;

import vn.vuumi.pcdash.i18n.AppLanguage;

public class PcDashApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppLanguage.apply(this);
    }
}
