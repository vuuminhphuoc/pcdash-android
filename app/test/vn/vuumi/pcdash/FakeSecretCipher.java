package vn.vuumi.pcdash;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class FakeSecretCipher implements SecretCipher {
    public static final String PREFIX = "enc-fake:";

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return "";
        return PREFIX + Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) return "";
        if (ciphertext.startsWith(PREFIX)) {
            byte[] bytes = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return ciphertext;
    }
}
