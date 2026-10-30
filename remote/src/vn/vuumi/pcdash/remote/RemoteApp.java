package vn.vuumi.pcdash.remote;

import android.app.Application;

import vn.vuumi.pcdash.i18n.AppLanguage;

public class RemoteApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppLanguage.apply(this);
    }
}
