package vn.vuumi.pcdash;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class AndroidSecretCipher implements SecretCipher {

    public static final String KEY_ALIAS = "pcdash-oauth";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String AES_GCM_CIPHER = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;

    private final KeyStore keyStore;
    private final SecureRandom random;

    public AndroidSecretCipher() throws Exception {
        this(initKeyStore(), new SecureRandom());
    }

    public AndroidSecretCipher(KeyStore keyStore, SecureRandom random) throws Exception {
        this.keyStore = keyStore;
        this.random = random != null ? random : new SecureRandom();
        ensureKey();
    }

    private static KeyStore initKeyStore() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        return ks;
    }

    private void ensureKey() throws Exception {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            Class<?> specBuilderClass = Class.forName("android.security.keystore.KeyGenParameterSpec$Builder");
            Class<?> keyPropertiesClass = Class.forName("android.security.keystore.KeyProperties");

            int purposeEncrypt = keyPropertiesClass.getField("PURPOSE_ENCRYPT").getInt(null);
            int purposeDecrypt = keyPropertiesClass.getField("PURPOSE_DECRYPT").getInt(null);
            int purposes = purposeEncrypt | purposeDecrypt;

            Object builder = specBuilderClass.getConstructor(String.class, int.class).newInstance(KEY_ALIAS, purposes);

            String blockModeGcm = (String) keyPropertiesClass.getField("BLOCK_MODE_GCM").get(null);
            String encPaddingNone = (String) keyPropertiesClass.getField("ENCRYPTION_PADDING_NONE").get(null);

            specBuilderClass.getMethod("setBlockModes", String[].class).invoke(builder, (Object) new String[]{blockModeGcm});
            specBuilderClass.getMethod("setEncryptionPaddings", String[].class).invoke(builder, (Object) new String[]{encPaddingNone});
            specBuilderClass.getMethod("setKeySize", int.class).invoke(builder, 256);

            java.security.spec.AlgorithmParameterSpec spec = (java.security.spec.AlgorithmParameterSpec) specBuilderClass.getMethod("build").invoke(builder);

            KeyGenerator kg = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE);
            kg.init(spec);
            kg.generateKey();
        }
    }

    @Override
    public String encrypt(String plaintext) throws Exception {
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }
        SecretKey key = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (key == null) {
            throw new IllegalStateException("AndroidKeyStore key not found: " + KEY_ALIAS);
        }
        // AndroidKeyStore requires randomized encryption: the keystore generates the IV itself.
        Cipher cipher = Cipher.getInstance(AES_GCM_CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_LENGTH_BYTES) {
            throw new IllegalStateException("Unexpected GCM IV length from AndroidKeyStore");
        }
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    @Override
    public String decrypt(String ciphertext) throws Exception {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return "";
        }
        byte[] combined = Base64.getDecoder().decode(ciphertext);
        if (combined.length < IV_LENGTH_BYTES) {
            throw new IllegalArgumentException("Encrypted secret payload too short");
        }
        byte[] iv = new byte[IV_LENGTH_BYTES];
        System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);

        int ctLen = combined.length - IV_LENGTH_BYTES;
        byte[] encryptedBytes = new byte[ctLen];
        System.arraycopy(combined, IV_LENGTH_BYTES, encryptedBytes, 0, ctLen);

        SecretKey key = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (key == null) {
            throw new IllegalStateException("AndroidKeyStore key not found: " + KEY_ALIAS);
        }

        Cipher cipher = Cipher.getInstance(AES_GCM_CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] decrypted = cipher.doFinal(encryptedBytes);
        return new String(decrypted, StandardCharsets.UTF_8);
    }
}
