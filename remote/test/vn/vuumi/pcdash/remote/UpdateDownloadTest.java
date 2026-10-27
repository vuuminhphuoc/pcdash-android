package vn.vuumi.pcdash.remote;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import vn.vuumi.pcdash.net.PcnetBridge;
public class UpdateDownloadTest {

    public static void main(String[] args) throws Exception {
        System.out.println("Running UpdateDownloadTest...");
        testDownloadSuccess();
        testDownloadShaMismatch();
        testDownloadContentLengthMismatch();
        testDownloadInterruptedMidway();
        testDownloadCancelledMidway();
        testDownloadHttp401ThrowsDeviceRevokedException();
        System.out.println("ALL UpdateDownloadTest assertions PASSED!");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    private static void assertFalse(boolean cond, String msg) {
        if (cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    private static String computeSha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static class TestRig {
        final ServerSocket server;
        final int port;
        final File testDir;
        final RemoteController controller;

        TestRig() throws Exception {
            server = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
            port = server.getLocalPort();

            testDir = new File(System.getProperty("java.io.tmpdir"), "upd_test_" + System.nanoTime());
            testDir.mkdirs();

            File configFile = new File(testDir, "config.json");
            File stateDir = new File(testDir, "state");
            stateDir.mkdirs();

            RemoteConfigStore store = new RemoteConfigStore(configFile);
            store.save(new RemoteConfigStore.RemoteConfig("token_123", "100.64.0.2", 8799));

            FakePcnetBridge bridge = new FakePcnetBridge();
            bridge.localPort = port;

            controller = new RemoteController(null, bridge, store, new RemoteApiClient(), stateDir);
            controller.open();
        }

        void cleanup() {
            try {
                server.close();
            } catch (Exception ignored) {}
            deleteRecursively(testDir);
        }
    }

    private static class FakePcnetBridge extends PcnetBridge {
        int localPort;
        @Override
        public boolean isNativeAvailable() { return true; }
        @Override
        public boolean isRunning() { return true; }
        @Override
        public int getBoundPort() { return localPort; }
        @Override
        public boolean begin(File stateDir, String hostname, int mode, String host, int hostPort, int localPort) {
            setBoundPort(this.localPort);
            return true;
        }
        @Override
        public vn.vuumi.pcdash.net.PcnetState state() {
            return new vn.vuumi.pcdash.net.PcnetState("running", "", java.util.Collections.singletonList("100.64.0.1"), localPort, "");
        }
        @Override
        public void stop() {}
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursively(child);
                }
            }
        }
        f.delete();
    }

    private static boolean hasTempFiles(File dir) {
        if (dir == null || !dir.exists()) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.getName().contains(".tmp")) {
                return true;
            }
        }
        return false;
    }

    public static void testDownloadSuccess() throws Exception {
        final TestRig rig = new TestRig();
        final byte[] payload = new byte[70000]; // 70 KB to test multiple 32 KB blocks
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        final String sha = computeSha256(payload);

        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = rig.server.accept();
                    InputStream in = s.getInputStream();
                    byte[] reqBuf = new byte[1024];
                    int r = in.read(reqBuf);
                    String req = new String(reqBuf, 0, r, StandardCharsets.UTF_8);
                    assertTrue(req.contains("GET /v1/update/apk"), "must request apk");
                    assertTrue(req.contains("Authorization: Bearer token_123"), "must include Bearer token");

                    OutputStream out = s.getOutputStream();
                    String header = "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: application/vnd.android.package-archive\r\n"
                            + "Content-Length: " + payload.length + "\r\n"
                            + "Connection: close\r\n\r\n";
                    out.write(header.getBytes(StandardCharsets.UTF_8));
                    out.write(payload);
                    out.flush();
                    s.close();
                } catch (Exception ignored) {}
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        File dest = new File(rig.testDir, "update.apk");
        UpdateInfo info = new UpdateInfo(true, 2, "1.1", sha, payload.length);
        final AtomicLong lastBytes = new AtomicLong(0);

        rig.controller.downloadUpdate(info, dest, new ProgressListener() {
            @Override
            public void onProgress(long bytesRead, long totalBytes) {
                lastBytes.set(bytesRead);
            }
        }, null);

        assertTrue(dest.exists(), "dest file must exist");
        assertTrue(dest.length() == payload.length, "dest file size must match");
        assertTrue(lastBytes.get() == payload.length, "progress listener must report complete bytes");
        assertFalse(hasTempFiles(rig.testDir), "no temp files must remain");

        rig.cleanup();
        System.out.println("  PASS: testDownloadSuccess");
    }

    public static void testDownloadShaMismatch() throws Exception {
        final TestRig rig = new TestRig();
        final byte[] payload = new byte[100];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        String wrongSha = "0000000000000000000000000000000000000000000000000000000000000000";

        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = rig.server.accept();
                    InputStream in = s.getInputStream();
                    byte[] reqBuf = new byte[1024];
                    in.read(reqBuf);

                    OutputStream out = s.getOutputStream();
                    String header = "HTTP/1.1 200 OK\r\n"
                            + "Content-Length: " + payload.length + "\r\n"
                            + "Connection: close\r\n\r\n";
                    out.write(header.getBytes(StandardCharsets.UTF_8));
                    out.write(payload);
                    out.flush();
                    s.close();
                } catch (Exception ignored) {}
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        File dest = new File(rig.testDir, "update.apk");
        UpdateInfo info = new UpdateInfo(true, 2, "1.1", wrongSha, payload.length);

        boolean threw = false;
        try {
            rig.controller.downloadUpdate(info, dest, null, null);
        } catch (IOException e) {
            threw = true;
            assertTrue(e.getMessage().contains("SHA-256 mismatch"), "error message must mention SHA-256 mismatch");
        }

        assertTrue(threw, "must throw IOException on SHA mismatch");
        assertFalse(dest.exists(), "dest must not exist on SHA mismatch");
        assertFalse(hasTempFiles(rig.testDir), "temp file must be deleted on SHA mismatch");

        rig.cleanup();
        System.out.println("  PASS: testDownloadShaMismatch");
    }

    public static void testDownloadContentLengthMismatch() throws Exception {
        final TestRig rig = new TestRig();
        final byte[] payload = new byte[100];
        String sha = computeSha256(payload);

        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = rig.server.accept();
                    InputStream in = s.getInputStream();
                    byte[] reqBuf = new byte[1024];
                    in.read(reqBuf);

                    OutputStream out = s.getOutputStream();
                    // Server returns Content-Length 80 instead of 100
                    String header = "HTTP/1.1 200 OK\r\n"
                            + "Content-Length: 80\r\n"
                            + "Connection: close\r\n\r\n";
                    out.write(header.getBytes(StandardCharsets.UTF_8));
                    out.write(payload, 0, 80);
                    out.flush();
                    s.close();
                } catch (Exception ignored) {}
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        File dest = new File(rig.testDir, "update.apk");
        UpdateInfo info = new UpdateInfo(true, 2, "1.1", sha, 100);

        boolean threw = false;
        try {
            rig.controller.downloadUpdate(info, dest, null, null);
        } catch (IOException e) {
            threw = true;
            assertTrue(e.getMessage().contains("Content-Length mismatch"), "error message must mention Content-Length mismatch");
        }

        assertTrue(threw, "must throw IOException on Content-Length mismatch");
        assertFalse(dest.exists(), "dest must not exist");
        assertFalse(hasTempFiles(rig.testDir), "temp file must be deleted");

        rig.cleanup();
        System.out.println("  PASS: testDownloadContentLengthMismatch");
    }

    public static void testDownloadInterruptedMidway() throws Exception {
        final TestRig rig = new TestRig();
        final byte[] payload = new byte[100];
        String sha = computeSha256(payload);

        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = rig.server.accept();
                    InputStream in = s.getInputStream();
                    byte[] reqBuf = new byte[1024];
                    in.read(reqBuf);

                    OutputStream out = s.getOutputStream();
                    String header = "HTTP/1.1 200 OK\r\n"
                            + "Content-Length: 100\r\n"
                            + "Connection: close\r\n\r\n";
                    out.write(header.getBytes(StandardCharsets.UTF_8));
                    // Send only 30 bytes then abruptly close
                    out.write(payload, 0, 30);
                    out.flush();
                    s.close();
                } catch (Exception ignored) {}
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        File dest = new File(rig.testDir, "update.apk");
        UpdateInfo info = new UpdateInfo(true, 2, "1.1", sha, 100);

        boolean threw = false;
        try {
            rig.controller.downloadUpdate(info, dest, null, null);
        } catch (IOException e) {
            threw = true;
        }

        assertTrue(threw, "must throw IOException on interrupted stream");
        assertFalse(dest.exists(), "dest must not exist");
        assertFalse(hasTempFiles(rig.testDir), "temp file must be deleted when interrupted");

        rig.cleanup();
        System.out.println("  PASS: testDownloadInterruptedMidway");
    }

    public static void testDownloadCancelledMidway() throws Exception {
        final TestRig rig = new TestRig();
        final byte[] payload = new byte[70000];
        String sha = computeSha256(payload);

        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = rig.server.accept();
                    InputStream in = s.getInputStream();
                    byte[] reqBuf = new byte[1024];
                    in.read(reqBuf);

                    OutputStream out = s.getOutputStream();
                    String header = "HTTP/1.1 200 OK\r\n"
                            + "Content-Length: " + payload.length + "\r\n"
                            + "Connection: close\r\n\r\n";
                    out.write(header.getBytes(StandardCharsets.UTF_8));
                    out.write(payload);
                    out.flush();
                    s.close();
                } catch (Exception ignored) {}
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        File dest = new File(rig.testDir, "update.apk");
        UpdateInfo info = new UpdateInfo(true, 2, "1.1", sha, payload.length);
        final AtomicCancelFlag cancelFlag = new AtomicCancelFlag();

        boolean threw = false;
        try {
            rig.controller.downloadUpdate(info, dest, new ProgressListener() {
                @Override
                public void onProgress(long bytesRead, long totalBytes) {
                    if (bytesRead > 0) {
                        cancelFlag.cancel();
                    }
                }
            }, cancelFlag);
        } catch (IOException e) {
            threw = true;
            assertTrue(e.getMessage().contains("cancelled"), "message must mention cancelled");
        }

        assertTrue(threw, "must throw IOException on cancel");
        assertFalse(dest.exists(), "dest must not exist on cancel");
        assertFalse(hasTempFiles(rig.testDir), "temp file must be deleted on cancel");

        rig.cleanup();
        System.out.println("  PASS: testDownloadCancelledMidway");
    }

    public static void testDownloadHttp401ThrowsDeviceRevokedException() throws Exception {
        final TestRig rig = new TestRig();

        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = rig.server.accept();
                    InputStream in = s.getInputStream();
                    byte[] reqBuf = new byte[1024];
                    in.read(reqBuf);

                    OutputStream out = s.getOutputStream();
                    String header = "HTTP/1.1 401 Unauthorized\r\n"
                            + "Connection: close\r\n\r\n";
                    out.write(header.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    s.close();
                } catch (Exception ignored) {}
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        File dest = new File(rig.testDir, "update.apk");
        UpdateInfo info = new UpdateInfo(true, 2, "1.1", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 100);

        boolean threw401 = false;
        try {
            rig.controller.downloadUpdate(info, dest, null, null);
        } catch (RemoteApiClient.DeviceRevokedException e) {
            threw401 = true;
        }

        assertTrue(threw401, "must throw DeviceRevokedException on HTTP 401");
        assertFalse(dest.exists(), "dest must not exist on 401");
        assertFalse(hasTempFiles(rig.testDir), "temp file must be deleted on 401");

        rig.cleanup();
        System.out.println("  PASS: testDownloadHttp401ThrowsDeviceRevokedException");
    }
}
