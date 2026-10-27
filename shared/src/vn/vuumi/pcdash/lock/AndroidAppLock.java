package vn.vuumi.pcdash.lock;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/** The process-wide {@link AppLock}: settings in SharedPreferences, the HMAC key in Android Keystore. */
public final class AndroidAppLock {
    private static final String PREFS = "pcdash_app_lock";
    private static final String KEY_ALIAS = "pcdash-app-lock";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private static AppLock instance;

    private AndroidAppLock() {}

    public static synchronized AppLock get(Context context) {
        if (instance == null) {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            instance = new AppLock(new PrefsStore(prefs), new KeystoreHasher(), new SecureRandom());
        }
        return instance;
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
