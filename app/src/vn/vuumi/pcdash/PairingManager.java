package vn.vuumi.pcdash;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.vuumi.pcdash.pairing.PairingMessages;
import vn.vuumi.pcdash.pairing.PairingProtocol;

public class PairingManager {

    public static final int DISCOVERY_PORT = 52191;
    public static final int PAIRING_PORT = 52192;
    public static final long CODE_TTL_MS = 300_000L;
    public static final int MAX_FAILURES = 5;
    public static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    public static final int CODE_LENGTH = 6;
    public static final int MAX_CONCURRENT_PAIRING = 1;
    public static final long SESSION_DEADLINE_MS = 15_000L;

    public static final String TAG = "PcdashPair";

    public interface LogListener {
        void onLog(int priority, String tag, String msg);
    }

    private static volatile LogListener logListener = null;

    public static void setLogListener(LogListener listener) {
        logListener = listener;
    }

    private static volatile java.lang.reflect.Method logMethodI = null;
    private static volatile java.lang.reflect.Method logMethodW = null;
    private static volatile java.lang.reflect.Method logMethodE = null;
    private static volatile boolean logMethodsInitialized = false;

    private static void logAndroid(int priority, String tag, String msg) {
        if (!logMethodsInitialized) {
            synchronized (PairingManager.class) {
                if (!logMethodsInitialized) {
                    try {
                        Class<?> cls = Class.forName("android.util.Log");
                        logMethodI = cls.getMethod("i", String.class, String.class);
                        logMethodW = cls.getMethod("w", String.class, String.class);
                        logMethodE = cls.getMethod("e", String.class, String.class);
                    } catch (Throwable ignored) {}
                    logMethodsInitialized = true;
                }
            }
        }
        try {
            java.lang.reflect.Method m = (priority == 4) ? logMethodI : (priority == 5 ? logMethodW : logMethodE);
            if (m != null) {
                m.invoke(null, tag, msg);
            }
        } catch (Throwable ignored) {}
    }

    static void logI(String tag, String msg) {
        LogListener l = logListener;
        if (l != null) l.onLog(4, tag, msg);
        logAndroid(4, tag, msg);
    }

    static void logW(String tag, String msg) {
        LogListener l = logListener;
        if (l != null) l.onLog(5, tag, msg);
        logAndroid(5, tag, msg);
    }

    static void logE(String tag, String msg) {
        LogListener l = logListener;
        if (l != null) l.onLog(6, tag, msg);
        logAndroid(6, tag, msg);
    }

    public enum SessionState {
        ACTIVE,
        RESERVED,
        COMPLETED,
        CANCELLED
    }

    public static class PairingException extends Exception {
        public PairingException(String message) {
            super(message);
        }
    }

    public static class PairingCode {
        private final String code;
        public final long issuedAtEpochMs;
        public final long expiresAtEpochMs;
        public final byte[] salt;
        public final BigInteger verifier;
        private volatile SessionState state = SessionState.ACTIVE;

        public PairingCode(String code, long issuedAtEpochMs, long expiresAtEpochMs, byte[] salt, BigInteger verifier) {
            this.code = code;
            this.issuedAtEpochMs = issuedAtEpochMs;
            this.expiresAtEpochMs = expiresAtEpochMs;
            this.salt = salt;
            this.verifier = verifier;
        }

        public String getCode() {
            return code;
        }

        public long getIssuedAtEpochMs() {
            return issuedAtEpochMs;
        }

        public long getExpiresAtEpochMs() {
            return expiresAtEpochMs;
        }

        public SessionState getState() {
            return state;
        }

        public boolean isExpired(long nowEpochMs) {
            return nowEpochMs >= expiresAtEpochMs;
        }

        @Override
        public String toString() {
            return "PairingCode[expiresAt=" + expiresAtEpochMs + ", state=" + state + "]";
        }
    }

    private final RemoteDeviceStore devices;
    private final HostTailnet hostTailnet;
    private final TimeSource clock;
    private final SecureRandom random;

    private PairingCode activeCode = null;
    private int failureCount = 0;
    private final java.util.concurrent.atomic.AtomicInteger activeHandshakes = new java.util.concurrent.atomic.AtomicInteger(0);
    private int peakConcurrentHandshakes = 0;
    private String currentTailnetIp = null;

    private volatile boolean running = false;
    private DatagramSocket udpSocket = null;
    private ServerSocket tcpServerSocket = null;
    private Thread udpThread = null;
    private Thread tcpThread = null;

    private final Semaphore tcpSemaphore = new Semaphore(MAX_CONCURRENT_PAIRING);
    private final ThreadPoolExecutor tcpExecutor = new ThreadPoolExecutor(
            MAX_CONCURRENT_PAIRING,
            MAX_CONCURRENT_PAIRING,
            60L, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(),
            new ThreadFactory() {
                private int count = 0;
                @Override
                public synchronized Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "PCDash-Pairing-Worker-" + (++count));
                    t.setDaemon(true);
                    return t;
                }
            }
    );

    public PairingManager(RemoteDeviceStore devices, HostTailnet hostTailnet, TimeSource clock) {
        this(devices, hostTailnet, clock, new SecureRandom());
    }

    public PairingManager(RemoteDeviceStore devices, HostTailnet hostTailnet, TimeSource clock, SecureRandom random) {
        this.devices = devices;
        this.hostTailnet = hostTailnet;
        this.clock = clock != null ? clock : TimeSource.SYSTEM;
        this.random = random != null ? random : new SecureRandom();
    }

    public synchronized PairingCode begin() throws PairingException {
        String tailnetIp = hostTailnet != null ? hostTailnet.tailnetIp() : null;
        if (tailnetIp == null || tailnetIp.trim().isEmpty()) {
            throw new PairingException("Host chưa đăng nhập Tailscale, mở PCDash host và đăng nhập");
        }

        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            int idx = random.nextInt(CODE_ALPHABET.length());
            sb.append(CODE_ALPHABET.charAt(idx));
        }
        String code = sb.toString();

        byte[] salt = new byte[16];
        random.nextBytes(salt);

        String normalized = PairingProtocol.normalizeCode(code);
        BigInteger x = PairingProtocol.computeX(PairingProtocol.HASH_SHA256, PairingProtocol.PAIRING_IDENTITY, normalized, salt);
        BigInteger v = PairingProtocol.computeV(PairingProtocol.G, x, PairingProtocol.N_2048);

        long now = clock.nowEpochMs();
        this.activeCode = new PairingCode(code, now, now + CODE_TTL_MS, salt, v);
        this.failureCount = 0;
        this.currentTailnetIp = tailnetIp.trim();
        return this.activeCode;
    }

    public synchronized void cancel() {
        if (this.activeCode != null) {
            this.activeCode.state = SessionState.CANCELLED;
        }
        this.activeCode = null;
        this.failureCount = 0;
    }

    public synchronized boolean isActive(long nowEpochMs) {
        if (activeCode == null) return false;
        if (activeCode.state != SessionState.ACTIVE && activeCode.state != SessionState.RESERVED) return false;
        if (nowEpochMs >= activeCode.expiresAtEpochMs) return false;
        if (failureCount >= MAX_FAILURES) return false;
        return true;
    }
    public synchronized boolean isAvailableForPairing(long nowEpochMs) {
        if (activeCode == null) return false;
        if (activeCode.state != SessionState.ACTIVE) return false;
        if (nowEpochMs >= activeCode.expiresAtEpochMs) return false;
        if (failureCount >= MAX_FAILURES) return false;
        return true;
    }

    public synchronized boolean recordFailure() {
        failureCount++;
        if (failureCount >= MAX_FAILURES) {
            cancel();
            return true;
        }
        return false;
    }

    public synchronized int getFailureCount() {
        return failureCount;
    }

    public boolean isRunning() {
        return running;
    }

    public int getActiveHandshakes() {
        return activeHandshakes.get();
    }

    public synchronized int getPeakConcurrentHandshakes() {
        return peakConcurrentHandshakes;
    }


    public synchronized RemoteDeviceStore.RemoteDevice complete(String deviceName, String deviceToken, String nodeId) throws IOException {
        if (!isActive(clock.nowEpochMs()) || activeCode == null || (activeCode.state != SessionState.ACTIVE && activeCode.state != SessionState.RESERVED)) {
            throw new IOException("No active pairing session");
        }
        activeCode.state = SessionState.COMPLETED;
        logI(TAG, "Ghép đôi thành công: thiết bị=" + PairingProtocol.sanitizeDeviceName(deviceName) + ", nodeId=" + (nodeId != null ? nodeId.trim() : ""));
        RemoteDeviceStore.RemoteDevice dev = devices.add(deviceName, deviceToken, nodeId);
        cancel(); // Single use
        return dev;
    }

    public synchronized void start() throws IOException {
        start(DISCOVERY_PORT, PAIRING_PORT);
    }

    public synchronized void start(int udpPort, int tcpPort) throws IOException {
        if (running) return;
        running = true;

        udpSocket = new DatagramSocket(udpPort);
        tcpServerSocket = new ServerSocket(tcpPort);

        udpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                listenUdp();
            }
        }, "PCDash-Pairing-UDP");
        udpThread.setDaemon(true);
        udpThread.start();

        tcpThread = new Thread(new Runnable() {
            @Override
            public void run() {
                listenTcp();
            }
        }, "PCDash-Pairing-TCP");
        tcpThread.setDaemon(true);
        tcpThread.start();
    }

    public synchronized void close() {
        running = false;
        cancel();
        if (udpSocket != null) {
            try {
                udpSocket.close();
            } catch (Exception ignored) {}
            udpSocket = null;
        }
        if (tcpServerSocket != null) {
            try {
                tcpServerSocket.close();
            } catch (IOException ignored) {}
            tcpServerSocket = null;
        }
        if (udpThread != null) {
            udpThread.interrupt();
            udpThread = null;
        }
        if (tcpThread != null) {
            tcpThread.interrupt();
            tcpThread = null;
        }
        tcpExecutor.shutdownNow();
    }

    private void listenUdp() {
        byte[] buf = new byte[1024];
        while (running && udpSocket != null && !udpSocket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                udpSocket.receive(packet);

                InetAddress peer = packet.getAddress();
                if (!LanAddressPolicy.isAllowedPairingPeer(peer)) {
                    continue;
                }
                if (!isActive(clock.nowEpochMs())) {
                    continue;
                }

                String msg = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
                if (PairingMessages.DISCOVER_QUERY.equals(msg)) {
                    byte[] respBytes = PairingMessages.DISCOVER_RESPONSE.getBytes(StandardCharsets.UTF_8);
                    DatagramPacket respPacket = new DatagramPacket(respBytes, respBytes.length, peer, packet.getPort());
                    udpSocket.send(respPacket);
                }
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    private void listenTcp() {
        while (running && tcpServerSocket != null && !tcpServerSocket.isClosed()) {
            try {
                final Socket socket = tcpServerSocket.accept();
                if (!tcpSemaphore.tryAcquire()) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {}
                    continue;
                }
                try {
                    tcpExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                processTcpHandshake(socket);
                            } catch (Exception ignored) {
                            } finally {
                                tcpSemaphore.release();
                                try {
                                    socket.close();
                                } catch (IOException ignored) {}
                            }
                        }
                    });
                } catch (RejectedExecutionException rej) {
                    tcpSemaphore.release();
                    try {
                        socket.close();
                    } catch (IOException ignored) {}
                }
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    public void handleTcpSocket(final Socket socket) {
        if (!tcpSemaphore.tryAcquire()) {
            try {
                socket.close();
            } catch (IOException ignored) {}
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    processTcpHandshake(socket);
                } catch (Exception ignored) {
                } finally {
                    tcpSemaphore.release();
                    try {
                        socket.close();
                    } catch (IOException ignored) {}
                }
            }
        }, "PCDash-Pairing-Conn").start();
    }

    public void processTcpHandshake(Socket socket) throws Exception {
        int current = activeHandshakes.incrementAndGet();
        synchronized (this) {
            if (current > peakConcurrentHandshakes) {
                peakConcurrentHandshakes = current;
            }
        }
        try {
            long deadline = System.currentTimeMillis() + SESSION_DEADLINE_MS;
            updateSocketTimeout(socket, deadline);
        InetAddress peer = socket.getInetAddress();
        String peerIp = peer != null ? peer.getHostAddress() : "null";
        logI(TAG, "Kết nối pairing đến từ IP: " + peerIp);
        if (!LanAddressPolicy.isAllowedPairingPeer(peer)) {
            logW(TAG, "Kết nối pairing bị từ chối: IP nguồn không thuộc dải RFC1918 (" + peerIp + ")");
            return;
        }
        byte[] salt;
        BigInteger v;
        String tailnetIp;
        synchronized (this) {
            boolean available = isAvailableForPairing(clock.nowEpochMs());
            logI(TAG, "Kiểm tra mã ghép đôi: " + (available ? "mã đang mở" : "không có mã mở hoặc mã không khả dụng"));
            if (!available) {
                if (activeCode == null) {
                    logW(TAG, "Kết nối pairing bị từ chối: không có mã mở");
                } else if (activeCode.isExpired(clock.nowEpochMs())) {
                    logW(TAG, "Kết nối pairing bị từ chối: mã đã hết hạn");
                } else if (failureCount >= MAX_FAILURES) {
                    logW(TAG, "Kết nối pairing bị từ chối: đã vượt quá số lần thử sai tối đa (" + failureCount + "/" + MAX_FAILURES + ")");
                } else {
                    logW(TAG, "Kết nối pairing bị từ chối: phiên không khả dụng");
                }
                return;
            }
            salt = activeCode.salt;
            v = activeCode.verifier;
            tailnetIp = currentTailnetIp;
        }

        DataInputStream dis = new DataInputStream(socket.getInputStream());
        DataOutputStream dos = new DataOutputStream(socket.getOutputStream());

        // 1. Read ClientHello
        updateSocketTimeout(socket, deadline);
        PairingMessages.ClientHello clientHello;
        try {
            clientHello = PairingMessages.ClientHello.read(dis);
        } catch (Exception e) {
            return;
        }

        // Validate version
        if (!PairingMessages.PROTOCOL_VERSION.equals(clientHello.version)) {
            return;
        }

        // Sanitize device name (max 64 bytes UTF-8)
        String deviceName = PairingProtocol.sanitizeDeviceName(clientHello.deviceName);

        // Validate client A
        BigInteger N = PairingProtocol.N_2048;
        BigInteger g = PairingProtocol.G;
        String hash = PairingProtocol.HASH_SHA256;

        BigInteger clientA = PairingProtocol.fromBytes(clientHello.clientA);
        try {
            PairingProtocol.validateA(clientA, N);
        } catch (IllegalArgumentException e) {
            return;
        }

        // 2. Generate server ephemeral b and B
        BigInteger k = PairingProtocol.computeK(hash, N, g);
        BigInteger b = PairingProtocol.randomExponent(random, N);
        BigInteger serverB = PairingProtocol.computeServerB(k, v, g, b, N);

        // Send ServerHello
        PairingMessages.ServerHello serverHello = new PairingMessages.ServerHello(
                PairingMessages.PROTOCOL_VERSION,
                salt,
                PairingProtocol.pad(serverB, N)
        );
        serverHello.write(dos);
        dos.flush();
        logI(TAG, "Đã gửi ServerHello");

        // 3. Compute SRP scrambling parameter u and S
        BigInteger u;
        try {
            u = PairingProtocol.computeU(hash, N, clientA, serverB);
        } catch (IllegalArgumentException e) {
            return;
        }

        BigInteger serverS = PairingProtocol.computeServerS(clientA, v, u, b, N);
        byte[] sessionKeyK = PairingProtocol.deriveSessionKeyK(hash, serverS, N);
        byte[] expectedM1 = PairingProtocol.computeM1(hash, N, g, PairingProtocol.PAIRING_IDENTITY, salt, clientA, serverB, sessionKeyK);

        // 4. Read client M1 (32 bytes)
        updateSocketTimeout(socket, deadline);
        byte[] clientM1 = new byte[32];
        try {
            dis.readFully(clientM1);
        } catch (Exception e) {
            recordFailure();
            logW(TAG, "Xác thực M1 thất bại (lỗi đọc dữ liệu M1, lần " + getFailureCount() + "/" + MAX_FAILURES + ")");
            return;
        }

        // Verify M1
        if (!MessageDigest.isEqual(clientM1, expectedM1)) {
            recordFailure();
            logW(TAG, "Xác thực M1 thất bại (lần " + getFailureCount() + "/" + MAX_FAILURES + ")");
            // Disconnect immediately, send nothing further
            return;
        }
        logI(TAG, "Xác thực M1 đúng");

        // 5. M1 is valid: transition session to RESERVED under lock
        synchronized (this) {
            if (!isActive(clock.nowEpochMs()) || activeCode == null) {
                return;
            }
            activeCode.state = SessionState.RESERVED;
        }

        // Check before creating auth key
        synchronized (this) {
            if (!isActive(clock.nowEpochMs()) || activeCode == null || activeCode.state != SessionState.RESERVED) {
                return;
            }
        }

        // 6. Generate device token
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String deviceToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        // Check before sending credential
        synchronized (this) {
            if (!isActive(clock.nowEpochMs()) || activeCode == null || activeCode.state != SessionState.RESERVED) {
                return;
            }
        }

        // 7. Compute transcript and derive AES key
        byte[] transcript = PairingMessages.computeTranscript(
                PairingMessages.PROTOCOL_VERSION,
                deviceName,
                PairingProtocol.pad(clientA, N),
                PairingProtocol.pad(serverB, N),
                salt
        );
        byte[] aesKey = PairingProtocol.deriveAesKey(sessionKeyK);
        byte[] m2 = PairingProtocol.computeM2(hash, N, clientA, expectedM1, sessionKeyK);

        PairingMessages.Credentials creds = new PairingMessages.Credentials(deviceToken, tailnetIp, 8091);
        String credsJson = creds.toJson();
        PairingMessages.EncryptedRecord credsRecord = PairingProtocol.seal(
                aesKey,
                transcript,
                credsJson.getBytes(StandardCharsets.UTF_8)
        );

        // Send M2 + credsRecord
        dos.write(m2);
        credsRecord.write(dos);
        dos.flush();

        // 8. Read ACK record containing nodeId
        updateSocketTimeout(socket, deadline);
        PairingMessages.EncryptedRecord ackRecord;
        byte[] ackPlaintext;
        try {
            ackRecord = PairingMessages.EncryptedRecord.read(dis);
            logI(TAG, "Đã nhận gói tin ACK từ client");
        } catch (Exception e) {
            logW(TAG, "Lỗi đọc gói tin ACK từ client: " + e.getMessage());
            return;
        }

        try {
            ackPlaintext = PairingProtocol.open(aesKey, transcript, ackRecord);
        } catch (Exception e) {
            logW(TAG, "Không thể giải mã gói tin ACK từ client");
            sendResultRecord(dos, aesKey, transcript, PairingMessages.PairingResult.error("ACK_DECRYPT_FAILED", "Không thể giải mã ACK"));
            return;
        }

        String ackJson = new String(ackPlaintext, StandardCharsets.UTF_8);
        String nodeId = extractField(ackJson, "nodeId");
        if (nodeId == null) {
            nodeId = "";
        }
        nodeId = nodeId.trim();
        logI(TAG, "Nhận nodeId=" + nodeId + " từ ACK");

        // Check before complete
        synchronized (this) {
            if (!isActive(clock.nowEpochMs()) || activeCode == null || activeCode.state != SessionState.RESERVED) {
                logW(TAG, "Phiên ghép đôi không còn hợp lệ hoặc đã hết hạn trước khi lưu thiết bị");
                sendResultRecord(dos, aesKey, transcript, PairingMessages.PairingResult.error(PairingMessages.ERR_STORE_ERROR, "Phiên ghép đôi đã hết hạn"));
                return;
            }
            try {
                complete(deviceName, deviceToken, nodeId);
                logI(TAG, "Kết quả complete/store: lưu thiết bị thành công (deviceName=" + deviceName + ", nodeId=" + nodeId + ")");
            } catch (Exception e) {
                logE(TAG, "Kết quả complete/store: lưu thiết bị thất bại vào store: " + e.getMessage());
                sendResultRecord(dos, aesKey, transcript, PairingMessages.PairingResult.error(PairingMessages.ERR_STORE_ERROR, "Lỗi lưu store: " + e.getMessage()));
                return;
            }
        }

        // Send final SUCCESS result record
        sendResultRecord(dos, aesKey, transcript, PairingMessages.PairingResult.ok());
        logI(TAG, "Đã gửi xác nhận kết quả cuối (status: ok) cho client");
        } finally {
            activeHandshakes.decrementAndGet();
        }
    }

    private void sendResultRecord(DataOutputStream dos, byte[] aesKey, byte[] transcript, PairingMessages.PairingResult result) {
        try {
            byte[] plaintext = result.toJson().getBytes(StandardCharsets.UTF_8);
            PairingMessages.EncryptedRecord record = PairingProtocol.seal(aesKey, transcript, plaintext);
            record.write(dos);
            dos.flush();
        } catch (Exception e) {
            logW(TAG, "Không thể gửi bản ghi kết quả ghép đôi: " + e.getMessage());
        }
    }


    private void updateSocketTimeout(Socket socket, long deadlineEpochMs) throws IOException {
        long remaining = deadlineEpochMs - System.currentTimeMillis();
        if (remaining <= 0) {
            throw new SocketTimeoutException("Pairing session deadline exceeded");
        }
        socket.setSoTimeout((int) Math.min(remaining, SESSION_DEADLINE_MS));
    }

    private static String extractField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private static String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
