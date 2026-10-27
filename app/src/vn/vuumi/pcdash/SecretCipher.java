package vn.vuumi.pcdash;

public interface SecretCipher {
    String encrypt(String plaintext) throws Exception;
    String decrypt(String ciphertext) throws Exception;
}
