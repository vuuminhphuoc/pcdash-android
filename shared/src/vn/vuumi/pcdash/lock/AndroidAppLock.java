package vn.vuumi.pcdash.lock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * The process-wide {@link AppLock}: settings in SharedPreferences, the HMAC key in Android Keystore.
 * Also tells the lock when the screen turns off (for the "when the screen turns off" choice); a
 * screen-off broadcast can only be received by a receiver registered at run time, so this lasts as
 * long as the process. A new process starts locked anyway.
 */
public final class AndroidAppLock {
    private static final String PREFS = "pcdash_app_lock";
    private static final String KEY_ALIAS = "pcdash-app-lock";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private static AppLock instance;
    // Main thread only: gates of started activities, told when the screen-off closed the lock.
    private static final List<Runnable> lockListeners = new ArrayList<Runnable>();

    private AndroidAppLock() {}

    public static synchronized AppLock get(Context context) {
        if (instance == null) {
            Context app = context.getApplicationContext();
            SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            instance = new AppLock(new PrefsStore(prefs), new KeystoreHasher(), new SecureRandom());
            watchScreenOff(app);
        }
        return instance;
    }

    /** Runs on the main thread when the screen turning off closes the lock. */
    static void addLockListener(Runnable listener) {
        if (!lockListeners.contains(listener)) {
            lockListeners.add(listener);
        }
    }

    static void removeLockListener(Runnable listener) {
        lockListeners.remove(listener);
    }

    private static void watchScreenOff(Context app) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                AppLock lock = instance;
                if (lock != null && lock.onScreenOff()) {
                    for (Runnable l : new ArrayList<Runnable>(lockListeners)) {
                        l.run();
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        // SCREEN_OFF comes from the system, which reaches a not-exported receiver too.
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
    }

    private static final class PrefsStore implements AppLock.Store {
        private final SharedPreferences prefs;

        PrefsStore(SharedPreferences prefs) {
            this.prefs = prefs;
        }

        @Override
        public String get(String key) {
            return prefs.getString(key, null);
        }

        // commit(), not apply(): wrong-code counters must be on disk before the process can be killed.
        @Override
        public void put(String key, String value) {
            prefs.edit().putString(key, value).commit();
        }

        @Override
        public void remove(String key) {
            prefs.edit().remove(key).commit();
        }
    }

    /** HMAC-SHA256 with a key that never leaves Android Keystore. */
    private static final class KeystoreHasher implements AppLock.CodeHasher {
        @Override
        public byte[] hash(byte[] salt, String code) throws GeneralSecurityException {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key());
            mac.update(salt);
            mac.update(code.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        }

        private static SecretKey key() throws GeneralSecurityException {
            try {
                KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
                ks.load(null);
                if (!ks.containsAlias(KEY_ALIAS)) {
                    KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE);
                    generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN).build());
                    return generator.generateKey();
                }
                return (SecretKey) ks.getKey(KEY_ALIAS, null);
            } catch (java.io.IOException e) {
                throw new GeneralSecurityException("Android Keystore unavailable", e);
            }
        }
    }
}
