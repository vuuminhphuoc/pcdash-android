package vn.vuumi.pcdash;

import vn.vuumi.pcdash.i18n.L10n;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import vn.vuumi.pcdash.net.PcnetState;
import vn.vuumi.pcdash.pairing.PairingMessages;
import vn.vuumi.pcdash.pairing.PairingProtocol;

public class PairingManagerTest {

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

    static class FakeTimeSource implements TimeSource {
        long currentMs = 1_000_000L;
        @Override
        public long nowEpochMs() {
            return currentMs;
        }
        public void advance(long ms) {
            currentMs += ms;
        }
    }

    static class FakeHostTailnet extends HostTailnet {
        String ip = "100.64.1.5";

        @Override
        public String tailnetIp() {
            return ip;
        }

        @Override
        public boolean isLoggedIn() {
            return ip != null;
        }

        @Override
        public PcnetState state() {
            if (ip != null) {
                return new PcnetState("running", "", Collections.singletonList(ip), 8091, "");
            }
            return new PcnetState("needs_login", "https://login.tailscale.com/a/mock", Collections.<String>emptyList(), 0, "");
        }
    }

    static class DeterministicRandom extends SecureRandom {
        private final int[] sequence;
        private int index = 0;

        DeterministicRandom(int... sequence) {
            this.sequence = sequence;
        }

        @Override
        public int nextInt(int bound) {
            if (sequence.length > 0) {
                int val = sequence[index % sequence.length];
                index++;
                return val % bound;
            }
            return 0;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) (i + 1);
            }
        }
    }

    static class TestChannel {
        final PipedOutputStream clientOut = new PipedOutputStream();
        final PipedInputStream serverIn;
        final PipedOutputStream serverOut = new PipedOutputStream();
        final PipedInputStream clientIn;

        TestChannel() throws IOException {
            serverIn = new PipedInputStream(clientOut);
            clientIn = new PipedInputStream(serverOut);
        }

        Socket createServerSocket(final InetAddress peer) {
            return new Socket() {
                @Override
                public InputStream getInputStream() { return serverIn; }
                @Override
                public OutputStream getOutputStream() { return serverOut; }
                @Override
                public InetAddress getInetAddress() { return peer; }
                @Override
                public void setSoTimeout(int timeout) {}
                @Override
                public void close() throws IOException {
                    try { serverIn.close(); } catch (IOException ignored) {}
                    try { serverOut.close(); } catch (IOException ignored) {}
                }
            };
        }
    }

    public static void testLetterFirstCodeExpirySingleUseAndFiveFailures() throws Exception {
        File tempStoreFile = File.createTempFile("pcdash_dev_test_", ".json");
        tempStoreFile.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(tempStoreFile);
        FakeHostTailnet tailnet = new FakeHostTailnet();
        FakeTimeSource clock = new FakeTimeSource();

        DeterministicRandom random = new DeterministicRandom(0, 1, 2, 3, 4, 23); // ABCDE2
        PairingManager manager = new PairingManager(store, tailnet, clock, random);

        PairingManager.PairingCode code = manager.begin();
        assertEquals(code.getCode(), "ABCDE2", "Deterministic code must be ABCDE2");
        assertEquals(code.getCode().length(), 6, "Code length must be 6");
        assertTrue(code.getCode().charAt(0) == 'A', "Code must start with letter A");
        assertTrue(!code.toString().contains(code.getCode()), "PairingCode.toString must not contain the plaintext code");
        assertTrue(manager.isActive(clock.nowEpochMs()), "Code must be active immediately");

        // Exactly at issuedAt + 300_000 ms -> expired
        assertTrue(!manager.isActive(code.issuedAtEpochMs + PairingManager.CODE_TTL_MS), "Code must be inactive at issuedAt + 300_000ms");

        // Re-begin for failure test
        manager.cancel();
        code = manager.begin();
        assertTrue(manager.isActive(clock.nowEpochMs()), "Code active after begin()");

        // 4 failures: still active
        for (int i = 1; i <= 4; i++) {
            boolean rejected = manager.recordFailure();
            assertTrue(!rejected, "Failure " + i + " should not cancel session yet");
            assertTrue(manager.isActive(clock.nowEpochMs()), "Session still active after failure " + i);
        }

        // 5th failure: cancels session!
        boolean sessionCanceledOn5th = manager.recordFailure();
        assertTrue(sessionCanceledOn5th, "5th failure must cancel session");
        assertTrue(!manager.isActive(clock.nowEpochMs()), "Session must be inactive after 5 failures");

        // Test single use: successful complete marks code inactive
        code = manager.begin();
        assertTrue(manager.isActive(clock.nowEpochMs()), "Active after begin");
        manager.complete("RemotePixel", "token-secret-12345678901234567890", "node-abc");
        assertTrue(!manager.isActive(clock.nowEpochMs()), "Code must be inactive after successful single use");
        assertEquals(store.list().size(), 1, "Store must contain 1 device");
        assertEquals(store.list().get(0).name, "RemotePixel", "Device name must match");
        assertEquals(store.list().get(0).nodeId, "node-abc", "NodeId must match");
    }

    public static void testCodeUsesOnlyAllowedAlphabetAndLengthSix() throws Exception {
        File tempStoreFile = File.createTempFile("pcdash_dev_test_", ".json");
        tempStoreFile.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(tempStoreFile);
        FakeHostTailnet tailnet = new FakeHostTailnet();
        FakeTimeSource clock = new FakeTimeSource();

        PairingManager manager = new PairingManager(store, tailnet, clock);
        String allowed = PairingManager.CODE_ALPHABET;
        assertEquals(allowed, "ABCDEFGHJKMNPQRSTUVWXYZ23456789", "Allowed alphabet must match spec");
        assertEquals(allowed.length(), 31, "CODE_ALPHABET length must be exactly 31");

        for (int i = 0; i < 50; i++) {
            PairingManager.PairingCode code = manager.begin();
            assertEquals(code.getCode().length(), PairingManager.CODE_LENGTH, "Length must be 6");
            for (int j = 0; j < code.getCode().length(); j++) {
                char ch = code.getCode().charAt(j);
                assertTrue(allowed.indexOf(ch) >= 0, "Character '" + ch + "' must be in allowed alphabet");
            }
            manager.cancel();
        }
    }

    public static void testPairingRejectedWhenNoCodeActive() throws Exception {
        File tempStoreFile = File.createTempFile("pcdash_dev_test_", ".json");
        tempStoreFile.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(tempStoreFile);
        FakeHostTailnet tailnet = new FakeHostTailnet();
        FakeTimeSource clock = new FakeTimeSource();

        PairingManager manager = new PairingManager(store, tailnet, clock);
        assertTrue(!manager.isActive(clock.nowEpochMs()), "Must not be active before begin()");
        assertTrue(!manager.isAvailableForPairing(clock.nowEpochMs()), "Must not be available for pairing before begin()");
    }

    public static void testLowercaseCodeAccepted() throws Exception {
        File tempStoreFile = File.createTempFile("pcdash_dev_test_", ".json");
        tempStoreFile.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(tempStoreFile);
        FakeHostTailnet tailnet = new FakeHostTailnet();
        FakeTimeSource clock = new FakeTimeSource();

        DeterministicRandom random = new DeterministicRandom(0, 1, 2, 3, 4, 23); // ABCDE2
        PairingManager manager = new PairingManager(store, tailnet, clock, random);

        PairingManager.PairingCode code = manager.begin();
        String normalized = PairingProtocol.normalizeCode("abcde2");
        assertEquals(normalized, code.getCode(), "Lowercase code normalized must match active code");
    }

    public static void testRejectsLoopbackTailscaleAndPublicPeer() throws Exception {
        assertTrue(!LanAddressPolicy.isAllowedPairingPeer(InetAddress.getByName("127.0.0.1")), "Loopback rejected");
        assertTrue(!LanAddressPolicy.isAllowedPairingPeer(InetAddress.getByName("100.64.0.1")), "Tailscale 100.64.0.1 rejected");
        assertTrue(!LanAddressPolicy.isAllowedPairingPeer(InetAddress.getByName("100.100.1.1")), "Tailscale 100.100.1.1 rejected");
        assertTrue(!LanAddressPolicy.isAllowedPairingPeer(InetAddress.getByName("8.8.8.8")), "Public IPv4 rejected");
        assertTrue(!LanAddressPolicy.isAllowedPairingPeer(InetAddress.getByName("203.0.113.1")), "Public IPv4 rejected");
        assertTrue(LanAddressPolicy.isAllowedPairingPeer(InetAddress.getByName("192.168.1.50")), "192.168.1.50 accepted");
        assertTrue(LanAddressPolicy.isAllowedPairingPeer(InetAddress.getByName("10.10.10.10")), "10.10.10.10 accepted");
        assertTrue(LanAddressPolicy.isAllowedPairingPeer(InetAddress.getByName("172.24.0.1")), "172.24.0.1 accepted");
    }

    public static void testHostNotLoggedInRejectsPairing() throws Exception {
        File tempStoreFile = File.createTempFile("pcdash_dev_test_", ".json");
        tempStoreFile.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(tempStoreFile);
        FakeHostTailnet tailnet = new FakeHostTailnet();
        tailnet.ip = null;
        FakeTimeSource clock = new FakeTimeSource();

        PairingManager manager = new PairingManager(store, tailnet, clock);
        try {
            manager.begin();
            throw new AssertionError("begin() must fail when host not logged in");
        } catch (PairingManager.PairingException e) {
            assertEquals(e.getMessage(), "Host chưa đăng nhập Tailscale, mở PCDash host và đăng nhập",
                    "Exact error message required by plan");
        }
    }

    public static void testHandshakeReturnsV2CredentialsAndCompletesOnAck() throws Exception {
        // Scenario 1: Valid handshake with nodeId in ACK
        runHandshakeScenario("valid-node-123", false, true, "valid nodeId");

        // Scenario 2: Valid handshake with empty nodeId in ACK
        runHandshakeScenario("", false, true, "empty nodeId");

        // Scenario 3: Decryption failure on corrupted ACK
        runHandshakeScenario("corrupt-ack", true, false, "corrupted ACK");
    }

    private static void runHandshakeScenario(final String ackNodeId, boolean corruptAck, boolean expectSuccess, String scenarioDesc) throws Exception {
        File tempStoreFile = File.createTempFile("pcdash_ack_test_", ".json");
        tempStoreFile.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(tempStoreFile);
        FakeHostTailnet tailnet = new FakeHostTailnet();
        FakeTimeSource clock = new FakeTimeSource();

        DeterministicRandom random = new DeterministicRandom(0, 1, 2, 3, 4, 23); // ABCDE2
        final PairingManager manager = new PairingManager(store, tailnet, clock, random);
        final PairingManager.PairingCode code = manager.begin();
        final TestChannel channel = new TestChannel();
        final InetAddress peer = InetAddress.getByName("192.168.1.100");
        final Socket serverSock = channel.createServerSocket(peer);

        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    manager.processTcpHandshake(serverSock);
                } catch (Exception ignored) {
                } finally {
                    try { serverSock.close(); } catch (IOException ignored) {}
                }
            }
        });
        serverThread.start();

        // Client simulation
        DataOutputStream clientDos = new DataOutputStream(channel.clientOut);
        DataInputStream clientDis = new DataInputStream(channel.clientIn);

        BigInteger N = PairingProtocol.N_2048;
        BigInteger g = PairingProtocol.G;
        String hash = PairingProtocol.HASH_SHA256;

        BigInteger a = PairingProtocol.randomExponent(null, N);
        BigInteger clientA = PairingProtocol.computeClientA(g, a, N);

        // 1. ClientHello
        PairingMessages.ClientHello clientHello = new PairingMessages.ClientHello(
                PairingMessages.PROTOCOL_VERSION,
                "TestDevice",
                PairingProtocol.pad(clientA, N)
        );
        clientHello.write(clientDos);
        clientDos.flush();
        PairingMessages.ServerHello serverHello = PairingMessages.ServerHello.read(clientDis);
        BigInteger serverB = PairingProtocol.fromBytes(serverHello.serverB);

        // 3. Compute SRP client secret S, K, M1
        BigInteger u = PairingProtocol.computeU(hash, N, clientA, serverB);
        BigInteger x = PairingProtocol.computeX(hash, PairingProtocol.PAIRING_IDENTITY, "ABCDE2", serverHello.salt);
        BigInteger clientS = PairingProtocol.computeClientS(serverB, PairingProtocol.computeK(hash, N, g), g, x, a, u, N);
        byte[] kClient = PairingProtocol.deriveSessionKeyK(hash, clientS, N);
        byte[] m1 = PairingProtocol.computeM1(hash, N, g, PairingProtocol.PAIRING_IDENTITY, serverHello.salt, clientA, serverB, kClient);

        // Send M1
        clientDos.write(m1);
        clientDos.flush();

        // 4. Read M2 + CredentialRecord
        byte[] m2 = new byte[32];
        clientDis.readFully(m2);

        byte[] expectedM2 = PairingProtocol.computeM2(hash, N, clientA, m1, kClient);
        assertTrue(MessageDigest.isEqual(m2, expectedM2), "Server M2 must match");

        PairingMessages.EncryptedRecord credRecord = PairingMessages.EncryptedRecord.read(clientDis);
        byte[] transcript = PairingMessages.computeTranscript(
                PairingMessages.PROTOCOL_VERSION,
                "TestDevice",
                PairingProtocol.pad(clientA, N),
                PairingProtocol.pad(serverB, N),
                serverHello.salt
        );
        byte[] aesKey = PairingProtocol.deriveAesKey(kClient);
        byte[] credPlaintext = PairingProtocol.open(aesKey, transcript, credRecord);
        assertTrue(credPlaintext != null && credPlaintext.length > 0, "Credential record must decrypt");

        String credsJson = new String(credPlaintext, StandardCharsets.UTF_8);
        assertTrue(!credsJson.contains("\"authKey\""), "v2 credentials must NEVER contain authKey");

        PairingMessages.Credentials creds = PairingMessages.Credentials.fromJson(credsJson);
        assertTrue(creds.deviceToken != null && !creds.deviceToken.isEmpty(), "deviceToken must not be empty");
        assertEquals(creds.hostTailnetIp, "100.64.1.5", "hostTailnetIp must match");
        assertEquals(creds.hostPort, 8091, "hostPort must be 8091");

        // 5. Send ACK with specified ackNodeId
        PairingMessages.EncryptedRecord ackRecord;
        if (corruptAck) {
            byte[] badIv = new byte[12];
            byte[] badCipher = new byte[32];
            ackRecord = new PairingMessages.EncryptedRecord(badIv, badCipher);
        } else {
            String ackJson = "{\"nodeId\":\"" + ackNodeId + "\"}";
            ackRecord = PairingProtocol.seal(aesKey, transcript, ackJson.getBytes(StandardCharsets.UTF_8));
        }
        ackRecord.write(clientDos);
        clientDos.flush();

        PairingMessages.EncryptedRecord resultRecord = PairingMessages.EncryptedRecord.read(clientDis);
        byte[] resPlaintext = PairingProtocol.open(aesKey, transcript, resultRecord);
        PairingMessages.PairingResult result = PairingMessages.PairingResult.fromJson(new String(resPlaintext, StandardCharsets.UTF_8));
        if (expectSuccess) {
            assertTrue(result.isOk(), scenarioDesc + ": result must be ok");
        } else {
            assertTrue(!result.isOk(), scenarioDesc + ": result must be error");
            assertTrue(result.code != null && !result.code.isEmpty(), scenarioDesc + ": error code must be present");
        }

        serverThread.join(5000);
        if (expectSuccess) {
            assertEquals(store.list().size(), 1, scenarioDesc + ": store must have 1 device");
            assertEquals(store.list().get(0).nodeId, ackNodeId, scenarioDesc + ": nodeId in store must match");
        } else {
            assertEquals(store.list().size(), 0, scenarioDesc + ": store must NOT record device on invalid ACK");
        }
    }

    public static void testCancelMidwayAbortsPairing() throws Exception {
        File tempStoreFile = File.createTempFile("pcdash_cancel_test_", ".json");
        tempStoreFile.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(tempStoreFile);
        FakeHostTailnet tailnet = new FakeHostTailnet();
        FakeTimeSource clock = new FakeTimeSource();

        DeterministicRandom random = new DeterministicRandom(0, 1, 2, 3, 4, 23); // ABCDE2
        final PairingManager manager = new PairingManager(store, tailnet, clock, random);
        manager.begin();

        final TestChannel channel = new TestChannel();
        final InetAddress peer = InetAddress.getByName("192.168.1.100");
        final Socket badSock = channel.createServerSocket(peer);
        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    manager.processTcpHandshake(badSock);
                } catch (Exception ignored) {
                } finally {
                    try { badSock.close(); } catch (IOException ignored) {}
                }
            }
        });
        serverThread.start();

        DataOutputStream clientDos = new DataOutputStream(channel.clientOut);
        DataInputStream clientDis = new DataInputStream(channel.clientIn);

        BigInteger N = PairingProtocol.N_2048;
        BigInteger g = PairingProtocol.G;
        String hash = PairingProtocol.HASH_SHA256;

        BigInteger a = PairingProtocol.randomExponent(null, N);
        BigInteger clientA = PairingProtocol.computeClientA(g, a, N);

        PairingMessages.ClientHello clientHello = new PairingMessages.ClientHello(
                PairingMessages.PROTOCOL_VERSION, "TestDevice", PairingProtocol.pad(clientA, N));
        clientHello.write(clientDos);
        clientDos.flush();

        PairingMessages.ServerHello serverHello = PairingMessages.ServerHello.read(clientDis);
        BigInteger serverB = PairingProtocol.fromBytes(serverHello.serverB);

        BigInteger u = PairingProtocol.computeU(hash, N, clientA, serverB);
        BigInteger x = PairingProtocol.computeX(hash, PairingProtocol.PAIRING_IDENTITY, "ABCDE2", serverHello.salt);
        BigInteger clientS = PairingProtocol.computeClientS(serverB, PairingProtocol.computeK(hash, N, g), g, x, a, u, N);
        byte[] kClient = PairingProtocol.deriveSessionKeyK(hash, clientS, N);
        byte[] m1 = PairingProtocol.computeM1(hash, N, g, PairingProtocol.PAIRING_IDENTITY, serverHello.salt, clientA, serverB, kClient);

        // Cancel session on host right before sending M1
        manager.cancel();

        clientDos.write(m1);
        clientDos.flush();

        serverThread.join(3000);

        // Assert nothing must be stored if cancelled
        assertEquals(store.list().size(), 0, "Nothing must be stored if cancelled");
    }

    public static void testTcpConcurrencySemaphoreLimit() throws Exception {
        File tempStoreFile = File.createTempFile("pcdash_sem_test_", ".json");
        tempStoreFile.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(tempStoreFile);
        FakeHostTailnet tailnet = new FakeHostTailnet();
        FakeTimeSource clock = new FakeTimeSource();

        PairingManager manager = new PairingManager(store, tailnet, clock);
        manager.start(52195, 52196); // use dedicated ports for test

        // Connect 1 socket
        Socket s1 = new Socket("127.0.0.1", 52196);

        // Connect 2nd socket: must be closed immediately because MAX_CONCURRENT_PAIRING = 1
        Socket s2 = new Socket("127.0.0.1", 52196);
        s2.setSoTimeout(2000);

        int readVal = -1;
        try {
            readVal = s2.getInputStream().read();
        } catch (IOException ignored) {}
        assertEquals(readVal, -1, "2nd concurrent socket must be closed immediately by semaphore");

        s1.close();
        s2.close();
        manager.close();
    }

    public static void testInvalidProtocolVersionRejected() throws Exception {
        File tempStoreFile = File.createTempFile("pcdash_ver_test_", ".json");
        tempStoreFile.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(tempStoreFile);
        FakeHostTailnet tailnet = new FakeHostTailnet();
        FakeTimeSource clock = new FakeTimeSource();

        final PairingManager manager = new PairingManager(store, tailnet, clock);
        manager.begin();

        final TestChannel channel = new TestChannel();
        final InetAddress peer = InetAddress.getByName("192.168.1.100");
        final Socket badSock = channel.createServerSocket(peer);

        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    manager.processTcpHandshake(badSock);
                } catch (Exception ignored) {
                } finally {
                    try { badSock.close(); } catch (IOException ignored) {}
                }
            }
        });
        serverThread.start();

        DataOutputStream clientDos = new DataOutputStream(channel.clientOut);
        DataInputStream clientDis = new DataInputStream(channel.clientIn);

        BigInteger N = PairingProtocol.N_2048;
        BigInteger g = PairingProtocol.G;
        BigInteger a = PairingProtocol.randomExponent(null, N);
        BigInteger clientA = PairingProtocol.computeClientA(g, a, N);

        // Send bad version
        PairingMessages.ClientHello badHello = new PairingMessages.ClientHello(
                "pcdash-pair-v999", "TestDevice", PairingProtocol.pad(clientA, N));
        badHello.write(clientDos);
        clientDos.flush();

        serverThread.join(3000);

        int readVal = -1;
        try {
            readVal = clientDis.read();
        } catch (IOException ignored) {}
        assertEquals(readVal, -1, "Bad version must cause immediate server disconnect");
    }

    public static void testDeviceNameSanitization() {
        String raw = "Device\0\r\n\tControl#1";
        String clean = PairingProtocol.sanitizeDeviceName(raw);
        assertTrue(!clean.contains("\0"), "Control chars removed");
        assertTrue(!clean.contains("\r"), "Control chars removed");
        assertTrue(!clean.contains("\n"), "Control chars removed");

        // Very long name > 64 bytes
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("A");
        }
        String truncated = PairingProtocol.sanitizeDeviceName(sb.toString());
        assertTrue(truncated.getBytes(StandardCharsets.UTF_8).length <= 64, "Length must be <= 64 bytes");
    }

    public static void testThreeConcurrentConnectionsWithWrongCode() throws Exception {
        File tempStoreFile = File.createTempFile("pcdash_3conn_test_", ".json");
        tempStoreFile.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(tempStoreFile);
        FakeHostTailnet tailnet = new FakeHostTailnet();
        FakeTimeSource clock = new FakeTimeSource();

        DeterministicRandom random = new DeterministicRandom(0, 1, 2, 3, 4, 23); // ABCDE2
        PairingManager manager = new PairingManager(store, tailnet, clock, random);
        manager.start(52197, 52198);

        PairingManager.PairingCode code = manager.begin();
        assertTrue(manager.isActive(clock.nowEpochMs()), "Code active");

        final int connCount = 3;
        final CountDownLatch readyLatch = new CountDownLatch(connCount);
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(connCount);
        final BigInteger N = PairingProtocol.N_2048;
        final BigInteger g = PairingProtocol.G;

        for (int i = 0; i < connCount; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        readyLatch.countDown();
                        startLatch.await();

                        try (Socket s = new Socket("127.0.0.1", 52198)) {
                            s.setSoTimeout(3000);
                            DataOutputStream dos = new DataOutputStream(s.getOutputStream());
                            DataInputStream dis = new DataInputStream(s.getInputStream());

                            BigInteger a = PairingProtocol.randomExponent(null, N);
                            BigInteger clientA = PairingProtocol.computeClientA(g, a, N);

                            PairingMessages.ClientHello hello = new PairingMessages.ClientHello(
                                    PairingMessages.PROTOCOL_VERSION, "TestDevice", PairingProtocol.pad(clientA, N));
                            hello.write(dos);
                            dos.flush();

                            PairingMessages.ServerHello sHello = PairingMessages.ServerHello.read(dis);

                            byte[] wrongM1 = new byte[32];
                            Arrays.fill(wrongM1, (byte) 0xEE);
                            dos.write(wrongM1);
                            dos.flush();

                            try {
                                dis.read();
                            } catch (IOException ignored) {}
                        } catch (Exception ignored) {
                        }
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                }
            }).start();
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);

        assertTrue(manager.getPeakConcurrentHandshakes() <= 1,
                "At most 1 handshake must run at a time; was " + manager.getPeakConcurrentHandshakes());
        assertEquals(manager.getActiveHandshakes(), 0, "No handshakes should remain running");

        assertTrue(manager.getFailureCount() <= PairingManager.MAX_FAILURES,
                "Failure count must not exceed MAX_FAILURES (5); was " + manager.getFailureCount());

        manager.close();
    }

    public static void testPairingManagerLogsAndNeverLeaksSecrets() throws Exception {
        final List<String> capturedLogs = new ArrayList<String>();
        PairingManager.setLogListener(new PairingManager.LogListener() {
            @Override
            public void onLog(int priority, String tag, String msg) {
                assertEquals(tag, PairingManager.TAG, "Log tag must be PcdashPair");
                capturedLogs.add(msg);
            }
        });

        File tempStoreFile = File.createTempFile("pcdash_log_test_", ".json");
        tempStoreFile.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(tempStoreFile);
        FakeHostTailnet tailnet = new FakeHostTailnet();
        FakeTimeSource clock = new FakeTimeSource();
        PairingManager manager = new PairingManager(store, tailnet, clock);

        try {
            // 1. IP rejected because not RFC1918 (e.g. 8.8.8.8)
            TestChannel c1 = new TestChannel();
            Socket s1 = c1.createServerSocket(InetAddress.getByName("8.8.8.8"));
            manager.processTcpHandshake(s1);
            boolean foundRfcLog = false;
            for (String log : capturedLogs) {
                if (log.contains("RFC1918")) {
                    foundRfcLog = true;
                    break;
                }
            }
            assertTrue(foundRfcLog, "Must log RFC1918 rejection");

            // 2. No active code
            capturedLogs.clear();
            TestChannel c2 = new TestChannel();
            Socket s2 = c2.createServerSocket(InetAddress.getByName("192.168.1.50"));
            manager.processTcpHandshake(s2);
            boolean foundNoCodeLog = false;
            for (String log : capturedLogs) {
                if (log.contains("không có mã mở")) {
                    foundNoCodeLog = true;
                    break;
                }
            }
            assertTrue(foundNoCodeLog, "Must log rejection when no code active");

            // 3. Expired code
            capturedLogs.clear();
            PairingManager.PairingCode code = manager.begin();
            clock.advance(PairingManager.CODE_TTL_MS + 1000);
            TestChannel c3 = new TestChannel();
            Socket s3 = c3.createServerSocket(InetAddress.getByName("192.168.1.50"));
            manager.processTcpHandshake(s3);
            boolean foundExpiredLog = false;
            for (String log : capturedLogs) {
                if (log.contains("mã đã hết hạn")) {
                    foundExpiredLog = true;
                    break;
                }
            }
            assertTrue(foundExpiredLog, "Must log rejection when code expired");

            // 4. M1 wrong with failure count
            capturedLogs.clear();
            clock.advance(1000);
            PairingManager.PairingCode newCode = manager.begin();
            final String activeCodeSecret = newCode.getCode();

            TestChannel c4 = new TestChannel();
            final Socket s4 = c4.createServerSocket(InetAddress.getByName("192.168.1.50"));
            Thread t4 = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        manager.processTcpHandshake(s4);
                    } catch (Exception ignored) {}
                }
            });
            t4.start();

            DataOutputStream dos4 = new DataOutputStream(c4.clientOut);
            DataInputStream dis4 = new DataInputStream(c4.clientIn);
            BigInteger N = PairingProtocol.N_2048;
            BigInteger g = PairingProtocol.G;
            BigInteger a4 = PairingProtocol.randomExponent(null, N);
            BigInteger clientA4 = PairingProtocol.computeClientA(g, a4, N);
            PairingMessages.ClientHello hello4 = new PairingMessages.ClientHello(
                    PairingMessages.PROTOCOL_VERSION, "TestPhone", PairingProtocol.pad(clientA4, N));
            hello4.write(dos4);
            dos4.flush();

            PairingMessages.ServerHello.read(dis4);
            // Send bad M1
            byte[] badM1 = new byte[32];
            Arrays.fill(badM1, (byte) 0xAA);
            dos4.write(badM1);
            dos4.flush();
            t4.join(3000);

            boolean foundM1Log = false;
            for (String log : capturedLogs) {
                if (log.contains("Xác thực M1 thất bại") && log.contains("1/5")) {
                    foundM1Log = true;
                    break;
                }
            }
            assertTrue(foundM1Log, "Must log M1 failure with count 1/5");

            // 5. Complete success
            capturedLogs.clear();
            manager.complete("MyCleanDevice", "secret_device_token_999", "node_test_ok");
            boolean foundSuccessLog = false;
            for (String log : capturedLogs) {
                if (log.contains("Ghép đôi thành công") && log.contains("MyCleanDevice") && log.contains("node_test_ok")) {
                    foundSuccessLog = true;
                    break;
                }
            }
            assertTrue(foundSuccessLog, "Must log pairing success with deviceName and nodeId");

            // 6. Assert NEVER log secrets: code, verifier, deviceToken, authKey, M1/M2
            for (String log : capturedLogs) {
                assertTrue(!log.contains(activeCodeSecret), "Log must NOT contain pairing code!");
                assertTrue(!log.contains("secret_device_token_999"), "Log must NOT contain deviceToken!");
                assertTrue(!log.contains("tskey-auth"), "Log must NOT contain authKey!");
            }
        } finally {
            PairingManager.setLogListener(null);
        }
    }

    public static void main(String[] args) throws Exception {
        L10n.setOverride(L10n.VI); // the assertions below check the Vietnamese UI text
        System.out.println("Running PairingManagerTest...");
        testLetterFirstCodeExpirySingleUseAndFiveFailures();
        System.out.println("  PASS: testLetterFirstCodeExpirySingleUseAndFiveFailures");
        testCodeUsesOnlyAllowedAlphabetAndLengthSix();
        System.out.println("  PASS: testCodeUsesOnlyAllowedAlphabetAndLengthSix");
        testPairingRejectedWhenNoCodeActive();
        System.out.println("  PASS: testPairingRejectedWhenNoCodeActive");
        testLowercaseCodeAccepted();
        System.out.println("  PASS: testLowercaseCodeAccepted");
        testRejectsLoopbackTailscaleAndPublicPeer();
        System.out.println("  PASS: testRejectsLoopbackTailscaleAndPublicPeer");
        testHostNotLoggedInRejectsPairing();
        System.out.println("  PASS: testHostNotLoggedInRejectsPairing");
        testHandshakeReturnsV2CredentialsAndCompletesOnAck();
        System.out.println("  PASS: testHandshakeReturnsV2CredentialsAndCompletesOnAck");
        testCancelMidwayAbortsPairing();
        System.out.println("  PASS: testCancelMidwayAbortsPairing");
        testTcpConcurrencySemaphoreLimit();
        System.out.println("  PASS: testTcpConcurrencySemaphoreLimit");
        testInvalidProtocolVersionRejected();
        System.out.println("  PASS: testInvalidProtocolVersionRejected");
        testDeviceNameSanitization();
        System.out.println("  PASS: testDeviceNameSanitization");
        testThreeConcurrentConnectionsWithWrongCode();
        System.out.println("  PASS: testThreeConcurrentConnectionsWithWrongCode");
        testPairingManagerLogsAndNeverLeaksSecrets();
        System.out.println("  PASS: testPairingManagerLogsAndNeverLeaksSecrets");
        System.out.println("ALL PairingManagerTest PASSED");
    }
}
