package vn.vuumi.pcdash.pairing;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class PairingProtocol {

    public static final BigInteger N_2048 = new BigInteger(
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC319294"
            + "3DB56050A37329CBB4A099ED8193E0757767A13DD52312AB4B03310D"
            + "CD7F48A9DA04FD50E8083969EDB767B0CF6095179A163AB3661A05FB"
            + "D5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF74"
            + "7359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A"
            + "436C6481F1D2B9078717461A5B9D32E688F87748544523B524B0D57D"
            + "5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6AF874E73"
            + "03CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB6"
            + "94B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F"
            + "9E4AFF73", 16);

    public static final BigInteger N_1024 = new BigInteger(
            "EEAF0AB9ADB38DD69C33F80AFA8FC5E86072618775FF3C0B9EA2314C"
            + "9C256576D674DF7496EA81D3383B4813D692C6E0E0D5D8E250B98BE4"
            + "8E495C1D6089DAD15DC7D7B46154D6B6CE8EF4AD69B15D4982559B29"
            + "7BCF1885C529F566660E57EC68EDBC3C05726CC02FD4CBF4976EAA9A"
            + "FD5138FE8376435B9FC61D2FC0EB06E3", 16);

    public static final BigInteger G = BigInteger.valueOf(2);

    public static final String HASH_SHA256 = "SHA-256";
    public static final String HASH_SHA1 = "SHA-1";
    public static final String PAIRING_IDENTITY = "pcdash-pair";
    public static final byte[] AES_KEY_INFO = "pcdash-pair-aes".getBytes(StandardCharsets.UTF_8);
    public static final int AES_KEY_LENGTH = 32;

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String AES_GCM_CIPHER = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_NONCE_LENGTH_BYTES = 12;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static int getByteLength(BigInteger n) {
        return (n.bitLength() + 7) / 8;
    }

    public static byte[] toBytes(BigInteger val, int length) {
        if (val == null) {
            return new byte[length];
        }
        byte[] raw = val.toByteArray();
        if (raw.length == length) {
            return raw;
        }
        byte[] result = new byte[length];
        if (raw.length > length) {
            // Strip leading zero sign bytes
            int offset = raw.length - length;
            System.arraycopy(raw, offset, result, 0, length);
        } else {
            // Right-align and left-pad with zeros
            int offset = length - raw.length;
            System.arraycopy(raw, 0, result, offset, raw.length);
        }
        return result;
    }

    public static byte[] pad(BigInteger val, BigInteger N) {
        return toBytes(val, getByteLength(N));
    }

    public static byte[] padBytes(byte[] val, int length) {
        if (val == null) return new byte[length];
        if (val.length == length) return val;
        byte[] result = new byte[length];
        if (val.length > length) {
            System.arraycopy(val, val.length - length, result, 0, length);
        } else {
            System.arraycopy(val, 0, result, length - val.length, val.length);
        }
        return result;
    }

    public static BigInteger fromBytes(byte[] bytes) {
        return new BigInteger(1, bytes);
    }

    public static MessageDigest getDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Hash algorithm not available: " + algorithm, e);
        }
    }

    public static byte[] hash(String algorithm, byte[]... parts) {
        MessageDigest md = getDigest(algorithm);
        for (byte[] p : parts) {
            if (p != null) {
                md.update(p);
            }
        }
        return md.digest();
    }

    public static String normalizeCode(String code) {
        if (code == null) return "";
        return code.trim().toUpperCase(java.util.Locale.US);
    }

    public static BigInteger computeX(String hashAlgorithm, String identity, String password, byte[] salt) {
        String idColonP = (identity != null ? identity : "") + ":" + (password != null ? password : "");
        byte[] innerHash = hash(hashAlgorithm, idColonP.getBytes(StandardCharsets.UTF_8));
        byte[] xBytes = hash(hashAlgorithm, salt, innerHash);
        return fromBytes(xBytes);
    }

    public static BigInteger computeV(BigInteger g, BigInteger x, BigInteger N) {
        return g.modPow(x, N);
    }

    public static BigInteger computeK(String hashAlgorithm, BigInteger N, BigInteger g) {
        int nLen = getByteLength(N);
        byte[] nBytes = toBytes(N, nLen);
        byte[] padG = toBytes(g, nLen);
        byte[] kBytes = hash(hashAlgorithm, nBytes, padG);
        return fromBytes(kBytes);
    }

    public static BigInteger computeU(String hashAlgorithm, BigInteger N, BigInteger A, BigInteger B) {
        int nLen = getByteLength(N);
        byte[] padA = toBytes(A, nLen);
        byte[] padB = toBytes(B, nLen);
        byte[] uBytes = hash(hashAlgorithm, padA, padB);
        BigInteger u = fromBytes(uBytes);
        validateU(u);
        return u;
    }

    public static BigInteger computeClientA(BigInteger g, BigInteger a, BigInteger N) {
        BigInteger A = g.modPow(a, N);
        validateA(A, N);
        return A;
    }

    public static BigInteger computeServerB(BigInteger k, BigInteger v, BigInteger g, BigInteger b, BigInteger N) {
        BigInteger gb = g.modPow(b, N);
        BigInteger B = k.multiply(v).add(gb).mod(N);
        validateB(B, N);
        return B;
    }

    public static BigInteger computeClientS(BigInteger B, BigInteger k, BigInteger g, BigInteger x, BigInteger a, BigInteger u, BigInteger N) {
        validateB(B, N);
        validateU(u);
        BigInteger gx = g.modPow(x, N);
        BigInteger kgx = k.multiply(gx).mod(N);
        BigInteger base = B.subtract(kgx).mod(N);
        if (base.compareTo(BigInteger.ZERO) < 0) {
            base = base.add(N);
        }
        BigInteger exp = a.add(u.multiply(x));
        return base.modPow(exp, N);
    }

    public static BigInteger computeServerS(BigInteger A, BigInteger v, BigInteger u, BigInteger b, BigInteger N) {
        validateA(A, N);
        validateU(u);
        BigInteger vu = v.modPow(u, N);
        BigInteger base = A.multiply(vu).mod(N);
        return base.modPow(b, N);
    }

    public static byte[] deriveSessionKeyK(String hashAlgorithm, BigInteger S, BigInteger N) {
        byte[] padS = toBytes(S, getByteLength(N));
        return hash(hashAlgorithm, padS);
    }

    public static byte[] computeM1(String hashAlgorithm, BigInteger N, BigInteger g, String identity, byte[] salt, BigInteger A, BigInteger B, byte[] K) {
        int nLen = getByteLength(N);
        byte[] nBytes = toBytes(N, nLen);
        byte[] gBytes = new byte[]{(byte) g.intValue()};

        byte[] hN = hash(hashAlgorithm, nBytes);
        byte[] hg = hash(hashAlgorithm, gBytes);
        byte[] hXor = new byte[hN.length];
        for (int i = 0; i < hN.length; i++) {
            hXor[i] = (byte) (hN[i] ^ hg[i]);
        }

        byte[] hI = hash(hashAlgorithm, identity.getBytes(StandardCharsets.UTF_8));
        byte[] padA = toBytes(A, nLen);
        byte[] padB = toBytes(B, nLen);

        return hash(hashAlgorithm, hXor, hI, salt, padA, padB, K);
    }

    public static byte[] computeM2(String hashAlgorithm, BigInteger N, BigInteger A, byte[] M1, byte[] K) {
        byte[] padA = toBytes(A, getByteLength(N));
        return hash(hashAlgorithm, padA, M1, K);
    }

    public static BigInteger randomExponent(SecureRandom random, BigInteger N) {
        SecureRandom rng = random != null ? random : SECURE_RANDOM;
        byte[] bytes = new byte[32]; // 256 bits
        BigInteger exp;
        do {
            rng.nextBytes(bytes);
            bytes[0] |= (byte) 0x80; // Ensure >= 256 bits
            exp = new BigInteger(1, bytes);
        } while (exp.compareTo(BigInteger.ONE) <= 0 || exp.compareTo(N) >= 0);
        return exp;
    }

    public static void validateA(BigInteger A, BigInteger N) {
        if (A == null) {
            throw new IllegalArgumentException("Client public value A cannot be null");
        }
        if (A.compareTo(BigInteger.ZERO) <= 0) {
            throw new IllegalArgumentException("Client public value A must be positive");
        }
        if (A.mod(N).equals(BigInteger.ZERO)) {
            throw new IllegalArgumentException("Client public value A mod N cannot be zero");
        }
        if (A.compareTo(N) >= 0) {
            throw new IllegalArgumentException("Client public value A must be less than N");
        }
    }

    public static void validateB(BigInteger B, BigInteger N) {
        if (B == null) {
            throw new IllegalArgumentException("Server public value B cannot be null");
        }
        if (B.compareTo(BigInteger.ZERO) <= 0) {
            throw new IllegalArgumentException("Server public value B must be positive");
        }
        if (B.mod(N).equals(BigInteger.ZERO)) {
            throw new IllegalArgumentException("Server public value B mod N cannot be zero");
        }
        if (B.compareTo(N) >= 0) {
            throw new IllegalArgumentException("Server public value B must be less than N");
        }
    }

    public static void validateU(BigInteger u) {
        if (u == null || u.equals(BigInteger.ZERO)) {
            throw new IllegalArgumentException("SRP scrambling parameter u cannot be zero");
        }
    }

    public static void validateVersion(String version) {
        if (!PairingMessages.PROTOCOL_VERSION.equals(version)) {
            throw new IllegalArgumentException("Unsupported pairing protocol version: " + version);
        }
    }

    public static PairingMessages.PairingResult validateProtocolVersion(String version) {
        if (!PairingMessages.PROTOCOL_VERSION.equals(version)) {
            return PairingMessages.PairingResult.error(PairingMessages.ERR_VERSION, PairingMessages.MSG_VERSION_MISMATCH);
        }
        return PairingMessages.PairingResult.ok();
    }

    public static String sanitizeDeviceName(String raw) {
        if (raw == null) return "Remote";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                sb.append(c);
            }
        }
        String clean = sb.toString().trim();
        if (clean.isEmpty()) {
            clean = "Remote";
        }
        byte[] bytes = clean.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 64) {
            int end = 64;
            while (end > 0 && (bytes[end - 1] & 0xC0) == 0x80) {
                end--;
            }
            clean = new String(bytes, 0, end, StandardCharsets.UTF_8).trim();
            if (clean.isEmpty()) clean = "Remote";
        }
        return clean;
    }

    public static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws NoSuchAlgorithmException, InvalidKeyException {
        if (prk == null || prk.length == 0) {
            throw new IllegalArgumentException("PRK cannot be empty");
        }
        if (length <= 0 || length > 255 * 32) {
            throw new IllegalArgumentException("Invalid output length for HKDF-SHA256: " + length);
        }
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(prk, HMAC_ALGORITHM));

        byte[] result = new byte[length];
        byte[] t = new byte[0];
        int generated = 0;
        byte round = 1;

        while (generated < length) {
            mac.reset();
            mac.update(t);
            if (info != null) {
                mac.update(info);
            }
            mac.update(round);
            t = mac.doFinal();

            int copyLen = Math.min(t.length, length - generated);
            System.arraycopy(t, 0, result, generated, copyLen);
            generated += copyLen;
            round++;
        }
        return result;
    }
    public static byte[] hkdfExtract(byte[] salt, byte[] ikm) throws NoSuchAlgorithmException, InvalidKeyException {
        byte[] effectiveSalt = (salt != null && salt.length > 0) ? salt : new byte[32];
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(effectiveSalt, HMAC_ALGORITHM));
        return mac.doFinal(ikm != null ? ikm : new byte[0]);
    }

    public static byte[] deriveAesKey(byte[] masterKeyK) {
        try {
            byte[] prk = hkdfExtract(new byte[32], masterKeyK);
            return hkdfExpand(prk, AES_KEY_INFO, AES_KEY_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("HKDF key derivation failed", e);
        }
    }

    public static PairingMessages.EncryptedRecord seal(byte[] aesKey, byte[] aad, byte[] plaintext) throws GeneralSecurityException {
        byte[] nonce = new byte[GCM_NONCE_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(nonce);

        Cipher cipher = Cipher.getInstance(AES_GCM_CIPHER);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), spec);
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }
        byte[] ciphertext = cipher.doFinal(plaintext);
        return new PairingMessages.EncryptedRecord(nonce, ciphertext);
    }

    public static byte[] open(byte[] aesKey, byte[] aad, PairingMessages.EncryptedRecord record) throws GeneralSecurityException {
        if (record == null) {
            throw new IllegalArgumentException("Record cannot be null");
        }
        Cipher cipher = Cipher.getInstance(AES_GCM_CIPHER);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, record.nonce);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), spec);
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(record.ciphertext);
    }
}
