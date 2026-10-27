package vn.vuumi.pcdash;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

public class HostApiTest {

    static class FakeExecutor implements RemoteActionExecutor {
        int statusCalls = 0;
        int actionCalls = 0;
        String lastAction = null;

        @Override
        public String statusJson() {
            statusCalls++;
            return "{\"online\":true,\"port\":8799,\"rtt_ms\":12}";
        }

        @Override
        public String doAction(String action) {
            actionCalls++;
            lastAction = action;
            return "ok: " + action;
        }

        int quotaCalls = 0;
        boolean lastQuotaRefresh = false;
        String quotaResponse = "{\"providers\":[],\"stale\":false}";

        @Override
        public String quotaJson(boolean refresh) {
            quotaCalls++;
            lastQuotaRefresh = refresh;
            return quotaResponse;
        }
    }

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

    static class HttpResponse {
        final int code;
        final String body;

        HttpResponse(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private static HttpResponse sendRequest(int port, String method, String path, String authHeader) throws IOException {
        return sendRequest(port, method, path, authHeader, null, null);
    }

    private static HttpResponse sendRequest(int port, String method, String path, String authHeader, String headerKey, String headerVal) throws IOException {
        URL url = new URL("http://127.0.0.1:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(2000);
        if (authHeader != null) {
            conn.setRequestProperty("Authorization", authHeader);
        }
        if (headerKey != null) {
            conn.setRequestProperty(headerKey, headerVal != null ? headerVal : "");
        }
        if ("POST".equalsIgnoreCase(method)) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(new byte[0]);
            }
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = "";
        if (is != null) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            body = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            is.close();
        }
        conn.disconnect();
        return new HttpResponse(code, body);
    }

    public static void testMissingWrongAndRevokedBearerNeverInvokesAction() throws Exception {
        File temp = File.createTempFile("dev-store-test1-", ".json");
        temp.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(temp);
        FakeExecutor executor = new FakeExecutor();
        RemoteApiServer server = new RemoteApiServer(store, executor);
        server.start(0);
        int port = server.getPort();

        try {
            String rawToken = "test-token-valid-12345678901234567890";
            RemoteDeviceStore.RemoteDevice dev = store.add("Test Phone", rawToken, "node-1");

            // 1. Missing Authorization header
            HttpResponse resp1 = sendRequest(port, "GET", "/v1/status", null);
            assertEquals(resp1.code, 401, "Missing header should return 401");
            assertEquals(executor.statusCalls, 0, "FakeExecutor must not be invoked on missing header");

            // 2. Wrong Bearer token
            HttpResponse resp2 = sendRequest(port, "GET", "/v1/status", "Bearer wrong-token-value");
            assertEquals(resp2.code, 401, "Wrong Bearer token should return 401");
            assertEquals(executor.statusCalls, 0, "FakeExecutor must not be invoked on wrong Bearer");

            // 3. Malformed Authorization header (not Bearer)
            HttpResponse resp3 = sendRequest(port, "POST", "/v1/wake", "Basic dXNlcjpwYXNz");
            assertEquals(resp3.code, 401, "Non-Bearer header should return 401");
            assertEquals(executor.actionCalls, 0, "FakeExecutor must not be invoked on malformed auth");

            // 4. Token after remove must be 401
            store.remove(dev.id);
            HttpResponse resp4 = sendRequest(port, "GET", "/v1/status", "Bearer " + rawToken);
            assertEquals(resp4.code, 401, "Revoked token should return 401");
            assertEquals(executor.statusCalls, 0, "FakeExecutor must not be invoked on revoked token");

            // 5. Action with revoked token
            HttpResponse resp5 = sendRequest(port, "POST", "/v1/wake", "Bearer " + rawToken);
            assertEquals(resp5.code, 401, "Action with revoked token should return 401");
            assertEquals(executor.actionCalls, 0, "FakeExecutor must not be invoked on action with revoked token");
        } finally {
            server.close();
            temp.delete();
        }
    }

    public static void testValidBearerRoutesStatusAndFiveActions() throws Exception {
        File temp = File.createTempFile("dev-store-test2-", ".json");
        temp.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(temp);
        FakeExecutor executor = new FakeExecutor();
        RemoteApiServer server = new RemoteApiServer(store, executor);
        server.start(0);
        int port = server.getPort();

        try {
            String rawToken = "valid-secret-token-abcdef1234567890";
            store.add("Controller", rawToken, "node-2");
            String auth = "Bearer " + rawToken;

            // GET /v1/status
            HttpResponse statusResp = sendRequest(port, "GET", "/v1/status", auth);
            assertEquals(statusResp.code, 200, "GET /v1/status should return 200");
            assertEquals(executor.statusCalls, 1, "statusCalls should be 1");
            assertTrue(statusResp.body.contains("\"online\":true"), "Status body must match executor output");

            // 5 valid actions: wake, sleep, hibernate, shutdown, abort
            String[] actions = {"wake", "sleep", "hibernate", "shutdown", "abort"};
            for (String action : actions) {
                HttpResponse actResp = sendRequest(port, "POST", "/v1/" + action, auth);
                assertEquals(actResp.code, 200, "POST /v1/" + action + " should return 200");
                assertEquals(executor.lastAction, action, "Executor lastAction should match " + action);
                assertTrue(actResp.body.contains("\"ok\":true"), "Action response body should have ok:true");
            }
            assertEquals(executor.actionCalls, 5, "Executor actionCalls should be 5");

            // Routes that must return 404
            String[] invalidRoutes = {
                "POST /v1/reboot",
                "GET /v1/wake",
                "POST /v1/status",
                "GET /api/status",
                "POST /v1/unknown",
                "POST /v1/",
                "POST /v1/wake/extra",
                "PUT /v1/wake",
                "DELETE /v1/wake",
                "POST /v2/wake",
                "POST /invalid",
                "GET /unknown"
            };
            for (String route : invalidRoutes) {
                String[] rParts = route.split(" ");
                HttpResponse notFound = sendRequest(port, rParts[0], rParts[1], auth);
                assertEquals(notFound.code, 404, route + " must return 404");
            }
            assertEquals(executor.actionCalls, 5,
                    "Invalid and 404 routes MUST NOT increment actionCalls (still exactly 5)");
            assertEquals(executor.statusCalls, 1,
                    "Invalid and 404 routes MUST NOT increment statusCalls (still exactly 1)");
        } finally {
            server.close();
            temp.delete();
        }
    }

    public static void testStorePersistsNameNodeIdAndOnlyTokenHash() throws Exception {
        File temp = File.createTempFile("dev-store-test3-", ".json");
        temp.deleteOnExit();

        byte[] rawBytes = new byte[32];
        new SecureRandom().nextBytes(rawBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes);

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        String expectedHashHex = sb.toString();

        RemoteDeviceStore store = new RemoteDeviceStore(temp);
        RemoteDeviceStore.RemoteDevice dev = store.add("My Android Controller", rawToken, "node-ts-xyz");

        assertEquals(dev.name, "My Android Controller", "Device name must match");
        assertEquals(dev.nodeId, "node-ts-xyz", "Device node ID must match");
        assertEquals(dev.tokenHash, expectedHashHex, "Stored hash must match SHA-256 of token");

        // Assert file does NOT contain raw token
        String fileContent = new String(Files.readAllBytes(temp.toPath()), StandardCharsets.UTF_8);
        assertTrue(!fileContent.contains(rawToken), "Private file MUST NOT contain raw token");
        assertTrue(fileContent.contains(expectedHashHex), "Private file MUST contain SHA-256 hash");
        assertTrue(fileContent.contains("My Android Controller"), "Private file MUST contain device name");
        assertTrue(fileContent.contains("node-ts-xyz"), "Private file MUST contain nodeId");

        // Verify accepts via digest comparison
        assertTrue(store.accepts(rawToken), "Store must accept the correct token");
        assertTrue(!store.accepts("wrong-token"), "Store must reject wrong token");
        assertTrue(!store.accepts(expectedHashHex), "Store must reject tokenHash as raw token");
        assertTrue(!store.accepts(""), "Store must reject empty token");
        assertTrue(!store.accepts(null), "Store must reject null token");

        // Test reload from disk
        RemoteDeviceStore reloaded = new RemoteDeviceStore(temp);
        List<RemoteDeviceStore.RemoteDevice> list = reloaded.list();
        assertEquals(list.size(), 1, "Reloaded store should have 1 device");
        assertEquals(list.get(0).name, "My Android Controller", "Reloaded device name match");
        assertEquals(list.get(0).nodeId, "node-ts-xyz", "Reloaded device nodeId match");
        assertTrue(reloaded.accepts(rawToken), "Reloaded store must accept correct raw token");

        // Test setNodeId
        reloaded.setNodeId(dev.id, "node-ts-updated");
        assertEquals(reloaded.list().get(0).nodeId, "node-ts-updated", "Node ID should be updated");

        RemoteDeviceStore reloadedAgain = new RemoteDeviceStore(temp);
        assertEquals(reloadedAgain.list().get(0).nodeId, "node-ts-updated", "Updated nodeId should persist");

        temp.delete();
    }
    public static void testStoreLastUsedTimestampsAndBackwardCompatibility() throws Exception {
        File temp = File.createTempFile("dev-store-compat-", ".json");
        temp.deleteOnExit();

        // 1. Write an old-format JSON (without lastUsedAt/createdAtMs)
        String oldJson = "[\n"
                + "  {\n"
                + "    \"id\": \"old-dev-1\",\n"
                + "    \"name\": \"Old Device\",\n"
                + "    \"tokenHash\": \"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\",\n"
                + "    \"nodeId\": \"node-old-1\",\n"
                + "    \"createdAt\": 1698000000000\n"
                + "  }\n"
                + "]\n";
        Files.write(temp.toPath(), oldJson.getBytes(StandardCharsets.UTF_8));

        // 2. Load it into RemoteDeviceStore
        RemoteDeviceStore store = new RemoteDeviceStore(temp);
        List<RemoteDeviceStore.RemoteDevice> list = store.list();
        assertEquals(list.size(), 1, "Old record must load successfully");
        assertEquals(list.get(0).id, "old-dev-1", "ID must match");
        assertEquals(list.get(0).createdAt, 1698000000000L, "CreatedAt must match");
        assertEquals(list.get(0).lastUsedAt, 0L, "Missing lastUsedAt must load as 0L");

        // 3. Add a new device with token, verify identify updates lastUsedAt
        String rawToken = "my-new-token-1234567890123456";
        RemoteDeviceStore.RemoteDevice dev2 = store.add("New Device", rawToken, "node-new-2");
        assertEquals(dev2.lastUsedAt, 0L, "Initial lastUsedAt should be 0L");

        long beforeAuth = System.currentTimeMillis();
        RemoteDeviceStore.RemoteDevice identified = store.identify(rawToken);
        assertTrue(identified != null, "Identified device must not be null");
        assertTrue(identified.lastUsedAt >= beforeAuth, "Identify must update lastUsedAt");

        // 4. Reload from disk, ensure both createdAt and lastUsedAt were persisted
        RemoteDeviceStore reloaded = new RemoteDeviceStore(temp);
        RemoteDeviceStore.RemoteDevice reloadedDev2 = null;
        for (RemoteDeviceStore.RemoteDevice d : reloaded.list()) {
            if ("node-new-2".equals(d.nodeId)) reloadedDev2 = d;
        }
        assertTrue(reloadedDev2 != null, "Reloaded device must exist");
        assertEquals(reloadedDev2.lastUsedAt, identified.lastUsedAt, "Persisted lastUsedAt must match");

        temp.delete();
    }

    public static void testCrashDuringWriteOrRenameKeepsRevokedTokensAndPreservesStore() throws Exception {
        File temp = File.createTempFile("dev-store-crash-", ".json");
        temp.deleteOnExit();

        try {
            RemoteDeviceStore store = new RemoteDeviceStore(temp);
            String token1 = "token-device-one-111111111111111111";
            String token2 = "token-device-two-222222222222222222";
            RemoteDeviceStore.RemoteDevice dev1 = store.add("Device One", token1, "node-1");
            RemoteDeviceStore.RemoteDevice dev2 = store.add("Device Two", token2, "node-2");

            assertTrue(store.accepts(token1), "Device 1 should be accepted initially");
            assertTrue(store.accepts(token2), "Device 2 should be accepted initially");

            // Revoke device 1
            store.remove(dev1.id);
            assertTrue(!store.accepts(token1), "Device 1 should be revoked");
            assertTrue(store.accepts(token2), "Device 2 should still be accepted");

            // 1. Inject writeSync error (crash mid-write before rename)
            RemoteDeviceStore writeFailingStore = new RemoteDeviceStore(temp, new RemoteDeviceStore.AtomicFileWriter() {
                @Override
                public void writeSync(File tempFile, byte[] data) throws IOException {
                    throw new IOException("Simulated write failure / disk crash");
                }

                @Override
                public void atomicReplace(File tempFile, File destFile) throws IOException {
                    throw new IOException("Should not reach atomicReplace on write failure");
                }
            });

            boolean writeFailed = false;
            try {
                writeFailingStore.add("Device Three", "token-device-three-3333333333", "node-3");
            } catch (IOException e) {
                writeFailed = true;
            }
            assertTrue(writeFailed, "Store add should throw on write failure");

            // Reload store from disk after simulated write crash
            RemoteDeviceStore reloadedAfterWriteFailure = new RemoteDeviceStore(temp);
            assertTrue(!reloadedAfterWriteFailure.accepts(token1),
                    "Revoked token1 MUST NOT be accepted after reload following write crash");
            assertTrue(reloadedAfterWriteFailure.accepts(token2),
                    "Store MUST NOT lose old data (token2 still accepted after write crash)");
            assertEquals(reloadedAfterWriteFailure.list().size(), 1,
                    "Store should still contain exactly 1 device (device 2)");

            // 2. Inject atomicReplace error (crash during rename/commit)
            RemoteDeviceStore renameFailingStore = new RemoteDeviceStore(temp, new RemoteDeviceStore.AtomicFileWriter() {
                @Override
                public void writeSync(File tempFile, byte[] data) throws IOException {
                    RemoteDeviceStore.DEFAULT_WRITER.writeSync(tempFile, data);
                }

                @Override
                public void atomicReplace(File tempFile, File destFile) throws IOException {
                    throw new IOException("Simulated rename failure / crash before swap");
                }
            });

            boolean renameFailed = false;
            try {
                renameFailingStore.add("Device Four", "token-device-four-4444444444", "node-4");
            } catch (IOException e) {
                renameFailed = true;
            }
            assertTrue(renameFailed, "Store add should throw on rename failure");

            // Reload store from disk after simulated rename crash
            RemoteDeviceStore reloadedAfterRenameFailure = new RemoteDeviceStore(temp);
            assertTrue(!reloadedAfterRenameFailure.accepts(token1),
                    "Revoked token1 MUST NOT be accepted after reload following rename crash");
            assertTrue(reloadedAfterRenameFailure.accepts(token2),
                    "Store MUST NOT lose old data (token2 still accepted after rename crash)");
            assertEquals(reloadedAfterRenameFailure.list().size(), 1,
                    "Store should still contain exactly 1 device (device 2)");
        } finally {
            temp.delete();
            File bak = new File(temp.getAbsolutePath() + ".bak");
            if (bak.exists()) bak.delete();
            File tmp = new File(temp.getAbsolutePath() + ".tmp");
            if (tmp.exists()) tmp.delete();
        }
    }

    public static void testSlowAndTooLargeHeadersRejectedWithoutHoldingThread() throws Exception {
        File temp = File.createTempFile("dev-store-slow-", ".json");
        temp.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(temp);
        String rawToken = "test-token-valid-abc1234567890";
        store.add("Tester", rawToken, "node-slow");
        FakeExecutor executor = new FakeExecutor();

        // Server with 300ms request timeout and max 4 threads
        RemoteApiServer server = new RemoteApiServer(store, executor, 300, 4);
        server.start(0);
        int port = server.getPort();

        try {
            // 1. Header too large (> 8 KB)
            Socket largeSocket = new Socket("127.0.0.1", port);
            largeSocket.setSoTimeout(3000);
            OutputStream out = largeSocket.getOutputStream();
            StringBuilder big = new StringBuilder();
            big.append("GET /v1/status HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer ").append(rawToken).append("\r\nX-Big-Header: ");
            for (int i = 0; i < 9000; i++) {
                big.append('A');
            }
            big.append("\r\n\r\n");
            out.write(big.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(largeSocket.getInputStream(), StandardCharsets.UTF_8));
            String respLine = in.readLine();
            assertTrue(respLine != null && (respLine.contains("431") || respLine.contains("400")),
                    "Header larger than 8 KB must be rejected with 431/400 (got: " + respLine + ")");
            largeSocket.close();

            // 2. Slow header (client stops sending before completing headers)
            Socket slowSocket = new Socket("127.0.0.1", port);
            slowSocket.setSoTimeout(3000);
            OutputStream slowOut = slowSocket.getOutputStream();
            slowOut.write("GET /v1/status HTTP/1.1\r\nHost: 127.0.0.1\r\n".getBytes(StandardCharsets.UTF_8));
            slowOut.flush();

            // Wait for 300ms deadline to expire
            Thread.sleep(450);

            InputStream is = slowSocket.getInputStream();
            byte[] buf = new byte[1024];
            int read = -1;
            try {
                read = is.read(buf);
            } catch (IOException ignored) {}

            if (read > 0) {
                String response = new String(buf, 0, read, StandardCharsets.UTF_8);
                assertTrue(response.contains("408") || response.contains("HTTP/1.1"),
                        "Slow client should receive 408 or connection close");
            }
            slowSocket.close();

            // Verify thread pool has released the worker thread
            Thread.sleep(100);
            int active = server.getThreadPool() != null ? server.getThreadPool().getActiveCount() : 0;
            assertEquals(active, 0, "ThreadPool active thread count must return to 0 without holding threads");
        } finally {
            server.close();
            temp.delete();
        }
    }
    public static void testWebDashboardCoexistsWithRemoteApiServer() throws Exception {
        android.content.Context mockCtx = new android.content.Context();
        WebDashboard dash = new WebDashboard(mockCtx);
        dash.start();
        Thread.sleep(300);

        try {
            HttpResponse h1 = sendRequest(WebDashboard.PORT, "GET", "/healthz", null);
            assertEquals(h1.code, 200, "WebDashboard /healthz must return 200 before RemoteApiServer");
            assertTrue(h1.body.contains("\"ok\":true"), "/healthz body must contain ok:true");

            HttpResponse s1 = sendRequest(WebDashboard.PORT, "GET", "/api/status", null);
            assertEquals(s1.code, 200, "WebDashboard /api/status must return 200 before RemoteApiServer");
            assertTrue(s1.body.contains("\"online\":"), "/api/status body must contain online status");

            File temp = File.createTempFile("dev-store-coexist-", ".json");
            temp.deleteOnExit();
            RemoteDeviceStore store = new RemoteDeviceStore(temp);
            String rawToken = "coexist-valid-token-1234567890";
            store.add("Coexist Device", rawToken, "node-coexist");
            FakeExecutor executor = new FakeExecutor();
            RemoteApiServer remoteServer = new RemoteApiServer(store, executor);
            remoteServer.start(RemoteApiServer.PORT);

            try {
                HttpResponse r1 = sendRequest(RemoteApiServer.PORT, "GET", "/v1/status", "Bearer " + rawToken);
                assertEquals(r1.code, 200, "RemoteApiServer must return 200 on /v1/status");

                HttpResponse h2 = sendRequest(WebDashboard.PORT, "GET", "/healthz", null);
                assertEquals(h2.code, 200, "WebDashboard /healthz must STILL return 200 after RemoteApiServer");
                assertTrue(h2.body.contains("\"ok\":true"), "/healthz body must contain ok:true");

                HttpResponse s2 = sendRequest(WebDashboard.PORT, "GET", "/api/status", null);
                assertEquals(s2.code, 200, "WebDashboard /api/status must STILL return 200 after RemoteApiServer");
                assertTrue(s2.body.contains("\"online\":"), "/api/status body must contain online status");
            } finally {
                remoteServer.close();
                temp.delete();
            }
        } finally {
            dash.stop();
            Thread.sleep(200);
        }
    }

    public static void testIdentify() throws Exception {
        File temp = File.createTempFile("dev-store-id-", ".json");
        temp.deleteOnExit();
        try {
            RemoteDeviceStore store = new RemoteDeviceStore(temp);
            String rawToken = "my-secret-token-ident-123456789";
            RemoteDeviceStore.RemoteDevice dev = store.add("Galaxy S22", rawToken, "node-s22");

            RemoteDeviceStore.RemoteDevice identified = store.identify(rawToken);
            assertTrue(identified != null, "identify must return RemoteDevice for valid token");
            assertEquals(identified.id, dev.id, "Device id must match");
            assertEquals(identified.name, "Galaxy S22", "Device name must match");
            assertEquals(identified.nodeId, "node-s22", "Device nodeId must match");

            assertTrue(store.identify("wrong-token") == null, "identify must return null for wrong token");
            assertTrue(store.identify("") == null, "identify must return null for empty token");
            assertTrue(store.identify(null) == null, "identify must return null for null token");

            store.remove(dev.id);
            assertTrue(store.identify(rawToken) == null, "identify must return null for revoked token");
        } finally {
            temp.delete();
        }
    }

    public static void testRouteLogAndCommandLogging() throws Exception {
        File tempStore = File.createTempFile("dev-store-log-", ".json");
        File tempLog = File.createTempFile("cmdlog-route-", ".jsonl");
        tempStore.deleteOnExit();
        tempLog.deleteOnExit();

        RemoteDeviceStore store = new RemoteDeviceStore(tempStore);
        String rawToken = "token-for-log-test-1234567890";
        store.add("My Remote Phone", rawToken, "node-phone-log");
        String auth = "Bearer " + rawToken;

        CommandLog commandLog = new CommandLog(tempLog);
        FakeExecutor executor = new FakeExecutor();
        RemoteApiServer server = new RemoteApiServer(store, executor, null, commandLog, null);
        server.start(0);
        int port = server.getPort();

        final String fakeWebToken = "testwebsecret123456789012";
        PcSettingsSource fakeSettings = new PcSettingsSource() {
            @Override public String getPcIp() { return "192.168.1.50"; }
            @Override public String getPcMac() { return "00:11:22:33:44:55"; }
            @Override public int getAgentPort() { return 8799; }
            @Override public String getAgentToken() { return "agent-token-test"; }
            @Override public String getWebToken() { return fakeWebToken; }
            @Override public boolean isConfigured() { return true; }
        };
        android.content.Context mockCtx = new android.content.Context();
        WebDashboard dash = new WebDashboard(mockCtx, commandLog, fakeSettings);
        dash.start();

        try {
            // 1. Missing / wrong Bearer -> 401
            HttpResponse resp401 = sendRequest(port, "GET", "/v1/log", null);
            assertEquals(resp401.code, 401, "GET /v1/log without Bearer must return 401");
            HttpResponse respWrong = sendRequest(port, "GET", "/v1/log", "Bearer bad-token");
            assertEquals(respWrong.code, 401, "GET /v1/log with wrong Bearer must return 401");

            // 2. Empty log -> 200 with entries:[]
            HttpResponse respEmpty = sendRequest(port, "GET", "/v1/log", auth);
            assertEquals(respEmpty.code, 200, "GET /v1/log with valid Bearer must return 200");
            assertEquals(respEmpty.body, "{\"entries\":[]}", "Empty entries array");

            // 3. POST /v1/wake via remote
            HttpResponse actResp = sendRequest(port, "POST", "/v1/wake", auth);
            assertEquals(actResp.code, 200, "POST /v1/wake must return 200");

            // 4. Verify log entry from remote
            HttpResponse logResp1 = sendRequest(port, "GET", "/v1/log", auth);
            assertEquals(logResp1.code, 200, "GET /v1/log must return 200");
            assertTrue(logResp1.body.contains("\"device\":\"My Remote Phone\""), "Must contain device name from identify");
            assertTrue(logResp1.body.contains("\"source\":\"remote\""), "Must contain source: remote");
            assertTrue(logResp1.body.contains("\"action\":\"wake\""), "Must contain action: wake");
            assertTrue(logResp1.body.contains("\"ok\":true"), "Must contain ok: true");

            // 5. POST /api/sleep via WebDashboard (port 8090)
            HttpResponse webResp = sendRequest(WebDashboard.PORT, "POST", "/api/sleep", null, "X-PCDash-Token", fakeWebToken);
            assertEquals(webResp.code, 200, "WebDashboard /api/sleep must return 200");

            // 6. Verify 2 entries in log, newest first (web sleep first, then remote wake)
            HttpResponse logResp2 = sendRequest(port, "GET", "/v1/log", auth);
            assertTrue(logResp2.body.contains("\"device\":\"web\""), "Must contain device: web");
            assertTrue(logResp2.body.contains("\"source\":\"web\""), "Must contain source: web");
            assertTrue(logResp2.body.contains("\"action\":\"sleep\""), "Must contain action: sleep");

            // Check newest first ordering: sleep should appear before wake in entries
            int idxSleep = logResp2.body.indexOf("\"action\":\"sleep\"");
            int idxWake = logResp2.body.indexOf("\"action\":\"wake\"");
            assertTrue(idxSleep < idxWake, "Newest entry (sleep) must appear before older entry (wake)");

            // 7. Query param limit: limit=1
            HttpResponse logLimit1 = sendRequest(port, "GET", "/v1/log?limit=1", auth);
            assertTrue(logLimit1.body.contains("\"action\":\"sleep\""), "Limit 1 should return newest");
            assertTrue(!logLimit1.body.contains("\"action\":\"wake\""), "Limit 1 should not contain older");

            // Query param limit with odd/invalid value -> default 50
            HttpResponse logLimitOdd = sendRequest(port, "GET", "/v1/log?limit=abc", auth);
            assertEquals(logLimitOdd.code, 200, "Limit with non-numeric should succeed with default 50");
            assertTrue(logLimitOdd.body.contains("\"action\":\"wake\""), "Limit abc should return all entries within default 50");

            // Query param limit negative -> default 50
            HttpResponse logLimitNeg = sendRequest(port, "GET", "/v1/log?limit=-5", auth);
            assertEquals(logLimitNeg.code, 200, "Limit with negative should succeed with default 50");
            assertTrue(logLimitNeg.body.contains("\"action\":\"wake\""), "Limit -5 should return all entries within default 50");
        } finally {
            server.close();
            dash.stop();
            tempStore.delete();
            tempLog.delete();
        }
    }

    public static void testRouteHistory() throws Exception {
        File tempStore = File.createTempFile("dev-store-hist-", ".json");
        File tempHist = File.createTempFile("metrics-hist-", ".json");
        tempStore.deleteOnExit();
        tempHist.deleteOnExit();

        RemoteDeviceStore store = new RemoteDeviceStore(tempStore);
        String rawToken = "token-for-history-test-99999999";
        store.add("History Phone", rawToken, "node-hist");
        String auth = "Bearer " + rawToken;

        MetricsHistory history = new MetricsHistory(tempHist);
        long now = System.currentTimeMillis();
        history.add(new MetricsHistory.Sample(now - 60000L, 12.0, 30.0, 45.0, 1000L, 200L, 50.0, 55.0, true));
        history.add(new MetricsHistory.Sample(now - 30000L, null, null, null, null, null, null, null, false));
        history.add(new MetricsHistory.Sample(now, 20.0, 35.0, 50.0, 2000L, 400L, 52.0, 58.0, true));

        FakeExecutor executor = new FakeExecutor();
        RemoteApiServer server = new RemoteApiServer(store, executor, null, null, history);
        server.start(0);
        int port = server.getPort();

        try {
            // 1. Missing / wrong Bearer -> 401
            HttpResponse resp401 = sendRequest(port, "GET", "/v1/history", null);
            assertEquals(resp401.code, 401, "GET /v1/history without Bearer must return 401");
            HttpResponse respWrong = sendRequest(port, "GET", "/v1/history", "Bearer bad-token");
            assertEquals(respWrong.code, 401, "GET /v1/history with wrong Bearer must return 401");

            // 2. Valid request -> 200 OK
            HttpResponse histResp = sendRequest(port, "GET", "/v1/history", auth);
            assertEquals(histResp.code, 200, "GET /v1/history with Bearer must return 200");
            assertTrue(histResp.body.contains("\"intervalMs\":30000"), "Must contain intervalMs");
            assertTrue(histResp.body.contains("\"online\":[1,0,1]"), "Must contain online flags");
            assertTrue(histResp.body.contains("\"cpu\":[12.0,null,20.0]"), "Must contain cpu values with null");

            // 3. Query param minutes
            HttpResponse m30 = sendRequest(port, "GET", "/v1/history?minutes=30", auth);
            assertEquals(m30.code, 200, "minutes=30 returns 200");

            HttpResponse mOdd = sendRequest(port, "GET", "/v1/history?minutes=odd_val", auth);
            assertEquals(mOdd.code, 200, "minutes=odd_val returns 200 (defaults to 60)");

            HttpResponse mBig = sendRequest(port, "GET", "/v1/history?minutes=9999", auth);
            assertEquals(mBig.code, 200, "minutes=9999 returns 200 (capped at 360)");

            HttpResponse mNeg = sendRequest(port, "GET", "/v1/history?minutes=-1", auth);
            assertEquals(mNeg.code, 200, "minutes=-1 returns 200 (defaults to 60)");
        } finally {
            server.close();
            tempStore.delete();
            tempHist.delete();
        }
    }

    public static void testRouteQuota() throws Exception {
        File temp = File.createTempFile("dev-store-quota-", ".json");
        temp.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(temp);
        store.add("Controller", "valid-quota-token-123", "node-quota-1");

        FakeExecutor executor = new FakeExecutor();
        RemoteApiServer server = new RemoteApiServer(store, executor);
        server.start(0);
        int port = server.getPort();

        try {
            // 1. Missing token -> 401
            HttpResponse r1 = sendRequest(port, "GET", "/v1/quota", null);
            assertEquals(r1.code, 401, "Missing token should return 401");
            assertTrue(r1.body.contains("unauthorized"), "Should return unauthorized error");

            // 2. Wrong token -> 401
            HttpResponse r2 = sendRequest(port, "GET", "/v1/quota", "Bearer wrong-token");
            assertEquals(r2.code, 401, "Wrong token should return 401");
            assertTrue(r2.body.contains("unauthorized"), "Should return unauthorized error");

            // 3. Valid token without refresh -> 200, calls quotaJson(false)
            HttpResponse r3 = sendRequest(port, "GET", "/v1/quota", "Bearer valid-quota-token-123");
            assertEquals(r3.code, 200, "Valid token should return 200");
            assertEquals(r3.body, "{\"providers\":[],\"stale\":false}", "Body should match quota response");
            assertEquals(executor.quotaCalls, 1, "quotaJson should be called once");
            assertEquals(executor.lastQuotaRefresh, false, "refresh should be false");

            // 4. Valid token with refresh=1 -> 200, calls quotaJson(true)
            HttpResponse r4 = sendRequest(port, "GET", "/v1/quota?refresh=1", "Bearer valid-quota-token-123");
            assertEquals(r4.code, 200, "Valid token with refresh=1 should return 200");
            assertEquals(executor.quotaCalls, 2, "quotaJson should be called twice");
            assertEquals(executor.lastQuotaRefresh, true, "refresh should be true when ?refresh=1");

            // 5. When executor returns null, server returns fallback {"providers":[],"stale":true}
            executor.quotaResponse = null;
            HttpResponse r5 = sendRequest(port, "GET", "/v1/quota", "Bearer valid-quota-token-123");
            assertEquals(r5.code, 200, "Null quotaJson should return 200 with fallback");
            assertEquals(r5.body, "{\"providers\":[],\"stale\":true}", "Fallback body should be empty stale");
        } finally {
            server.close();
            temp.delete();
        }
    }
    public static void testRemoteApiServerBindsOnlyLoopback() throws Exception {
        File temp = File.createTempFile("dev-store-bind-", ".json");
        temp.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(temp);
        RemoteApiServer server = new RemoteApiServer(store, new FakeExecutor());
        server.setLoopbackOnly(true);
        server.start(0);
        try {
            assertTrue(server.isRunning(), "Server must be running");
            assertTrue(server.getBindAddress() != null, "Bind address must not be null");
            assertTrue(server.getBindAddress().isLoopbackAddress(), "Server must only bind loopback address");
            assertEquals(server.getBindAddress().getHostAddress(), "127.0.0.1", "Bind address must be 127.0.0.1");
        } finally {
            server.close();
        }
    }

    public static void testWebDashboardAuth401WithoutTokenAnd200WithToken() throws Exception {
        final String expectedToken = "webtoken-secret-12345678";
        PcSettingsSource fakeSettings = new PcSettingsSource() {
            @Override public String getPcIp() { return "192.168.1.50"; }
            @Override public String getPcMac() { return "00:11:22:33:44:55"; }
            @Override public int getAgentPort() { return 8799; }
            @Override public String getAgentToken() { return "agent-secret"; }
            @Override public String getWebToken() { return expectedToken; }
            @Override public boolean isConfigured() { return true; }
        };
        File tempLog = File.createTempFile("cmdlog-webauth-", ".jsonl");
        tempLog.deleteOnExit();
        CommandLog commandLog = new CommandLog(tempLog);
        android.content.Context mockCtx = new android.content.Context();
        WebDashboard dash = new WebDashboard(mockCtx, commandLog, fakeSettings);
        dash.start();
        Thread.sleep(200);

        try {
            // POST /api/wake without token -> 401
            HttpResponse rNoToken = sendRequest(WebDashboard.PORT, "POST", "/api/wake", null);
            assertEquals(rNoToken.code, 401, "POST /api/wake without token must return 401");
            assertTrue(rNoToken.body.contains("unauthorized"), "Body must contain unauthorized");

            // POST /api/wake with wrong token -> 401
            HttpResponse rWrong = sendRequest(WebDashboard.PORT, "POST", "/api/wake", null, "X-PCDash-Token", "wrong-token");
            assertEquals(rWrong.code, 401, "POST /api/wake with wrong token must return 401");

            // Verify refused POST logged in CommandLog with ok=false
            java.util.List<CommandLog.LogEntry> entries = commandLog.getEntries(10);
            assertTrue(entries.size() >= 2, "CommandLog must record refused attempts");
            assertEquals(entries.get(0).ok, false, "Refused attempt must be logged with ok=false");
            assertEquals(entries.get(0).source, "web", "Source must be web");

            // POST /api/wake with valid token -> 200
            HttpResponse rValid = sendRequest(WebDashboard.PORT, "POST", "/api/wake", null, "X-PCDash-Token", expectedToken);
            assertEquals(rValid.code, 200, "POST /api/wake with valid token must return 200");
            assertTrue(rValid.body.contains("\"ok\":true"), "Body must contain ok:true");
        } finally {
            dash.stop();
            tempLog.delete();
        }
    }

    public static void testWebDashboardOptionsReturns403() throws Exception {
        android.content.Context mockCtx = new android.content.Context();
        WebDashboard dash = new WebDashboard(mockCtx);
        dash.start();
        Thread.sleep(200);

        try {
            URL url = new URL("http://127.0.0.1:" + WebDashboard.PORT + "/api/wake");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("OPTIONS");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            assertEquals(code, 403, "OPTIONS /api/wake must return 403");
            String allowOrigin = conn.getHeaderField("Access-Control-Allow-Origin");
            assertEquals(allowOrigin, null, "Must never emit Access-Control-Allow-Origin");
        } finally {
            dash.stop();
        }
    }

    public static void testWebDashboardRejectsOversizedHeaders() throws Exception {
        android.content.Context mockCtx = new android.content.Context();
        WebDashboard dash = new WebDashboard(mockCtx);
        dash.start();
        Thread.sleep(200);

        try {
            StringBuilder big = new StringBuilder("GET /healthz HTTP/1.1\r\nX-Fill: ");
            for (int i = 0; i < WebDashboard.MAX_HEADER_BYTES + 100; i++) big.append('a');
            big.append("\r\n\r\n");
            String status;
            try (Socket sock = new Socket("127.0.0.1", WebDashboard.PORT)) {
                sock.setSoTimeout(3000);
                OutputStream os = sock.getOutputStream();
                try {
                    os.write(big.toString().getBytes(StandardCharsets.US_ASCII));
                    os.flush();
                } catch (IOException ignored) {
                    // server may close before the whole header is written
                }
                BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII));
                status = r.readLine();
            }
            assertTrue(status != null && status.startsWith("HTTP/1.1 431"), "oversized header must get 431, got " + status);

            HttpResponse rHealth = sendRequest(WebDashboard.PORT, "GET", "/healthz", null);
            assertEquals(rHealth.code, 200, "dashboard must keep serving after an oversized request");
        } finally {
            dash.stop();
        }
    }

    public static void testDashboardPeerPolicy() throws Exception {
        String[] allowed = { "127.0.0.1", "::1", "192.168.1.20", "10.1.2.3", "172.20.0.5", "169.254.10.1", "100.64.0.9", "fe80::1", "fd7a:115c:a1e0::9", "::ffff:192.168.1.20" };
        String[] refused = { "8.8.8.8", "11.22.33.44", "172.32.0.1", "100.128.0.1", "2001:ee0:6aa:c790::1", "::ffff:8.8.8.8" };
        for (String a : allowed) {
            assertTrue(LanAddressPolicy.isAllowedDashboardPeer(java.net.InetAddress.getByName(a)), a + " must be allowed");
        }
        for (String a : refused) {
            assertTrue(!LanAddressPolicy.isAllowedDashboardPeer(java.net.InetAddress.getByName(a)), a + " must be refused");
        }
        assertTrue(!LanAddressPolicy.isAllowedDashboardPeer(null), "null must be refused");
    }

    public static void testWebDashboardOpenEndpoints() throws Exception {
        android.content.Context mockCtx = new android.content.Context();
        final String secretWebToken = "super-secret-token-do-not-leak";
        PcSettingsSource fakeSettings = new PcSettingsSource() {
            @Override public String getPcIp() { return "192.168.1.50"; }
            @Override public String getPcMac() { return "00:11:22:33:44:55"; }
            @Override public int getAgentPort() { return 8799; }
            @Override public String getAgentToken() { return "agent-sec"; }
            @Override public String getWebToken() { return secretWebToken; }
            @Override public boolean isConfigured() { return true; }
        };
        WebDashboard dash = new WebDashboard(mockCtx, null, fakeSettings);
        dash.start();
        Thread.sleep(200);

        try {
            HttpResponse rRoot = sendRequest(WebDashboard.PORT, "GET", "/", null);
            assertEquals(rRoot.code, 200, "GET / must return 200");

            HttpResponse rHealth = sendRequest(WebDashboard.PORT, "GET", "/healthz", null);
            assertEquals(rHealth.code, 200, "GET /healthz must return 200");
            assertTrue(rHealth.body.contains("\"ok\":true"), "/healthz must return ok:true");

            HttpResponse rStatus = sendRequest(WebDashboard.PORT, "GET", "/api/status", null);
            assertEquals(rStatus.code, 200, "GET /api/status must return 200");
            assertTrue(!rStatus.body.contains(secretWebToken), "/api/status must never leak webToken");
        } finally {
            dash.stop();
        }
    }

    public static void runSmokeServer() throws Exception {
        android.content.Context mockCtx = new android.content.Context();
        WebDashboard dash = new WebDashboard(mockCtx);
        dash.start();
        Thread.sleep(300);
        System.out.println("READY_8090");
        System.out.flush();

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String cmd = reader.readLine();
        RemoteApiServer remoteServer = null;
        File temp = null;
        if ("START_8091".equals(cmd)) {
            temp = File.createTempFile("dev-store-smoke-", ".json");
            temp.deleteOnExit();
            RemoteDeviceStore store = new RemoteDeviceStore(temp);
            store.add("Smoke Device", "smoke-token-secret-123", "node-smoke");
            FakeExecutor executor = new FakeExecutor();
            remoteServer = new RemoteApiServer(store, executor);
            remoteServer.start(RemoteApiServer.PORT);
            System.out.println("READY_8091");
            System.out.flush();
            cmd = reader.readLine();
        }

        if (remoteServer != null) {
            remoteServer.close();
        }
        if (temp != null) {
            temp.delete();
        }
        dash.stop();
        System.out.println("STOPPED");
    }

    public static void main(String[] args) {
        if (args.length > 0 && "--smoke-server".equals(args[0])) {
            try {
                runSmokeServer();
            } catch (Throwable t) {
                t.printStackTrace();
                System.exit(1);
            }
            return;
        }

        System.out.println("Running HostApiTest...");
        try {
            testMissingWrongAndRevokedBearerNeverInvokesAction();
            System.out.println("  PASS: testMissingWrongAndRevokedBearerNeverInvokesAction");
            testValidBearerRoutesStatusAndFiveActions();
            System.out.println("  PASS: testValidBearerRoutesStatusAndFiveActions");
            testStorePersistsNameNodeIdAndOnlyTokenHash();
            System.out.println("  PASS: testStorePersistsNameNodeIdAndOnlyTokenHash");
            testStoreLastUsedTimestampsAndBackwardCompatibility();
            System.out.println("  PASS: testStoreLastUsedTimestampsAndBackwardCompatibility");
            testCrashDuringWriteOrRenameKeepsRevokedTokensAndPreservesStore();
            System.out.println("  PASS: testCrashDuringWriteOrRenameKeepsRevokedTokensAndPreservesStore");
            testSlowAndTooLargeHeadersRejectedWithoutHoldingThread();
            System.out.println("  PASS: testSlowAndTooLargeHeadersRejectedWithoutHoldingThread");
            testWebDashboardCoexistsWithRemoteApiServer();
            System.out.println("  PASS: testWebDashboardCoexistsWithRemoteApiServer");
            testIdentify();
            System.out.println("  PASS: testIdentify");
            testRouteLogAndCommandLogging();
            System.out.println("  PASS: testRouteLogAndCommandLogging");
            testRouteHistory();
            System.out.println("  PASS: testRouteHistory");
            testRouteQuota();
            System.out.println("  PASS: testRouteQuota");
            testRemoteApiServerBindsOnlyLoopback();
            System.out.println("  PASS: testRemoteApiServerBindsOnlyLoopback");
            testWebDashboardAuth401WithoutTokenAnd200WithToken();
            System.out.println("  PASS: testWebDashboardAuth401WithoutTokenAnd200WithToken");
            testWebDashboardOptionsReturns403();
            System.out.println("  PASS: testWebDashboardOptionsReturns403");
            testWebDashboardRejectsOversizedHeaders();
            System.out.println("  PASS: testWebDashboardRejectsOversizedHeaders");
            testDashboardPeerPolicy();
            System.out.println("  PASS: testDashboardPeerPolicy");
            testWebDashboardOpenEndpoints();
            System.out.println("  PASS: testWebDashboardOpenEndpoints");
            System.out.println("ALL 12 TESTS PASSED");
        } catch (Throwable t) {
            System.err.println("TEST FAILED: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }
}
