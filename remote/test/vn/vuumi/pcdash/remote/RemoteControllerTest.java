package vn.vuumi.pcdash.remote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import vn.vuumi.pcdash.pairing.PairingMessages;
import vn.vuumi.pcdash.pairing.PairingProtocol;
import vn.vuumi.pcdash.net.PcnetBridge;
import vn.vuumi.pcdash.net.PcnetState;

public class RemoteControllerTest {

    private static class FakePcnetBridge extends PcnetBridge {
        final AtomicBoolean running = new AtomicBoolean(false);
        final AtomicReference<String> startedHost = new AtomicReference<String>(null);
        final AtomicReference<String> startedHostname = new AtomicReference<String>(null);
        int startedPort = 8091;
        int localPort = 18091;
        int startCount = 0;
        String assignedNodeId = "node_test_123";
        String lastErrorString = null;
        boolean beginResult = true;

        @Override
        public boolean isNativeAvailable() {
            return true;
        }

        @Override
        public String lastError() {
            return lastErrorString;
        }

        @Override
        public boolean begin(File stateDir, String hostname, int mode, String host, int hostPort, int localPort) {
            startCount++;
            if (this.localPort <= 0 && !beginResult) {
                return false;
            }
            running.set(true);
            startedHost.set(host);
            startedPort = hostPort;
            startedHostname.set(hostname);
            setBoundPort(this.localPort > 0 ? this.localPort : 18091);
            return beginResult;
        }

        @Override
        public PcnetState state() {
            if (lastErrorString != null && lastErrorString.contains("NEEDS_LOGIN")) {
                return new PcnetState("needs_login", "https://login.tailscale.com/a/test", java.util.Collections.<String>emptyList(), 0, lastErrorString);
            }
            if (running.get()) {
                return new PcnetState("running", "", java.util.Collections.singletonList("100.64.0.1"), localPort > 0 ? localPort : 18091, "");
            }
            return new PcnetState("stopped", "", java.util.Collections.<String>emptyList(), 0, "");
        }
        @Override
        public void stop() {
            running.set(false);
            setBoundPort(-1);
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }

        @Override
        public String nodeId() {
            return assignedNodeId;
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Running RemoteControllerTest...");
        testCodeInputUppercasesAndFiltersInvalidChars();
        testApiClientUsesLoopbackBearerAndWhitelist();
        testStopAndForgetCloseTsnetAndErasePrivateState();
        testPairAcceptsAlphanumericCodeAndPersistsNoAuthKey();
        testNeedsLoginWhenPairedRetainsTokenAndDoesNotForgetHost();
        testOpenIsIdempotentAndRetainsRealPort();
        testUiThreadCallsNeverBlockWhileNetworkCallInFlight();
        testPcnetBridgeThrowsWhenNativeUnavailable();
        testRemoteConfigStoreAtomicReplacePreservesOldConfigOnFailure();
        testRemoteConfigStoreHostnamePersistence();
        testStageConnect_Refused();
        testStageHello_ServerCloses();
        testStageProofRejected_EofAfterM1();
        testStageServerProofInvalid_BadM2();
        testStageCredentials_Corrupted();
        testStageCredentials_LegacyWithAuthKeyRejected();
        testStageProtocolVersionOne_Rejected();
        testStageAck_Failure();
        testStageAck_HostReturnsErrorCode();
        testNoSecretsInLogsOrMessages();
        System.out.println("ALL RemoteControllerTest assertions PASSED!");
    }

    public static void testCodeInputUppercasesAndFiltersInvalidChars() {
        String input = "abcde2!0ol";
        String normalized = RemoteController.normalizeCode(input);
        if (!"ABCDE2".equals(normalized)) {
            throw new AssertionError("Expected ABCDE2 but got: " + normalized);
        }

        String empty = RemoteController.normalizeCode(null);
        if (!"".equals(empty)) {
            throw new AssertionError("Expected empty string for null, got: " + empty);
        }

        String padded = RemoteController.normalizeCode("  23456789  ");
        if (!"234567".equals(padded)) {
            throw new AssertionError("Expected 234567 (max 6 chars), got: " + padded);
        }
        System.out.println("PASS: testCodeInputUppercasesAndFiltersInvalidChars");
    }

    public static void testApiClientUsesLoopbackBearerAndWhitelist() throws Exception {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        int port = server.getLocalPort();

        final AtomicReference<String> capturedMethod = new AtomicReference<String>();
        final AtomicReference<String> capturedPath = new AtomicReference<String>();
        final AtomicReference<String> capturedAuth = new AtomicReference<String>();
        final AtomicReference<String> capturedHostHeader = new AtomicReference<String>();

        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < 2; i++) {
                        Socket s = server.accept();
                        InputStream in = s.getInputStream();
                        OutputStream out = s.getOutputStream();

                        byte[] buf = new byte[2048];
                        int read = in.read(buf);
                        String req = new String(buf, 0, read, StandardCharsets.UTF_8);
                        String[] lines = req.split("\r\n");
                        String[] reqLine = lines[0].split(" ");
                        capturedMethod.set(reqLine[0]);
                        capturedPath.set(reqLine[1]);

                        for (String l : lines) {
                            if (l.toLowerCase().startsWith("authorization:")) {
                                capturedAuth.set(l.substring("authorization:".length()).trim());
                            }
                            if (l.toLowerCase().startsWith("host:")) {
                                capturedHostHeader.set(l.substring("host:".length()).trim());
                            }
                        }

                        if ("/v1/status".equals(reqLine[1])) {
                            String body = "{\"online\":true,\"port\":8799,\"rtt_ms\":10}";
                            String resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
                            out.write(resp.getBytes(StandardCharsets.UTF_8));
                        } else if ("/v1/wake".equals(reqLine[1])) {
                            String body = "{\"ok\":true,\"action\":\"wake\",\"detail\":\"sent WOL\"}";
                            String resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
                            out.write(resp.getBytes(StandardCharsets.UTF_8));
                        }
                        out.flush();
                        s.close();
                    }
                } catch (Exception ignored) {}
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        RemoteApiClient client = new RemoteApiClient();

        // 1. Test getStatus
        RemoteApiClient.RemoteStatus status = client.getStatus(port, "test_token_abc");
        if (!status.online || status.port == null || status.port != 8799) {
            throw new AssertionError("getStatus failed to parse response: " + status.rawJson);
        }
        if (!"GET".equals(capturedMethod.get()) || !"/v1/status".equals(capturedPath.get())) {
            throw new AssertionError("Expected GET /v1/status, got: " + capturedMethod.get() + " " + capturedPath.get());
        }
        if (!"Bearer test_token_abc".equals(capturedAuth.get())) {
            throw new AssertionError("Expected Bearer test_token_abc, got: " + capturedAuth.get());
        }

        // 2. Test postAction valid whitelist
        RemoteApiClient.RemoteCommandResult res = client.postAction(port, "test_token_abc", "wake");
        if (!res.ok || !"wake".equals(res.action)) {
            throw new AssertionError("postAction failed: " + res.rawJson);
        }
        if (!"POST".equals(capturedMethod.get()) || !"/v1/wake".equals(capturedPath.get())) {
            throw new AssertionError("Expected POST /v1/wake, got: " + capturedMethod.get() + " " + capturedPath.get());
        }

        // 3. Test non-whitelist actions are rejected locally
        String[] invalidActions = {"rm_rf", "exec", "reboot", "ping", "status", "", null};
        for (String act : invalidActions) {
            try {
                client.postAction(port, "token", act);
                throw new AssertionError("Expected IllegalArgumentException for invalid action: " + act);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }

        server.close();
        System.out.println("PASS: testApiClientUsesLoopbackBearerAndWhitelist");
    }

    public static void testStopAndForgetCloseTsnetAndErasePrivateState() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "pcdash_remote_test_" + System.currentTimeMillis());
        tempDir.mkdirs();
        File configFile = new File(tempDir, "remote_config.json");
        File stateDir = new File(tempDir, "tsnet_state");
        stateDir.mkdirs();
        File dummyStateFile = new File(stateDir, "tailscale.state");
        dummyStateFile.createNewFile();

        FakePcnetBridge bridge = new FakePcnetBridge();
        RemoteConfigStore store = new RemoteConfigStore(configFile);
        store.save(new RemoteConfigStore.RemoteConfig("token_123", "100.64.0.5", 8091));

        RemoteApiClient api = new RemoteApiClient();
        PairingClient pairing = new PairingClient();

        RemoteController controller = new RemoteController(pairing, bridge, store, api, stateDir);

        // open() boots tsnet bridge
        controller.open();
        if (!bridge.isRunning()) {
            throw new AssertionError("Expected bridge to be running after open()");
        }

        // close() stops tsnet bridge
        controller.close();
        if (bridge.isRunning()) {
            throw new AssertionError("Expected bridge to be stopped after close()");
        }

        // reopen, then forgetHost()
        controller.open();
        if (!bridge.isRunning()) {
            throw new AssertionError("Expected bridge to be running after open()");
        }

        controller.forgetHost();
        if (bridge.isRunning()) {
            throw new AssertionError("Expected bridge to be stopped after forgetHost()");
        }
        if (configFile.exists()) {
            throw new AssertionError("Expected configFile to be deleted after forgetHost()");
        }
        if (store.load() != null) {
            throw new AssertionError("Expected store.load() to return null after forgetHost()");
        }
        if (dummyStateFile.exists()) {
            throw new AssertionError("Expected tsnet state files to be erased after forgetHost()");
        }

        // cleanup temp dir
        tempDir.delete();
        System.out.println("PASS: testStopAndForgetCloseTsnetAndErasePrivateState");
    }

    public static void testPairAcceptsAlphanumericCodeAndPersistsNoAuthKey() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "pcdash_remote_pair_test_" + System.currentTimeMillis());
        tempDir.mkdirs();
        File configFile = new File(tempDir, "remote_config.json");
        File stateDir = new File(tempDir, "tsnet_state");
        stateDir.mkdirs();

        final String pairCode = "ABCDE2";
        final ServerSocket mockHostServer = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final int hostPort = mockHostServer.getLocalPort();

        final BigInteger N = PairingProtocol.N_2048;
        final BigInteger g = PairingProtocol.G;
        final String hash = PairingProtocol.HASH_SHA256;
        final byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);

        String normalizedCode = PairingProtocol.normalizeCode(pairCode);
        BigInteger x = PairingProtocol.computeX(hash, PairingProtocol.PAIRING_IDENTITY, normalizedCode, salt);
        BigInteger v = PairingProtocol.computeV(g, x, N);

        final AtomicReference<String> receivedNodeIdInAck = new AtomicReference<String>();
        final AtomicBoolean serverPairSuccess = new AtomicBoolean(false);

        Thread hostThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket socket = mockHostServer.accept();
                    DataInputStream dis = new DataInputStream(socket.getInputStream());
                    DataOutputStream dos = new DataOutputStream(socket.getOutputStream());

                    // 1. Read ClientHello
                    PairingMessages.ClientHello clientHello = PairingMessages.ClientHello.read(dis);
                    BigInteger clientA = PairingProtocol.fromBytes(clientHello.clientA);

                    // 2. Send ServerHello
                    BigInteger k = PairingProtocol.computeK(hash, N, g);
                    BigInteger b = PairingProtocol.randomExponent(new SecureRandom(), N);
                    BigInteger serverB = PairingProtocol.computeServerB(k, v, g, b, N);
                    PairingMessages.ServerHello serverHello = new PairingMessages.ServerHello(
                            PairingMessages.PROTOCOL_VERSION,
                            salt,
                            PairingProtocol.pad(serverB, N)
                    );
                    serverHello.write(dos);
                    dos.flush();

                    // 3. Compute server S & key
                    BigInteger u = PairingProtocol.computeU(hash, N, clientA, serverB);
                    BigInteger serverS = PairingProtocol.computeServerS(clientA, v, u, b, N);
                    byte[] sessionKeyK = PairingProtocol.deriveSessionKeyK(hash, serverS, N);
                    byte[] expectedM1 = PairingProtocol.computeM1(hash, N, g, PairingProtocol.PAIRING_IDENTITY, salt, clientA, serverB, sessionKeyK);

                    // 4. Read client M1
                    byte[] clientM1 = new byte[32];
                    dis.readFully(clientM1);
                    if (!MessageDigest.isEqual(clientM1, expectedM1)) {
                        socket.close();
                        return;
                    }

                    // 5. Send M2 + Creds
                    byte[] transcript = PairingMessages.computeTranscript(
                            PairingMessages.PROTOCOL_VERSION,
                            clientHello.deviceName,
                            PairingProtocol.pad(clientA, N),
                            PairingProtocol.pad(serverB, N),
                            salt
                    );
                    byte[] aesKey = PairingProtocol.deriveAesKey(sessionKeyK);
                    byte[] m2 = PairingProtocol.computeM2(hash, N, clientA, expectedM1, sessionKeyK);

                    String credsJson = "{\n"
                            + "  \"deviceToken\": \"device_token_secret_xyz\",\n"
                            + "  \"hostTailnetIp\": \"100.64.0.99\",\n"
                            + "  \"hostPort\": 8091\n"
                            + "}";
                    PairingMessages.EncryptedRecord credsRecord = PairingProtocol.seal(
                            aesKey,
                            transcript,
                            credsJson.getBytes(StandardCharsets.UTF_8)
                    );
                    dos.write(m2);
                    credsRecord.write(dos);
                    dos.flush();

                    // 6. Read ACK record
                    PairingMessages.EncryptedRecord ackRecord = PairingMessages.EncryptedRecord.read(dis);
                    byte[] ackPlaintext = PairingProtocol.open(aesKey, transcript, ackRecord);
                    String ackJson = new String(ackPlaintext, StandardCharsets.UTF_8);
                    int idx = ackJson.indexOf("\"nodeId\"");
                    if (idx >= 0) {
                        int colon = ackJson.indexOf(':', idx);
                        int q1 = ackJson.indexOf('"', colon);
                        int q2 = ackJson.indexOf('"', q1 + 1);
                        receivedNodeIdInAck.set(ackJson.substring(q1 + 1, q2));
                        serverPairSuccess.set(true);
                    }
                    // Send final result record
                    PairingMessages.EncryptedRecord resultRecord = PairingProtocol.seal(
                            aesKey,
                            transcript,
                            "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8)
                    );
                    resultRecord.write(dos);
                    dos.flush();
                    socket.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        hostThread.setDaemon(true);
        hostThread.start();

        FakePcnetBridge bridge = new FakePcnetBridge();
        bridge.assignedNodeId = "node_pcdash_verified_777";

        RemoteConfigStore store = new RemoteConfigStore(configFile);
        RemoteApiClient api = new RemoteApiClient();
        PairingClient pairing = new PairingClient(hostPort); // connect to mockHostServer

        RemoteController controller = new RemoteController(pairing, bridge, store, api, stateDir);
        controller.pair(mockHostServer.getInetAddress(), pairCode, "Test Phone");

        if (!serverPairSuccess.get()) {
            throw new AssertionError("Server side pairing failed or ACK not received");
        }
        if (!"node_pcdash_verified_777".equals(receivedNodeIdInAck.get())) {
            throw new AssertionError("Expected host to receive nodeId node_pcdash_verified_777, got: " + receivedNodeIdInAck.get());
        }

        // Check persistent store has NO authKey, only token and hostTailnetIp
        RemoteConfigStore.RemoteConfig saved = store.load();
        if (saved == null) {
            throw new AssertionError("Expected store.load() to return saved config");
        }
        if (!"device_token_secret_xyz".equals(saved.deviceToken)) {
            throw new AssertionError("Device token mismatch");
        }
        if (!"100.64.0.99".equals(saved.hostTailnetIp)) {
            throw new AssertionError("Host tailnet IP mismatch");
        }
        if (saved.hostPort != 8091) {
            throw new AssertionError("Host port mismatch");
        }

        // Verify config file on disk doesn't have authKey
        String diskContent = new String(java.nio.file.Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
        if (diskContent.contains("authKey") || diskContent.contains("tskey-auth-mock-single-use-123")) {
            throw new AssertionError("Persistent config file MUST NOT contain authKey!");
        }

        mockHostServer.close();
        tempDir.delete();
        System.out.println("PASS: testPairAcceptsAlphanumericCodeAndPersistsNoAuthKey");
    }

    public static void testHttp401ThrowsDeviceRevokedException() throws Exception {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final int port = server.getLocalPort();

        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (!server.isClosed()) {
                        Socket s = server.accept();
                        InputStream in = s.getInputStream();
                        OutputStream out = s.getOutputStream();
                        byte[] buf = new byte[1024];
                        int read = in.read(buf);
                        String body = "{\"error\":\"device revoked\"}";
                        String resp = "HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nContent-Length: "
                                + body.length() + "\r\n\r\n" + body;
                        out.write(resp.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        s.close();
                    }
                } catch (Exception ignored) {}
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        RemoteApiClient client = new RemoteApiClient();

        // 1. Calling getStatus() against 401 server must throw DeviceRevokedException
        boolean threwRevokedOnStatus = false;
        try {
            client.getStatus(port, "revoked_token");
        } catch (RemoteApiClient.DeviceRevokedException e) {
            threwRevokedOnStatus = true;
        }
        if (!threwRevokedOnStatus) {
            throw new AssertionError("Expected DeviceRevokedException on HTTP 401 getStatus, but none was thrown");
        }

        // 2. Calling postAction() against 401 server must throw DeviceRevokedException
        boolean threwRevokedOnAction = false;
        try {
            client.postAction(port, "revoked_token", "wake");
        } catch (RemoteApiClient.DeviceRevokedException e) {
            threwRevokedOnAction = true;
        }
        if (!threwRevokedOnAction) {
            throw new AssertionError("Expected DeviceRevokedException on HTTP 401 postAction, but none was thrown");
        }

        server.close();

        // 3. Controller integration: when DeviceRevokedException occurs, forgetHost() resets paired state
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "pcdash_test_401_" + System.currentTimeMillis());
        tempDir.mkdirs();
        File configFile = new File(tempDir, "config.json");
        File stateDir = new File(tempDir, "tsnet");
        RemoteConfigStore store = new RemoteConfigStore(configFile);
        store.save(new RemoteConfigStore.RemoteConfig("tok_401", "100.64.0.1", 8091));
        FakePcnetBridge bridge = new FakePcnetBridge();
        bridge.localPort = port;
        RemoteController controller = new RemoteController(new PairingClient(), bridge, store, client, stateDir);
        controller.open();

        if (!controller.isPaired()) {
            throw new AssertionError("Controller should be paired initially");
        }

        // Simulate 401 handling in controller consumer: call forgetHost()
        try {
            controller.forgetHost();
        } catch (Exception e) {
            throw new AssertionError("forgetHost should not throw: " + e.getMessage());
        }

        if (controller.isPaired()) {
            throw new AssertionError("Controller must NOT be paired after forgetHost");
        }
        if (configFile.exists()) {
            throw new AssertionError("configFile must be deleted after forgetHost");
        }
        if (controller.getBoundLocalPort() != -1) {
            throw new AssertionError("boundLocalPort must be -1 after forgetHost");
        }

        tempDir.delete();
        System.out.println("PASS: testHttp401ThrowsDeviceRevokedException");
    }

    public static void testNeedsLoginWhenPairedRetainsTokenAndDoesNotForgetHost() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "pcdash_test_needs_login_" + System.currentTimeMillis());
        tempDir.mkdirs();
        File configFile = new File(tempDir, "config.json");
        File stateDir = new File(tempDir, "tsnet_state");
        stateDir.mkdirs();

        RemoteConfigStore store = new RemoteConfigStore(configFile);
        store.save(new RemoteConfigStore.RemoteConfig("tok_retained", "100.64.0.99", 8091));

        final FakePcnetBridge bridge = new FakePcnetBridge();
        bridge.lastErrorString = "NEEDS_LOGIN: backend in state NeedsLogin";
        RemoteController controller = new RemoteController(new PairingClient(), bridge, store, new RemoteApiClient(), stateDir);

        // 1. Calling open() on paired controller with needs_login must NOT throw DeviceRevokedException
        boolean threwRevokedOnOpen = false;
        try {
            controller.open();
        } catch (RemoteApiClient.DeviceRevokedException e) {
            threwRevokedOnOpen = true;
        } catch (IOException ignored) {}

        if (threwRevokedOnOpen) {
            throw new AssertionError("needs_login MUST NOT throw DeviceRevokedException");
        }

        // 2. Controller MUST still be paired!
        if (!controller.isPaired()) {
            throw new AssertionError("Controller must remain paired when state is needs_login");
        }

        // 3. Token in store MUST be retained (NOT deleted)
        RemoteConfigStore.RemoteConfig loaded = store.load();
        if (loaded == null || !"tok_retained".equals(loaded.deviceToken)) {
            throw new AssertionError("Device token must be retained in store when needs_login, got: " + loaded);
        }

        // 4. State must report isNeedsLogin()
        PcnetState st = controller.state();
        if (st == null || !st.isNeedsLogin()) {
            throw new AssertionError("controller.state() must report isNeedsLogin, got: " + st);
        }

        // Cleanup
        stateDir.delete();
        configFile.delete();
        tempDir.delete();
        System.out.println("PASS: testNeedsLoginWhenPairedRetainsTokenAndDoesNotForgetHost");
    }

    public static void testOpenIsIdempotentAndRetainsRealPort() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "pcdash_test_open_" + System.currentTimeMillis());
        tempDir.mkdirs();
        File configFile = new File(tempDir, "config.json");
        File stateDir = new File(tempDir, "tsnet");
        RemoteConfigStore store = new RemoteConfigStore(configFile);
        store.save(new RemoteConfigStore.RemoteConfig("tok_test", "100.64.0.1", 8091));

        FakePcnetBridge bridge = new FakePcnetBridge();
        bridge.localPort = 24680; // Non-default, non-18091 port

        RemoteController controller = new RemoteController(new PairingClient(), bridge, store, new RemoteApiClient(), stateDir);

        // First open: must start bridge and record real port 24680
        controller.open();
        if (bridge.startCount != 1) {
            throw new AssertionError("Expected startCount 1, got " + bridge.startCount);
        }
        if (controller.getBoundLocalPort() != 24680) {
            throw new AssertionError("Expected bound local port 24680, got " + controller.getBoundLocalPort());
        }

        // Simulate screen rotation or redundant onStart: change bridge.localPort to 99999
        bridge.localPort = 99999;
        controller.open();

        // Idempotent: must NOT start again, must NOT overwrite boundLocalPort
        if (bridge.startCount != 1) {
            throw new AssertionError("Expected startCount still 1 after second open(), got " + bridge.startCount);
        }
        if (controller.getBoundLocalPort() != 24680) {
            throw new AssertionError("boundLocalPort must not be overwritten! Expected 24680, got " + controller.getBoundLocalPort());
        }

        controller.close();
        if (controller.getBoundLocalPort() != -1) {
            throw new AssertionError("Expected bound local port -1 after close");
        }

        tempDir.delete();
        System.out.println("PASS: testOpenIsIdempotentAndRetainsRealPort");
    }

    public static void testUiThreadCallsNeverBlockWhileNetworkCallInFlight() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "pcdash_test_nolock_" + System.currentTimeMillis());
        tempDir.mkdirs();
        RemoteConfigStore store = new RemoteConfigStore(new File(tempDir, "config.json"));
        store.save(new RemoteConfigStore.RemoteConfig("tok_test", "100.64.0.1", 8091));

        FakePcnetBridge bridge = new FakePcnetBridge();
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        RemoteApiClient slowApi = new RemoteApiClient() {
            @Override
            public String getMetrics(int localPort, String token) throws IOException {
                entered.countDown();
                try {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}
                return "{}";
            }
        };
        final RemoteController controller = new RemoteController(new PairingClient(), bridge, store, slowApi, new File(tempDir, "tsnet"));
        controller.open();

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    controller.getMetrics();
                } catch (IOException ignored) {}
            }
        });
        worker.start();
        if (!entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new AssertionError("network call never started");
        }

        final AtomicBoolean uiCallsDone = new AtomicBoolean(false);
        Thread ui = new Thread(new Runnable() {
            @Override
            public void run() {
                controller.isPaired();
                controller.getBoundLocalPort();
                uiCallsDone.set(true);
            }
        });
        ui.start();
        ui.join(1000);
        boolean blocked = !uiCallsDone.get();
        release.countDown();
        worker.join(5000);
        if (blocked) {
            throw new AssertionError("isPaired()/getBoundLocalPort() blocked while a network call was in flight");
        }
        controller.close();
        System.out.println("PASS: testUiThreadCallsNeverBlockWhileNetworkCallInFlight");
    }

    public static void testPcnetBridgeThrowsWhenNativeUnavailable() {
        PcnetBridge bridge = new PcnetBridge();
        if (bridge.isNativeAvailable()) {
            throw new AssertionError("In JVM unit tests, nativeAvailable should be false");
        }

        String expectedMsg = "Thiếu thư viện native (ABI không hỗ trợ?)";

        // Test start
        try {
            bridge.start(null, "", "", 0);
            throw new AssertionError("start() should have thrown IllegalStateException");
        } catch (PcnetBridge.DeviceRevokedException e) {
            throw new AssertionError("start() should have thrown IllegalStateException, got: " + e);
        } catch (IllegalStateException e) {
            if (!expectedMsg.equals(e.getMessage())) {
                throw new AssertionError("Expected message '" + expectedMsg + "', got: " + e.getMessage());
            }
        }

        // Test begin
        try {
            bridge.begin(null, "", 0, "", 0, 0);
            throw new AssertionError("begin() should have thrown IllegalStateException");
        } catch (IllegalStateException e) {
            if (!expectedMsg.equals(e.getMessage())) {
                throw new AssertionError("Expected message '" + expectedMsg + "', got: " + e.getMessage());
            }
        }

        // Test stop
        try {
            bridge.stop();
            throw new AssertionError("stop() should have thrown IllegalStateException");
        } catch (IllegalStateException e) {
            if (!expectedMsg.equals(e.getMessage())) {
                throw new AssertionError("Expected message '" + expectedMsg + "', got: " + e.getMessage());
            }
        }

        // Test isRunning
        try {
            bridge.isRunning();
            throw new AssertionError("isRunning() should have thrown IllegalStateException");
        } catch (IllegalStateException e) {
            if (!expectedMsg.equals(e.getMessage())) {
                throw new AssertionError("Expected message '" + expectedMsg + "', got: " + e.getMessage());
            }
        }

        // Test nodeId
        try {
            bridge.nodeId();
            throw new AssertionError("nodeId() should have thrown IllegalStateException");
        } catch (IllegalStateException e) {
            if (!expectedMsg.equals(e.getMessage())) {
                throw new AssertionError("Expected message '" + expectedMsg + "', got: " + e.getMessage());
            }
        }

        // Test lastError
        try {
            bridge.lastError();
            throw new AssertionError("lastError() should have thrown IllegalStateException");
        } catch (IllegalStateException e) {
            if (!expectedMsg.equals(e.getMessage())) {
                throw new AssertionError("Expected message '" + expectedMsg + "', got: " + e.getMessage());
            }
        }

        System.out.println("PASS: testPcnetBridgeThrowsWhenNativeUnavailable");
    }

    public static void testRemoteConfigStoreAtomicReplacePreservesOldConfigOnFailure() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "pcdash_test_store_" + System.currentTimeMillis());
        tempDir.mkdirs();
        File configFile = new File(tempDir, "config.json");

        // 1. Initial valid save
        RemoteConfigStore normalStore = new RemoteConfigStore(configFile);
        normalStore.save(new RemoteConfigStore.RemoteConfig("tok_orig", "100.64.0.1", 8091));

        RemoteConfigStore.RemoteConfig loaded = normalStore.load();
        if (loaded == null || !"tok_orig".equals(loaded.deviceToken) || !"100.64.0.1".equals(loaded.hostTailnetIp)) {
            throw new AssertionError("Failed to load initial config");
        }

        // 2. Failure during writeSync (e.g. disk write failure before rename)
        RemoteConfigStore failingWriteStore = new RemoteConfigStore(configFile, new RemoteConfigStore.AtomicFileWriter() {
            @Override
            public void writeSync(File tempFile, byte[] data) throws IOException {
                throw new IOException("Simulated disk write failure");
            }

            @Override
            public void atomicReplace(File tempFile, File destFile) throws IOException {
                RemoteConfigStore.defaultAtomicReplace(tempFile, destFile);
            }
        });

        boolean threwWrite = false;
        try {
            failingWriteStore.save(new RemoteConfigStore.RemoteConfig("tok_corrupt", "100.64.0.2", 8092));
        } catch (IOException e) {
            threwWrite = true;
        }
        if (!threwWrite) {
            throw new AssertionError("Expected IOException on writeSync failure");
        }

        // Verify old config was NOT corrupted or lost
        RemoteConfigStore.RemoteConfig afterWriteFail = normalStore.load();
        if (afterWriteFail == null || !"tok_orig".equals(afterWriteFail.deviceToken)) {
            throw new AssertionError("Old config was lost or corrupted after writeSync failure!");
        }

        // 3. Failure during atomicReplace (e.g. rename failure)
        RemoteConfigStore failingReplaceStore = new RemoteConfigStore(configFile, new RemoteConfigStore.AtomicFileWriter() {
            @Override
            public void writeSync(File tempFile, byte[] data) throws IOException {
                RemoteConfigStore.DEFAULT_WRITER.writeSync(tempFile, data);
            }

            @Override
            public void atomicReplace(File tempFile, File destFile) throws IOException {
                throw new IOException("Simulated atomic replace failure");
            }
        });

        boolean threwReplace = false;
        try {
            failingReplaceStore.save(new RemoteConfigStore.RemoteConfig("tok_corrupt2", "100.64.0.3", 8093));
        } catch (IOException e) {
            threwReplace = true;
        }
        if (!threwReplace) {
            throw new AssertionError("Expected IOException on atomicReplace failure");
        }

        // Verify old config is STILL intact
        RemoteConfigStore.RemoteConfig afterReplaceFail = normalStore.load();
        if (afterReplaceFail == null || !"tok_orig".equals(afterReplaceFail.deviceToken)) {
            throw new AssertionError("Old config was lost or corrupted after atomicReplace failure!");
        }

        // 4. Successful update
        normalStore.save(new RemoteConfigStore.RemoteConfig("tok_updated", "100.64.0.4", 8094));
        RemoteConfigStore.RemoteConfig afterSuccess = normalStore.load();
        if (afterSuccess == null || !"tok_updated".equals(afterSuccess.deviceToken)) {
            throw new AssertionError("Failed to load updated config");
        }

        normalStore.clear();
        if (configFile.exists()) {
            throw new AssertionError("Config file should be deleted after clear");
        }

        tempDir.delete();
        System.out.println("PASS: testRemoteConfigStoreAtomicReplacePreservesOldConfigOnFailure");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void assertEquals(Object actual, Object expected, String msg) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Assertion failed for " + msg + ": expected <" + expected + "> but got <" + actual + ">");
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    public static void testStageConnect_Refused() throws Exception {
        ServerSocket temp = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        int port = temp.getLocalPort();
        temp.close(); // Ensure port is closed and connection is refused

        File tempDir = File.createTempFile("pcdash_c1_", ".dir");
        tempDir.delete();
        tempDir.mkdirs();
        try {
            PairingClient client = new PairingClient(port);
            client.pair(InetAddress.getByName("127.0.0.1"), "ABC234", "Phone", tempDir, new FakePcnetBridge());
            throw new AssertionError("Expected PairingException(CONNECT)");
        } catch (PairingClient.PairingException e) {
            assertEquals(e.getStage(), PairingClient.Stage.CONNECT, "Stage must be CONNECT");
            assertTrue(e.getMessage().contains("Không kết nối được tới host"), "Message should mention host connect error");
            assertTrue(e.getMessage().contains("từ chối"), "Message should differentiate connection refused");
        } finally {
            deleteDir(tempDir);
        }
        System.out.println("PASS: testStageConnect_Refused");
    }

    public static void testStageHello_ServerCloses() throws Exception {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = server.accept();
                    s.close();
                } catch (Exception ignored) {}
            }
        }).start();

        File tempDir = File.createTempFile("pcdash_h1_", ".dir");
        tempDir.delete();
        tempDir.mkdirs();
        try {
            PairingClient client = new PairingClient(server.getLocalPort());
            client.pair(InetAddress.getByName("127.0.0.1"), "ABC234", "Phone", tempDir, new FakePcnetBridge());
            throw new AssertionError("Expected PairingException(HELLO)");
        } catch (PairingClient.PairingException e) {
            assertEquals(e.getStage(), PairingClient.Stage.HELLO, "Stage must be HELLO");
            assertTrue(e.getMessage().contains("Host trả lời không hợp lệ"), "Message should mention Host trả lời không hợp lệ");
        } finally {
            server.close();
            deleteDir(tempDir);
        }
        System.out.println("PASS: testStageHello_ServerCloses");
    }

    public static void testStageProofRejected_EofAfterM1() throws Exception {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final BigInteger N = PairingProtocol.N_2048;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = server.accept();
                    DataInputStream dis = new DataInputStream(s.getInputStream());
                    DataOutputStream dos = new DataOutputStream(s.getOutputStream());
                    PairingMessages.ClientHello hello = PairingMessages.ClientHello.read(dis);
                    BigInteger b = PairingProtocol.randomExponent(new SecureRandom(), N);
                    BigInteger serverB = PairingProtocol.computeServerB(BigInteger.valueOf(3), BigInteger.valueOf(2), BigInteger.valueOf(2), b, N);
                    byte[] salt = new byte[16];
                    new PairingMessages.ServerHello(PairingMessages.PROTOCOL_VERSION, salt, PairingProtocol.pad(serverB, N)).write(dos);
                    dos.flush();
                    byte[] clientM1 = new byte[32];
                    dis.readFully(clientM1);
                    // Disconnect after M1 (as host does on wrong/expired code / no code)
                    s.close();
                } catch (Exception ignored) {}
            }
        }).start();

        File tempDir = File.createTempFile("pcdash_pr_", ".dir");
        tempDir.delete();
        tempDir.mkdirs();
        try {
            PairingClient client = new PairingClient(server.getLocalPort());
            client.pair(InetAddress.getByName("127.0.0.1"), "ABC234", "Phone", tempDir, new FakePcnetBridge());
            throw new AssertionError("Expected PairingException(PROOF_REJECTED)");
        } catch (PairingClient.PairingException e) {
            assertEquals(e.getStage(), PairingClient.Stage.PROOF_REJECTED, "Stage must be PROOF_REJECTED");
            assertEquals(e.getMessage(), "Host từ chối: mã sai hoặc đã hết hạn — bấm Thêm thiết bị để lấy mã mới", "Exact message required");
        } finally {
            server.close();
            deleteDir(tempDir);
        }
        System.out.println("PASS: testStageProofRejected_EofAfterM1");
    }

    public static void testStageServerProofInvalid_BadM2() throws Exception {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final BigInteger N = PairingProtocol.N_2048;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = server.accept();
                    DataInputStream dis = new DataInputStream(s.getInputStream());
                    DataOutputStream dos = new DataOutputStream(s.getOutputStream());
                    PairingMessages.ClientHello hello = PairingMessages.ClientHello.read(dis);
                    BigInteger b = PairingProtocol.randomExponent(new SecureRandom(), N);
                    BigInteger serverB = PairingProtocol.computeServerB(BigInteger.valueOf(3), BigInteger.valueOf(2), BigInteger.valueOf(2), b, N);
                    byte[] salt = new byte[16];
                    new PairingMessages.ServerHello(PairingMessages.PROTOCOL_VERSION, salt, PairingProtocol.pad(serverB, N)).write(dos);
                    dos.flush();
                    byte[] clientM1 = new byte[32];
                    dis.readFully(clientM1);
                    // Send bad M2 (32 bytes)
                    byte[] badM2 = new byte[32];
                    Arrays.fill(badM2, (byte) 0xEE);
                    dos.write(badM2);
                    dos.flush();
                    s.close();
                } catch (Exception ignored) {}
            }
        }).start();

        File tempDir = File.createTempFile("pcdash_m2_", ".dir");
        tempDir.delete();
        tempDir.mkdirs();
        try {
            PairingClient client = new PairingClient(server.getLocalPort());
            client.pair(InetAddress.getByName("127.0.0.1"), "ABC234", "Phone", tempDir, new FakePcnetBridge());
            throw new AssertionError("Expected PairingException(SERVER_PROOF_INVALID)");
        } catch (PairingClient.PairingException e) {
            assertEquals(e.getStage(), PairingClient.Stage.SERVER_PROOF_INVALID, "Stage must be SERVER_PROOF_INVALID");
            assertTrue(e.getMessage().contains("Host trả lời không hợp lệ"), "Message should mention Host trả lời không hợp lệ");
        } finally {
            server.close();
            deleteDir(tempDir);
        }
        System.out.println("PASS: testStageServerProofInvalid_BadM2");
    }

    public static void testStageCredentials_Corrupted() throws Exception {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final String pairCode = "ABC234";
        final BigInteger N = PairingProtocol.N_2048;
        final BigInteger g = PairingProtocol.G;
        final String hash = PairingProtocol.HASH_SHA256;
        final byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String normalizedCode = PairingProtocol.normalizeCode(pairCode);
        BigInteger x = PairingProtocol.computeX(hash, PairingProtocol.PAIRING_IDENTITY, normalizedCode, salt);
        final BigInteger v = PairingProtocol.computeV(g, x, N);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = server.accept();
                    DataInputStream dis = new DataInputStream(s.getInputStream());
                    DataOutputStream dos = new DataOutputStream(s.getOutputStream());

                    PairingMessages.ClientHello clientHello = PairingMessages.ClientHello.read(dis);
                    BigInteger clientA = PairingProtocol.fromBytes(clientHello.clientA);

                    BigInteger k = PairingProtocol.computeK(hash, N, g);
                    BigInteger b = PairingProtocol.randomExponent(new SecureRandom(), N);
                    BigInteger serverB = PairingProtocol.computeServerB(k, v, g, b, N);
                    new PairingMessages.ServerHello(PairingMessages.PROTOCOL_VERSION, salt, PairingProtocol.pad(serverB, N)).write(dos);
                    dos.flush();

                    BigInteger u = PairingProtocol.computeU(hash, N, clientA, serverB);
                    BigInteger serverS = PairingProtocol.computeServerS(clientA, v, u, b, N);
                    byte[] sessionKeyK = PairingProtocol.deriveSessionKeyK(hash, serverS, N);
                    byte[] expectedM1 = PairingProtocol.computeM1(hash, N, g, PairingProtocol.PAIRING_IDENTITY, salt, clientA, serverB, sessionKeyK);

                    byte[] clientM1 = new byte[32];
                    dis.readFully(clientM1);

                    byte[] m2 = PairingProtocol.computeM2(hash, N, clientA, expectedM1, sessionKeyK);
                    dos.write(m2);

                    // Send corrupted encrypted record
                    byte[] garbageIv = new byte[12];
                    byte[] garbageCiphertext = new byte[30];
                    new PairingMessages.EncryptedRecord(garbageIv, garbageCiphertext).write(dos);
                    dos.flush();
                    s.close();
                } catch (Exception ignored) {}
            }
        }).start();

        File tempDir = File.createTempFile("pcdash_cr_", ".dir");
        tempDir.delete();
        tempDir.mkdirs();
        try {
            PairingClient client = new PairingClient(server.getLocalPort());
            client.pair(InetAddress.getByName("127.0.0.1"), pairCode, "Phone", tempDir, new FakePcnetBridge());
            throw new AssertionError("Expected PairingException(CREDENTIALS)");
        } catch (PairingClient.PairingException e) {
            assertEquals(e.getStage(), PairingClient.Stage.CREDENTIALS, "Stage must be CREDENTIALS");
            assertTrue(e.getMessage().contains("Host trả lời không hợp lệ"), "Message should mention Host trả lời không hợp lệ");
        } finally {
            server.close();
            deleteDir(tempDir);
        }
        System.out.println("PASS: testStageCredentials_Corrupted");
    }

    public static void testStageCredentials_LegacyWithAuthKeyRejected() throws Exception {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final String pairCode = "ABC234";
        final String rawAuthKey = "tskey-auth-sample-super-secret-12345";
        final BigInteger N = PairingProtocol.N_2048;
        final BigInteger g = PairingProtocol.G;
        final String hash = PairingProtocol.HASH_SHA256;
        final byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String normalizedCode = PairingProtocol.normalizeCode(pairCode);
        BigInteger x = PairingProtocol.computeX(hash, PairingProtocol.PAIRING_IDENTITY, normalizedCode, salt);
        final BigInteger v = PairingProtocol.computeV(g, x, N);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = server.accept();
                    DataInputStream dis = new DataInputStream(s.getInputStream());
                    DataOutputStream dos = new DataOutputStream(s.getOutputStream());

                    PairingMessages.ClientHello clientHello = PairingMessages.ClientHello.read(dis);
                    BigInteger clientA = PairingProtocol.fromBytes(clientHello.clientA);

                    BigInteger k = PairingProtocol.computeK(hash, N, g);
                    BigInteger b = PairingProtocol.randomExponent(new SecureRandom(), N);
                    BigInteger serverB = PairingProtocol.computeServerB(k, v, g, b, N);
                    new PairingMessages.ServerHello(PairingMessages.PROTOCOL_VERSION, salt, PairingProtocol.pad(serverB, N)).write(dos);
                    dos.flush();

                    BigInteger u = PairingProtocol.computeU(hash, N, clientA, serverB);
                    BigInteger serverS = PairingProtocol.computeServerS(clientA, v, u, b, N);
                    byte[] sessionKeyK = PairingProtocol.deriveSessionKeyK(hash, serverS, N);
                    byte[] expectedM1 = PairingProtocol.computeM1(hash, N, g, PairingProtocol.PAIRING_IDENTITY, salt, clientA, serverB, sessionKeyK);

                    byte[] clientM1 = new byte[32];
                    dis.readFully(clientM1);

                    byte[] transcript = PairingMessages.computeTranscript(
                            PairingMessages.PROTOCOL_VERSION,
                            clientHello.deviceName,
                            PairingProtocol.pad(clientA, N),
                            PairingProtocol.pad(serverB, N),
                            salt
                    );
                    byte[] aesKey = PairingProtocol.deriveAesKey(sessionKeyK);
                    byte[] m2 = PairingProtocol.computeM2(hash, N, clientA, expectedM1, sessionKeyK);

                    String credsJson = "{\n"
                            + "  \"authKey\": \"" + rawAuthKey + "\",\n"
                            + "  \"deviceToken\": \"token_xyz\",\n"
                            + "  \"hostTailnetIp\": \"100.64.0.50\",\n"
                            + "  \"hostPort\": 8091\n"
                            + "}";
                    PairingMessages.EncryptedRecord credsRecord = PairingProtocol.seal(
                            aesKey,
                            transcript,
                            credsJson.getBytes(StandardCharsets.UTF_8)
                    );
                    dos.write(m2);
                    credsRecord.write(dos);
                    dos.flush();
                    s.close();
                } catch (Exception ignored) {}
            }
        }).start();

        File tempDir = File.createTempFile("pcdash_ts_", ".dir");
        tempDir.delete();
        tempDir.mkdirs();

        FakePcnetBridge bridge = new FakePcnetBridge();
        try {
            PairingClient client = new PairingClient(server.getLocalPort());
            client.pair(InetAddress.getByName("127.0.0.1"), pairCode, "Phone", tempDir, bridge);
            throw new AssertionError("Expected PairingException(CREDENTIALS) when legacy authKey is received");
        } catch (PairingClient.PairingException e) {
            assertEquals(e.getStage(), PairingClient.Stage.CREDENTIALS, "Stage must be CREDENTIALS");
            assertTrue(e.getMessage().contains("authKey"), "Error message should mention authKey: " + e.getMessage());
        } finally {
            server.close();
            deleteDir(tempDir);
        }
        System.out.println("PASS: testStageCredentials_LegacyWithAuthKeyRejected");
    }

    public static void testStageProtocolVersionOne_Rejected() throws Exception {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final String pairCode = "ABC234";
        final BigInteger N = PairingProtocol.N_2048;
        final BigInteger g = PairingProtocol.G;
        final String hash = PairingProtocol.HASH_SHA256;
        final byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String normalizedCode = PairingProtocol.normalizeCode(pairCode);
        BigInteger x = PairingProtocol.computeX(hash, PairingProtocol.PAIRING_IDENTITY, normalizedCode, salt);
        final BigInteger v = PairingProtocol.computeV(g, x, N);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = server.accept();
                    DataInputStream dis = new DataInputStream(s.getInputStream());
                    DataOutputStream dos = new DataOutputStream(s.getOutputStream());

                    PairingMessages.ClientHello clientHello = PairingMessages.ClientHello.read(dis);
                    BigInteger clientA = PairingProtocol.fromBytes(clientHello.clientA);

                    BigInteger k = PairingProtocol.computeK(hash, N, g);
                    BigInteger b = PairingProtocol.randomExponent(new SecureRandom(), N);
                    BigInteger serverB = PairingProtocol.computeServerB(k, v, g, b, N);

                    // Send version "1" (legacy)
                    new PairingMessages.ServerHello("1", salt, PairingProtocol.pad(serverB, N)).write(dos);
                    dos.flush();
                    s.close();
                } catch (Exception ignored) {}
            }
        }).start();

        File tempDir = File.createTempFile("pcdash_v1_", ".dir");
        tempDir.delete();
        tempDir.mkdirs();

        FakePcnetBridge bridge = new FakePcnetBridge();
        try {
            PairingClient client = new PairingClient(server.getLocalPort());
            client.pair(InetAddress.getByName("127.0.0.1"), pairCode, "Phone", tempDir, bridge);
            throw new AssertionError("Expected PairingException(HELLO) when protocol version is 1");
        } catch (PairingClient.PairingException e) {
            assertEquals(e.getStage(), PairingClient.Stage.HELLO, "Stage must be HELLO");
            assertTrue(e.getMessage().contains("phiên bản giao thức không khớp"), "Error message should mention version mismatch: " + e.getMessage());
        } finally {
            server.close();
            deleteDir(tempDir);
        }
        System.out.println("PASS: testStageProtocolVersionOne_Rejected");
    }

    public static void testStageAck_Failure() throws Exception {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final String pairCode = "ABC234";
        final BigInteger N = PairingProtocol.N_2048;
        final BigInteger g = PairingProtocol.G;
        final String hash = PairingProtocol.HASH_SHA256;
        final byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String normalizedCode = PairingProtocol.normalizeCode(pairCode);
        BigInteger x = PairingProtocol.computeX(hash, PairingProtocol.PAIRING_IDENTITY, normalizedCode, salt);
        final BigInteger v = PairingProtocol.computeV(g, x, N);
        final java.util.concurrent.CountDownLatch credsReadLatch = new java.util.concurrent.CountDownLatch(1);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = server.accept();
                    DataInputStream dis = new DataInputStream(s.getInputStream());
                    DataOutputStream dos = new DataOutputStream(s.getOutputStream());

                    PairingMessages.ClientHello clientHello = PairingMessages.ClientHello.read(dis);
                    BigInteger clientA = PairingProtocol.fromBytes(clientHello.clientA);

                    BigInteger k = PairingProtocol.computeK(hash, N, g);
                    BigInteger b = PairingProtocol.randomExponent(new SecureRandom(), N);
                    BigInteger serverB = PairingProtocol.computeServerB(k, v, g, b, N);
                    new PairingMessages.ServerHello(PairingMessages.PROTOCOL_VERSION, salt, PairingProtocol.pad(serverB, N)).write(dos);
                    dos.flush();

                    BigInteger u = PairingProtocol.computeU(hash, N, clientA, serverB);
                    BigInteger serverS = PairingProtocol.computeServerS(clientA, v, u, b, N);
                    byte[] sessionKeyK = PairingProtocol.deriveSessionKeyK(hash, serverS, N);
                    byte[] expectedM1 = PairingProtocol.computeM1(hash, N, g, PairingProtocol.PAIRING_IDENTITY, salt, clientA, serverB, sessionKeyK);

                    byte[] clientM1 = new byte[32];
                    dis.readFully(clientM1);

                    byte[] transcript = PairingMessages.computeTranscript(
                            PairingMessages.PROTOCOL_VERSION,
                            clientHello.deviceName,
                            PairingProtocol.pad(clientA, N),
                            PairingProtocol.pad(serverB, N),
                            salt
                    );
                    byte[] aesKey = PairingProtocol.deriveAesKey(sessionKeyK);
                    byte[] m2 = PairingProtocol.computeM2(hash, N, clientA, expectedM1, sessionKeyK);

                    String credsJson = "{\n"
                            + "  \"deviceToken\": \"token_xyz\",\n"
                            + "  \"hostTailnetIp\": \"100.64.0.50\",\n"
                            + "  \"hostPort\": 8091\n"
                            + "}";
                    PairingMessages.EncryptedRecord credsRecord = PairingProtocol.seal(
                            aesKey,
                            transcript,
                            credsJson.getBytes(StandardCharsets.UTF_8)
                    );
                    dos.write(m2);
                    credsRecord.write(dos);
                    dos.flush();

                    // Wait until client has received and decrypted credentials
                    credsReadLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    // Force reset connection before client can write ACK
                    s.setSoLinger(true, 0);
                    s.close();
                } catch (Exception ignored) {}
            }
        }).start();

        File tempDir = File.createTempFile("pcdash_ack_", ".dir");
        tempDir.delete();
        tempDir.mkdirs();

        FakePcnetBridge bridge = new FakePcnetBridge() {
            @Override
            public String nodeId() {
                credsReadLatch.countDown();
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                return super.nodeId();
            }
        };
        try {
            PairingClient client = new PairingClient(server.getLocalPort());
            client.pair(InetAddress.getByName("127.0.0.1"), pairCode, "Phone", tempDir, bridge);
            throw new AssertionError("Expected PairingException(ACK)");
        } catch (PairingClient.PairingException e) {
            assertEquals(e.getStage(), PairingClient.Stage.ACK, "Stage must be ACK");
            assertTrue(e.getMessage().contains("ACK"), "Message should mention ACK");
        } finally {
            server.close();
            deleteDir(tempDir);
        }
        System.out.println("PASS: testStageAck_Failure");
    }
    public static void testStageAck_HostReturnsErrorCode() throws Exception {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final String pairCode = "ABC234";
        final BigInteger N = PairingProtocol.N_2048;
        final BigInteger g = PairingProtocol.G;
        final String hash = PairingProtocol.HASH_SHA256;
        final byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String normalizedCode = PairingProtocol.normalizeCode(pairCode);
        BigInteger x = PairingProtocol.computeX(hash, PairingProtocol.PAIRING_IDENTITY, normalizedCode, salt);
        final BigInteger v = PairingProtocol.computeV(g, x, N);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = server.accept();
                    DataInputStream dis = new DataInputStream(s.getInputStream());
                    DataOutputStream dos = new DataOutputStream(s.getOutputStream());

                    PairingMessages.ClientHello clientHello = PairingMessages.ClientHello.read(dis);
                    BigInteger clientA = PairingProtocol.fromBytes(clientHello.clientA);

                    BigInteger k = PairingProtocol.computeK(hash, N, g);
                    BigInteger b = PairingProtocol.randomExponent(new SecureRandom(), N);
                    BigInteger serverB = PairingProtocol.computeServerB(k, v, g, b, N);
                    new PairingMessages.ServerHello(PairingMessages.PROTOCOL_VERSION, salt, PairingProtocol.pad(serverB, N)).write(dos);
                    dos.flush();

                    BigInteger u = PairingProtocol.computeU(hash, N, clientA, serverB);
                    BigInteger serverS = PairingProtocol.computeServerS(clientA, v, u, b, N);
                    byte[] sessionKeyK = PairingProtocol.deriveSessionKeyK(hash, serverS, N);
                    byte[] expectedM1 = PairingProtocol.computeM1(hash, N, g, PairingProtocol.PAIRING_IDENTITY, salt, clientA, serverB, sessionKeyK);

                    byte[] clientM1 = new byte[32];
                    dis.readFully(clientM1);

                    byte[] transcript = PairingMessages.computeTranscript(
                            PairingMessages.PROTOCOL_VERSION,
                            clientHello.deviceName,
                            PairingProtocol.pad(clientA, N),
                            PairingProtocol.pad(serverB, N),
                            salt
                    );
                    byte[] aesKey = PairingProtocol.deriveAesKey(sessionKeyK);
                    byte[] m2 = PairingProtocol.computeM2(hash, N, clientA, expectedM1, sessionKeyK);

                    String credsJson = "{\n"
                            + "  \"deviceToken\": \"token_xyz\",\n"
                            + "  \"hostTailnetIp\": \"100.64.0.50\",\n"
                            + "  \"hostPort\": 8091\n"
                            + "}";
                    PairingMessages.EncryptedRecord credsRecord = PairingProtocol.seal(
                            aesKey,
                            transcript,
                            credsJson.getBytes(StandardCharsets.UTF_8)
                    );
                    dos.write(m2);
                    credsRecord.write(dos);
                    dos.flush();

                    // Read ACK record
                    PairingMessages.EncryptedRecord.read(dis);

                    // Send error result record
                    PairingMessages.EncryptedRecord resultRecord = PairingProtocol.seal(
                            aesKey,
                            transcript,
                            "{\"status\":\"error\",\"code\":\"NODE_NOT_FOUND\",\"message\":\"Device not found\"}".getBytes(StandardCharsets.UTF_8)
                    );
                    resultRecord.write(dos);
                    dos.flush();
                    s.close();
                } catch (Exception ignored) {}
            }
        }).start();

        File tempDir = File.createTempFile("pcdash_ack_err_", ".dir");
        tempDir.delete();
        tempDir.mkdirs();

        FakePcnetBridge bridge = new FakePcnetBridge();
        try {
            PairingClient client = new PairingClient(server.getLocalPort());
            client.pair(InetAddress.getByName("127.0.0.1"), pairCode, "Phone", tempDir, bridge);
            throw new AssertionError("Expected PairingException(ACK) with NODE_NOT_FOUND");
        } catch (PairingClient.PairingException e) {
            assertEquals(e.getStage(), PairingClient.Stage.ACK, "Stage must be ACK");
            assertEquals(e.getErrorCode(), "NODE_NOT_FOUND", "ErrorCode must be NODE_NOT_FOUND");
            assertTrue(e.getMessage().contains("NODE_NOT_FOUND"), "Message should contain NODE_NOT_FOUND in VN");
            assertTrue(!bridge.isRunning(), "Bridge must be stopped on error");
        } finally {
            server.close();
            deleteDir(tempDir);
        }
        System.out.println("PASS: testStageAck_HostReturnsErrorCode");
    }


    public static void testNoSecretsInLogsOrMessages() throws Exception {
        final List<String> logs = new ArrayList<String>();
        PairingClient.setLogListener(new PairingClient.LogListener() {
            @Override
            public void onLog(int priority, String tag, String msg) {
                assertEquals(tag, PairingClient.TAG, "Tag must be PcdashPair");
                logs.add(msg);
            }
        });

        final String secretCode = "MNP234";
        final String secretAuthKey = "tskey-auth-secret-test-sample-8888";
        final String secretToken = "device_secret_token_abc";

        try {
            // Run successful pair
            final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            final int port = server.getLocalPort();
            final BigInteger N = PairingProtocol.N_2048;
            final BigInteger g = PairingProtocol.G;
            final String hash = PairingProtocol.HASH_SHA256;
            final byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            String normalizedCode = PairingProtocol.normalizeCode(secretCode);
            BigInteger x = PairingProtocol.computeX(hash, PairingProtocol.PAIRING_IDENTITY, normalizedCode, salt);
            final BigInteger v = PairingProtocol.computeV(g, x, N);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Socket s = server.accept();
                        DataInputStream dis = new DataInputStream(s.getInputStream());
                        DataOutputStream dos = new DataOutputStream(s.getOutputStream());
                        PairingMessages.ClientHello clientHello = PairingMessages.ClientHello.read(dis);
                        BigInteger clientA = PairingProtocol.fromBytes(clientHello.clientA);
                        BigInteger k = PairingProtocol.computeK(hash, N, g);
                        BigInteger b = PairingProtocol.randomExponent(new SecureRandom(), N);
                        BigInteger serverB = PairingProtocol.computeServerB(k, v, g, b, N);
                        new PairingMessages.ServerHello(PairingMessages.PROTOCOL_VERSION, salt, PairingProtocol.pad(serverB, N)).write(dos);
                        dos.flush();
                        BigInteger u = PairingProtocol.computeU(hash, N, clientA, serverB);
                        BigInteger serverS = PairingProtocol.computeServerS(clientA, v, u, b, N);
                        byte[] sessionKeyK = PairingProtocol.deriveSessionKeyK(hash, serverS, N);
                        byte[] expectedM1 = PairingProtocol.computeM1(hash, N, g, PairingProtocol.PAIRING_IDENTITY, salt, clientA, serverB, sessionKeyK);
                        byte[] clientM1 = new byte[32];
                        dis.readFully(clientM1);
                        byte[] transcript = PairingMessages.computeTranscript(
                                PairingMessages.PROTOCOL_VERSION,
                                clientHello.deviceName,
                                PairingProtocol.pad(clientA, N),
                                PairingProtocol.pad(serverB, N),
                                salt
                        );
                        byte[] aesKey = PairingProtocol.deriveAesKey(sessionKeyK);
                        byte[] m2 = PairingProtocol.computeM2(hash, N, clientA, expectedM1, sessionKeyK);
                        String credsJson = "{\n"
                                + "  \"deviceToken\": \"" + secretToken + "\",\n"
                                + "  \"hostTailnetIp\": \"100.64.0.10\",\n"
                                + "  \"hostPort\": 8091\n"
                                + "}";
                        PairingMessages.EncryptedRecord credsRecord = PairingProtocol.seal(aesKey, transcript, credsJson.getBytes(StandardCharsets.UTF_8));
                        dos.write(m2);
                        credsRecord.write(dos);
                        dos.flush();
                        PairingMessages.EncryptedRecord.read(dis);
                        PairingMessages.EncryptedRecord resultRecord = PairingProtocol.seal(
                                aesKey,
                                transcript,
                                "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8)
                        );
                        resultRecord.write(dos);
                        dos.flush();
                        s.close();
                        server.close();
                    } catch (Exception ignored) {}
                }
            }).start();

            File tempDir = File.createTempFile("pcdash_log_", ".dir");
            tempDir.delete();
            tempDir.mkdirs();

            PairingClient client = new PairingClient(port);
            FakePcnetBridge bridge = new FakePcnetBridge();
            client.pair(InetAddress.getByName("127.0.0.1"), secretCode, "CleanDevice", tempDir, bridge);

            assertTrue(!logs.isEmpty(), "Log entries should have been recorded");
            for (String line : logs) {
                assertTrue(!line.contains(secretCode), "Log line must NOT contain pairing code: " + line);
                assertTrue(!line.contains(secretAuthKey), "Log line must NOT contain authKey: " + line);
                assertTrue(!line.contains(secretToken), "Log line must NOT contain deviceToken: " + line);
            }
            deleteDir(tempDir);
        } finally {
            PairingClient.setLogListener(null);
        }
        System.out.println("PASS: testNoSecretsInLogsOrMessages");
    }

    public static void testRemoteConfigStoreHostnamePersistence() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "pcdash_hostname_test_" + System.currentTimeMillis());
        tempDir.mkdirs();
        File configFile = new File(tempDir, "config.json");

        RemoteConfigStore store = new RemoteConfigStore(configFile);
        String h1 = store.getOrCreateHostname();
        assertTrue(h1.startsWith("pcdash-remote-"), "Hostname prefix must be pcdash-remote-");
        assertEquals(h1.length(), 18, "Hostname length must be 18 (pcdash-remote-xxxx)");

        String h2 = store.getOrCreateHostname();
        assertEquals(h1, h2, "Subsequent getOrCreateHostname calls must return identical hostname");

        // Reload store from disk
        RemoteConfigStore store2 = new RemoteConfigStore(configFile);
        assertEquals(store2.getOrCreateHostname(), h1, "Reloaded store must read persisted hostname from disk");

        // Clear store - hostname must remain
        store.save(new RemoteConfigStore.RemoteConfig("tok_test", "100.64.0.1", 8091));
        store.clear();
        assertEquals(store.getOrCreateHostname(), h1, "clear() must retain persistent hostname");

        deleteDir(tempDir);
        System.out.println("PASS: testRemoteConfigStoreHostnamePersistence");
    }
}
