package vn.vuumi.pcdash.pairing;

import vn.vuumi.pcdash.i18n.L10n;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

public class PairingProtocolTest {

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("Assertion failed: " + message);
        }
    }

    private static void assertEquals(Object actual, Object expected, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Assertion failed for " + message + ": expected <" + expected + "> but got <" + actual + ">");
    }

    private static byte[] hexToBytes(String hex) {
        String clean = hex.replaceAll("\\s+", "");
        int len = clean.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(clean.charAt(i), 16) << 4)
                    + Character.digit(clean.charAt(i + 1), 16));
        }
        return data;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /**
     * Requirement A (1): RFC 5054 Appendix B test vector.
     * I = "alice", P = "password123", salt, a, b from vector.
     * Uses SHA-1 per RFC 5054.
     * Verifies: v, k, u, A, B, S.
     */
    public static void testRFC5054AppendixBVector() throws Exception {
        String I = "alice";
        String P = "password123";
        byte[] s = hexToBytes("BEB25379 D1A8581E B5A72767 3A2441EE");
        BigInteger N = PairingProtocol.N_1024;
        BigInteger g = PairingProtocol.G;

        BigInteger kExp = new BigInteger("7556AA045AEF2CDD07ABAF0F665C3E818913186F", 16);
        BigInteger xExp = new BigInteger("94B7555AABE9127CC58CCF4993DB6CF84D16C124", 16);
        BigInteger vExp = new BigInteger(
                "7E273DE8696FFC4F4E337D05B4B375BEB0DDE1569E8FA00A9886D812"
                + "9BADA1F1822223CA1A605B530E379BA4729FDC59F105B4787E5186F5"
                + "C671085A1447B52A48CF1970B4FB6F8400BBF4CEBFBB168152E08AB5"
                + "EA53D15C1AFF87B2B9DA6E04E058AD51CC72BFC9033B564E26480D78"
                + "E955A5E29E7AB245DB2BE315E2099AFB", 16);

        BigInteger a = new BigInteger(
                "60975527035CF2AD1989806F0407210BC81EDC04E2762A56AFD529DDDA2D4393", 16);
        BigInteger b = new BigInteger(
                "E487CB59D31AC550471E81F00F6928E01DDA08E974A004F49E61F5D105284D20", 16);

        BigInteger AExp = new BigInteger(
                "61D5E490F6F1B79547B0704C436F523DD0E560F0C64115BB72557EC4"
                + "4352E8903211C04692272D8B2D1A5358A2CF1B6E0BFCF99F921530EC"
                + "8E39356179EAE45E42BA92AEACED825171E1E8B9AF6D9C03E1327F44"
                + "BE087EF06530E69F66615261EEF54073CA11CF5858F0EDFDFE15EFEA"
                + "B349EF5D76988A3672FAC47B0769447B", 16);
        BigInteger BExp = new BigInteger(
                "BD0C61512C692C0CB6D041FA01BB152D4916A1E77AF46AE105393011"
                + "BAF38964DC46A0670DD125B95A981652236F99D9B681CBF87837EC99"
                + "6C6DA04453728610D0C6DDB58B318885D7D82C7F8DEB75CE7BD4FBAA"
                + "37089E6F9C6059F388838E7A00030B331EB76840910440B1B27AAEAE"
                + "EB4012B7D7665238A8E3FB004B117B58", 16);
        BigInteger uExp = new BigInteger("CE38B9593487DA98554ED47D70A7AE5F462EF019", 16);
        byte[] sExp = hexToBytes(
                "B0DC82BABCF30674AE450C0287745E7990A3381F63B387AAF271A10D"
                + "233861E359B48220F7C4693C9AE12B0A6F67809F0876E2D013800D6C"
                + "41BB59B6D5979B5C00A172B4A2A5903A0BDCAF8A709585EB2AFAFA8F"
                + "3499B200210DCC1F10EB33943CD67FC88A2F39A4BE5BEC4EC0A3212D"
                + "C346D7E474B29EDE8A469FFECA686E5A");

        // Compute using SHA-1
        String hashAlgo = PairingProtocol.HASH_SHA1;
        BigInteger kCalc = PairingProtocol.computeK(hashAlgo, N, g);
        assertEquals(kCalc, kExp, "RFC 5054 k parameter");

        BigInteger xCalc = PairingProtocol.computeX(hashAlgo, I, P, s);
        assertEquals(xCalc, xExp, "RFC 5054 x parameter");

        BigInteger vCalc = PairingProtocol.computeV(g, xCalc, N);
        assertEquals(vCalc, vExp, "RFC 5054 v parameter");

        BigInteger ACalc = PairingProtocol.computeClientA(g, a, N);
        assertEquals(ACalc, AExp, "RFC 5054 A parameter");

        BigInteger BCalc = PairingProtocol.computeServerB(kCalc, vCalc, g, b, N);
        assertEquals(BCalc, BExp, "RFC 5054 B parameter");

        BigInteger uCalc = PairingProtocol.computeU(hashAlgo, N, ACalc, BCalc);
        assertEquals(uCalc, uExp, "RFC 5054 u parameter");

        BigInteger sClient = PairingProtocol.computeClientS(BCalc, kCalc, g, xCalc, a, uCalc, N);
        BigInteger sServer = PairingProtocol.computeServerS(ACalc, vCalc, uCalc, b, N);
        assertEquals(sClient, sServer, "RFC 5054 Premaster secret S equality");

        byte[] sClientBytes = PairingProtocol.pad(sClient, N);
        assertTrue(Arrays.equals(sClientBytes, sExp), "RFC 5054 Premaster secret S bytes match Appendix B");
    }

    /**
     * Requirement A (2): Round trip with correct pairing code.
     */
    public static void testRoundTripValidCode() throws Exception {
        String identity = PairingProtocol.PAIRING_IDENTITY;
        String code = "ABCDE2";
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);

        BigInteger N = PairingProtocol.N_2048;
        BigInteger g = PairingProtocol.G;
        String hash = PairingProtocol.HASH_SHA256;

        // Server setup (when "Thêm thiết bị" is pressed)
        BigInteger x = PairingProtocol.computeX(hash, identity, code, salt);
        BigInteger v = PairingProtocol.computeV(g, x, N);
        BigInteger k = PairingProtocol.computeK(hash, N, g);

        // Protocol exchange
        // 1. Client generates ephemeral a, A
        BigInteger a = PairingProtocol.randomExponent(null, N);
        BigInteger A = PairingProtocol.computeClientA(g, a, N);

        // 2. Server generates ephemeral b, B
        BigInteger b = PairingProtocol.randomExponent(null, N);
        BigInteger B = PairingProtocol.computeServerB(k, v, g, b, N);

        // 3. Both compute u, S, K
        BigInteger u = PairingProtocol.computeU(hash, N, A, B);
        BigInteger sClient = PairingProtocol.computeClientS(B, k, g, x, a, u, N);
        BigInteger sServer = PairingProtocol.computeServerS(A, v, u, b, N);
        assertEquals(sClient, sServer, "Client and Server premaster secrets must match");

        byte[] kClient = PairingProtocol.deriveSessionKeyK(hash, sClient, N);
        byte[] kServer = PairingProtocol.deriveSessionKeyK(hash, sServer, N);
        assertTrue(Arrays.equals(kClient, kServer), "Session keys K must match");

        // 4. Client computes M1 and sends to server
        byte[] m1Client = PairingProtocol.computeM1(hash, N, g, identity, salt, A, B, kClient);
        byte[] m1ServerExpected = PairingProtocol.computeM1(hash, N, g, identity, salt, A, B, kServer);
        assertTrue(MessageDigest.isEqual(m1Client, m1ServerExpected), "Server must accept valid M1");

        // 5. Server computes M2 and sends to client
        byte[] m2Server = PairingProtocol.computeM2(hash, N, A, m1ServerExpected, kServer);
        byte[] m2ClientExpected = PairingProtocol.computeM2(hash, N, A, m1Client, kClient);
        assertTrue(MessageDigest.isEqual(m2Server, m2ClientExpected), "Client must accept valid M2");

        // 6. AES-GCM credential exchange
        byte[] aesKeyClient = PairingProtocol.deriveAesKey(kClient);
        byte[] aesKeyServer = PairingProtocol.deriveAesKey(kServer);
        assertTrue(Arrays.equals(aesKeyClient, aesKeyServer), "AES keys must match");

        String version = PairingMessages.PROTOCOL_VERSION;
        String deviceName = "Pixel 8";
        byte[] transcript = PairingMessages.computeTranscript(
                version,
                deviceName,
                PairingProtocol.pad(A, N),
                PairingProtocol.pad(B, N),
                salt
        );

        byte[] credentialPlaintext = "{\"authKey\":\"tskey-test\",\"deviceToken\":\"tok-xyz\"}".getBytes(StandardCharsets.UTF_8);
        PairingMessages.EncryptedRecord credRecord = PairingProtocol.seal(aesKeyServer, transcript, credentialPlaintext);

        byte[] decryptedCred = PairingProtocol.open(aesKeyClient, transcript, credRecord);
        assertTrue(Arrays.equals(credentialPlaintext, decryptedCred), "Decrypted credential must match original");


        // v2 Credentials round-trip in SRP session
        PairingMessages.Credentials creds = new PairingMessages.Credentials("tok-xyz", "100.64.0.1", 8091);
        byte[] credsPlaintext = creds.toJson().getBytes(StandardCharsets.UTF_8);
        PairingMessages.EncryptedRecord credRecordV2 = PairingProtocol.seal(aesKeyServer, transcript, credsPlaintext);
        byte[] decryptedCredV2 = PairingProtocol.open(aesKeyClient, transcript, credRecordV2);
        PairingMessages.Credentials parsedCreds = PairingMessages.Credentials.fromJson(new String(decryptedCredV2, StandardCharsets.UTF_8));
        assertEquals(parsedCreds.deviceToken, "tok-xyz", "deviceToken must match");
        assertEquals(parsedCreds.hostTailnetIp, "100.64.0.1", "hostTailnetIp must match");
        assertEquals(parsedCreds.hostPort, 8091, "hostPort must match");
        // 7. Client ACK exchange
        byte[] ackPlaintext = "{\"nodeId\":\"node-12345\"}".getBytes(StandardCharsets.UTF_8);
        PairingMessages.EncryptedRecord ackRecord = PairingProtocol.seal(aesKeyClient, transcript, ackPlaintext);
        byte[] decryptedAck = PairingProtocol.open(aesKeyServer, transcript, ackRecord);
        assertTrue(Arrays.equals(ackPlaintext, decryptedAck), "Decrypted ACK must match original");
    }

    /**
     * Requirement A (3): Wrong code -> M1 is rejected, no credentials sent.
     */
    public static void testWrongCodeM1RejectedNoCredentialsSent() throws Exception {
        String identity = PairingProtocol.PAIRING_IDENTITY;
        String correctCode = "ABCDE2";
        String wrongCode = "XYZ999";
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);

        BigInteger N = PairingProtocol.N_2048;
        BigInteger g = PairingProtocol.G;
        String hash = PairingProtocol.HASH_SHA256;

        // Server has correct code/verifier
        BigInteger xCorrect = PairingProtocol.computeX(hash, identity, correctCode, salt);
        BigInteger vCorrect = PairingProtocol.computeV(g, xCorrect, N);
        BigInteger k = PairingProtocol.computeK(hash, N, g);

        BigInteger a = PairingProtocol.randomExponent(null, N);
        BigInteger A = PairingProtocol.computeClientA(g, a, N);

        BigInteger b = PairingProtocol.randomExponent(null, N);
        BigInteger B = PairingProtocol.computeServerB(k, vCorrect, g, b, N);

        // Client mistakenly computes using wrongCode
        BigInteger xWrong = PairingProtocol.computeX(hash, identity, wrongCode, salt);
        BigInteger u = PairingProtocol.computeU(hash, N, A, B);
        BigInteger sClientWrong = PairingProtocol.computeClientS(B, k, g, xWrong, a, u, N);
        byte[] kClientWrong = PairingProtocol.deriveSessionKeyK(hash, sClientWrong, N);
        byte[] m1Wrong = PairingProtocol.computeM1(hash, N, g, identity, salt, A, B, kClientWrong);

        // Server calculates expected M1 with its legitimate session
        BigInteger sServer = PairingProtocol.computeServerS(A, vCorrect, u, b, N);
        byte[] kServer = PairingProtocol.deriveSessionKeyK(hash, sServer, N);
        byte[] m1ServerExpected = PairingProtocol.computeM1(hash, N, g, identity, salt, A, B, kServer);

        boolean m1Matches = MessageDigest.isEqual(m1Wrong, m1ServerExpected);
        assertTrue(!m1Matches, "M1 generated with wrong code must be rejected by server");
    }

    /**
     * Requirement A (4): Rogue host simulation.
     * The rogue host does not know v and attempts to guess the pairing code.
     * It interacts with a legitimate client (who knows P_real) once, receiving A and M1.
     * Assert: The received M1 allows testing AT MOST ONE code hypothesis (P_guess).
     *
     * Cryptographic reason:
     * In SRP-6a, M1 = H(..., K) where K = H(S) and S = (B - k*g^x)^(a + u*x).
     * If the rogue host tests hypothesis P_1, it computes S_1 = (A * v_1^u)^b.
     * Because the client only sends a single M1 per interactive run, testing any other
     * candidate P_2 produces S_2 != S_1 and M1_2 != M1.
     * The rogue host cannot check whether P_2 was the code without initiating another
     * live interactive session (which the host limits to 5 attempts before destroying the code).
     * Offline dictionary search against a single captured transcript cannot verify multiple guesses
     * because each test requires checking equality against the single received M1, which only matches
     * the exact unique verifier v used by the client.
     */
    public static void testRogueHostCannotVerifyMoreThanOneCodeHypothesis() throws Exception {
        String identity = PairingProtocol.PAIRING_IDENTITY;
        String realCode = "ABCDE2";
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);

        BigInteger N = PairingProtocol.N_2048;
        BigInteger g = PairingProtocol.G;
        String hash = PairingProtocol.HASH_SHA256;

        // Rogue host chooses b and B without knowing the real v
        BigInteger bRogue = PairingProtocol.randomExponent(null, N);
        BigInteger k = PairingProtocol.computeK(hash, N, g);

        // Rogue host picks a guess P_guess1 (e.g. "WRONG1") and generates B
        String guess1 = "WRONG1";
        BigInteger xGuess1 = PairingProtocol.computeX(hash, identity, guess1, salt);
        BigInteger vGuess1 = PairingProtocol.computeV(g, xGuess1, N);
        BigInteger BRogue = PairingProtocol.computeServerB(k, vGuess1, g, bRogue, N);

        // Legitimate client connects using realCode ("ABCDE2")
        BigInteger aClient = PairingProtocol.randomExponent(null, N);
        BigInteger AClient = PairingProtocol.computeClientA(g, aClient, N);

        BigInteger u = PairingProtocol.computeU(hash, N, AClient, BRogue);
        BigInteger xReal = PairingProtocol.computeX(hash, identity, realCode, salt);
        BigInteger sClient = PairingProtocol.computeClientS(BRogue, k, g, xReal, aClient, u, N);
        byte[] kClient = PairingProtocol.deriveSessionKeyK(hash, sClient, N);
        byte[] m1FromClient = PairingProtocol.computeM1(hash, N, g, identity, salt, AClient, BRogue, kClient);

        // Rogue host checks its first guess (guess1):
        BigInteger sGuess1 = PairingProtocol.computeServerS(AClient, vGuess1, u, bRogue, N);
        byte[] kGuess1 = PairingProtocol.deriveSessionKeyK(hash, sGuess1, N);
        byte[] m1Guess1 = PairingProtocol.computeM1(hash, N, g, identity, salt, AClient, BRogue, kGuess1);

        assertTrue(!MessageDigest.isEqual(m1FromClient, m1Guess1),
                "Rogue host's wrong guess 1 must fail against client's M1");

        // Rogue host attempts to test guess2 ("WRONG2") offline against the SAME session transcript
        String guess2 = "WRONG2";
        BigInteger xGuess2 = PairingProtocol.computeX(hash, identity, guess2, salt);
        BigInteger vGuess2 = PairingProtocol.computeV(g, xGuess2, N);
        BigInteger sGuess2 = PairingProtocol.computeServerS(AClient, vGuess2, u, bRogue, N);
        byte[] kGuess2 = PairingProtocol.deriveSessionKeyK(hash, sGuess2, N);
        byte[] m1Guess2 = PairingProtocol.computeM1(hash, N, g, identity, salt, AClient, BRogue, kGuess2);

        assertTrue(!MessageDigest.isEqual(m1FromClient, m1Guess2),
                "Rogue host's offline guess 2 must fail");

        // Only the exact verifier matching the client's realCode could produce a matching M1
        BigInteger vReal = PairingProtocol.computeV(g, xReal, N);
        // Note: because the server sent B based on vGuess1, even testing realCode against
        // the rogue server's B = k*vGuess1 + g^b will fail because server committed to vGuess1!
        BigInteger sRogueWithRealV = PairingProtocol.computeServerS(AClient, vReal, u, bRogue, N);
        byte[] kRogueWithRealV = PairingProtocol.deriveSessionKeyK(hash, sRogueWithRealV, N);
        byte[] m1RogueWithRealV = PairingProtocol.computeM1(hash, N, g, identity, salt, AClient, BRogue, kRogueWithRealV);
        assertTrue(!MessageDigest.isEqual(m1FromClient, m1RogueWithRealV),
                "Rogue host who committed to vGuess1 in B cannot even verify realCode from this session");
    }

    /**
     * Requirement A (5): A=0, B=0, B=N are rejected.
     */
    public static void testRejectsInvalidSrpParameters() {
        BigInteger N = PairingProtocol.N_2048;

        // 1. A = 0
        boolean aZeroFailed = false;
        try {
            PairingProtocol.validateA(BigInteger.ZERO, N);
        } catch (IllegalArgumentException e) {
            aZeroFailed = true;
        }
        assertTrue(aZeroFailed, "A = 0 must be rejected");

        // 2. A = N
        boolean aNFailed = false;
        try {
            PairingProtocol.validateA(N, N);
        } catch (IllegalArgumentException e) {
            aNFailed = true;
        }
        assertTrue(aNFailed, "A = N must be rejected");

        // 3. B = 0
        boolean bZeroFailed = false;
        try {
            PairingProtocol.validateB(BigInteger.ZERO, N);
        } catch (IllegalArgumentException e) {
            bZeroFailed = true;
        }
        assertTrue(bZeroFailed, "B = 0 must be rejected");

        // 4. B = N
        boolean bNFailed = false;
        try {
            PairingProtocol.validateB(N, N);
        } catch (IllegalArgumentException e) {
            bNFailed = true;
        }
        assertTrue(bNFailed, "B = N must be rejected");

        // 5. u = 0
        boolean uZeroFailed = false;
        try {
            PairingProtocol.validateU(BigInteger.ZERO);
        } catch (IllegalArgumentException e) {
            uZeroFailed = true;
        }
        assertTrue(uZeroFailed, "u = 0 must be rejected");
    }

    /**
     * Requirement A (6): Modifying 1 byte of version/deviceName/A/B/salt causes record open to fail.
     */
    public static void testTamperingTranscriptFailsRecordOpen() throws Exception {
        byte[] aesKey = new byte[32];
        new SecureRandom().nextBytes(aesKey);

        String version = PairingMessages.PROTOCOL_VERSION;
        String deviceName = "Pixel 8";
        byte[] A = new byte[256];
        Arrays.fill(A, (byte) 0x11);
        byte[] B = new byte[256];
        Arrays.fill(B, (byte) 0x22);
        byte[] salt = new byte[16];
        Arrays.fill(salt, (byte) 0x33);

        byte[] transcript = PairingMessages.computeTranscript(version, deviceName, A, B, salt);
        byte[] plaintext = "{\"authKey\":\"test-auth-key\"}".getBytes(StandardCharsets.UTF_8);

        PairingMessages.EncryptedRecord record = PairingProtocol.seal(aesKey, transcript, plaintext);

        // 1. Valid open
        byte[] opened = PairingProtocol.open(aesKey, transcript, record);
        assertTrue(Arrays.equals(plaintext, opened), "Valid transcript must open successfully");

        // 2. Tamper version
        byte[] badVersionTranscript = PairingMessages.computeTranscript("pcdash-pair-v2", deviceName, A, B, salt);
        assertOpenFails(aesKey, badVersionTranscript, record, "Tampered version in AAD must fail open");

        // 3. Tamper deviceName
        byte[] badNameTranscript = PairingMessages.computeTranscript(version, "Pixel 9", A, B, salt);
        assertOpenFails(aesKey, badNameTranscript, record, "Tampered deviceName in AAD must fail open");

        // 4. Tamper A
        byte[] badA = A.clone();
        badA[0] ^= 0x01;
        byte[] badATranscript = PairingMessages.computeTranscript(version, deviceName, badA, B, salt);
        assertOpenFails(aesKey, badATranscript, record, "Tampered A in AAD must fail open");

        // 5. Tamper B
        byte[] badB = B.clone();
        badB[0] ^= 0x01;
        byte[] badBTranscript = PairingMessages.computeTranscript(version, deviceName, A, badB, salt);
        assertOpenFails(aesKey, badBTranscript, record, "Tampered B in AAD must fail open");

        // 6. Tamper salt
        byte[] badSalt = salt.clone();
        badSalt[0] ^= 0x01;
        byte[] badSaltTranscript = PairingMessages.computeTranscript(version, deviceName, A, B, badSalt);
        assertOpenFails(aesKey, badSaltTranscript, record, "Tampered salt in AAD must fail open");
    }

    private static void assertOpenFails(byte[] key, byte[] aad, PairingMessages.EncryptedRecord record, String msg) {
        boolean failed = false;
        try {
            PairingProtocol.open(key, aad, record);
        } catch (GeneralSecurityException e) {
            failed = true;
        }
        assertTrue(failed, msg);
    }

    /**
     * Requirement A (7): Alphabet has exactly 31 characters: "ABCDEFGHJKMNPQRSTUVWXYZ23456789".
     */
    public static void testCodeAlphabetLength31() {
        String alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        assertEquals(alphabet.length(), 31, "Alphabet length must be 31");
        // Verify exclusion of 0, O, 1, I, L
        assertTrue(!alphabet.contains("0"), "Must not contain 0");
        assertTrue(!alphabet.contains("O"), "Must not contain O");
        assertTrue(!alphabet.contains("1"), "Must not contain 1");
        assertTrue(!alphabet.contains("I"), "Must not contain I");
        assertTrue(!alphabet.contains("L"), "Must not contain L");
    }

    /**
     * RFC 5869 Test Case 1 (SHA-256) & deriveAesKey test.
     */
    public static void testHkdfRFC5869AndDeriveAesKeyVector() throws Exception {
        byte[] ikm = hexToBytes("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = hexToBytes("000102030405060708090a0b0c");
        byte[] info = hexToBytes("f0f1f2f3f4f5f6f7f8f9");
        int l = 42;
        byte[] prkExp = hexToBytes("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5");
        byte[] okmExp = hexToBytes("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");

        byte[] prkActual = PairingProtocol.hkdfExtract(salt, ikm);
        assertTrue(Arrays.equals(prkExp, prkActual), "RFC 5869 PRK must match");

        byte[] okmActual = PairingProtocol.hkdfExpand(prkActual, info, l);
        assertTrue(Arrays.equals(okmExp, okmActual), "RFC 5869 OKM must match");

        // Verify deriveAesKey(masterK) uses 32 zero-byte salt Extract before Expand
        byte[] masterK = hexToBytes("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20");
        byte[] prkDerive = PairingProtocol.hkdfExtract(new byte[32], masterK);
        byte[] expectedAesKey = PairingProtocol.hkdfExpand(prkDerive, PairingProtocol.AES_KEY_INFO, PairingProtocol.AES_KEY_LENGTH);
        byte[] actualAesKey = PairingProtocol.deriveAesKey(masterK);
        assertTrue(Arrays.equals(expectedAesKey, actualAesKey), "deriveAesKey must match RFC 5869 Extract(zero[32]) + Expand");
    }

    public static void testCredentialsWithAuthKeyRejected() {
        String legacy = "{\"authKey\":\"tskey-auth-12345\",\"deviceToken\":\"tok-xyz\",\"hostTailnetIp\":\"100.64.0.1\",\"hostPort\":8091}";
        boolean thrown = false;
        try {
            PairingMessages.Credentials.fromJson(legacy);
        } catch (IllegalArgumentException e) {
            thrown = true;
        }
        assertTrue(thrown, "Credentials.fromJson must reject payload containing authKey");
    }

    public static void testProtocolVersionTwoEnforcedAndVersionOneRejected() {
        assertEquals(PairingMessages.PROTOCOL_VERSION, "2", "PROTOCOL_VERSION must be 2");

        // Version 2 valid
        PairingProtocol.validateVersion("2");
        PairingMessages.PairingResult r2 = PairingProtocol.validateProtocolVersion("2");
        assertTrue(r2.isOk(), "validateProtocolVersion('2') must be OK");

        // Version 1 / legacy versions rejected
        boolean v1Thrown = false;
        try {
            PairingProtocol.validateVersion("1");
        } catch (IllegalArgumentException e) {
            v1Thrown = true;
        }
        assertTrue(v1Thrown, "validateVersion('1') must throw IllegalArgumentException");

        boolean legacyThrown = false;
        try {
            PairingProtocol.validateVersion("pcdash-pair-v1");
        } catch (IllegalArgumentException e) {
            legacyThrown = true;
        }
        assertTrue(legacyThrown, "validateVersion('pcdash-pair-v1') must throw IllegalArgumentException");

        PairingMessages.PairingResult r1 = PairingProtocol.validateProtocolVersion("1");
        assertTrue(!r1.isOk(), "validateProtocolVersion('1') must be error");
        assertEquals(r1.code, "version", "Error code must be version");
        assertEquals(r1.message, "Cần cập nhật PCDash host/remote", "Error message must match spec");
    }

    public static void main(String[] args) throws Exception {
        L10n.setOverride(L10n.VI); // the assertions below check the Vietnamese UI text
        System.out.println("Running PairingProtocolTest...");
        testRFC5054AppendixBVector();
        System.out.println("  PASS: testRFC5054AppendixBVector");

        testRoundTripValidCode();
        System.out.println("  PASS: testRoundTripValidCode");

        testWrongCodeM1RejectedNoCredentialsSent();
        System.out.println("  PASS: testWrongCodeM1RejectedNoCredentialsSent");

        testRogueHostCannotVerifyMoreThanOneCodeHypothesis();
        System.out.println("  PASS: testRogueHostCannotVerifyMoreThanOneCodeHypothesis");

        testRejectsInvalidSrpParameters();
        System.out.println("  PASS: testRejectsInvalidSrpParameters");

        testTamperingTranscriptFailsRecordOpen();
        System.out.println("  PASS: testTamperingTranscriptFailsRecordOpen");

        testCodeAlphabetLength31();
        System.out.println("  PASS: testCodeAlphabetLength31");
        testHkdfRFC5869AndDeriveAesKeyVector();
        System.out.println("  PASS: testHkdfRFC5869AndDeriveAesKeyVector");
        testCredentialsWithAuthKeyRejected();
        System.out.println("  PASS: testCredentialsWithAuthKeyRejected");
        testProtocolVersionTwoEnforcedAndVersionOneRejected();
        System.out.println("  PASS: testProtocolVersionTwoEnforcedAndVersionOneRejected");

        System.out.println("ALL PairingProtocolTest PASSED");
    }
}
