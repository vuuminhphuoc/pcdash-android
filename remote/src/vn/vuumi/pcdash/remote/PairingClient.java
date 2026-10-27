package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.i18n.L10n;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import vn.vuumi.pcdash.net.PcnetBridge;
import vn.vuumi.pcdash.pairing.PairingMessages;
import vn.vuumi.pcdash.pairing.PairingProtocol;

public class PairingClient {

    public static final int DEFAULT_DISCOVERY_PORT = 52191;
    public static final int DEFAULT_PAIRING_PORT = 52192;
    public static final int SOCKET_TIMEOUT_MS = 15000;

    public static final String TAG = "PcdashPair";

    public enum Stage {
        DISCOVERY,
        CONNECT,
        HELLO,
        PROOF_REJECTED,
        SERVER_PROOF_INVALID,
        CREDENTIALS,
        TSNET_LOGIN,
        ACK
    }

    public static class PairingException extends Exception {
        public final Stage stage;
        public final String errorCode;

        public PairingException(Stage stage, String message) {
            this(stage, null, message, null);
        }

        public PairingException(Stage stage, String errorCode, String message) {
            this(stage, errorCode, message, null);
        }

        public PairingException(Stage stage, String message, Throwable cause) {
            this(stage, null, message, cause);
        }

        public PairingException(Stage stage, String errorCode, String message, Throwable cause) {
            super(message, cause);
            this.stage = stage;
            this.errorCode = errorCode;
        }

        public Stage getStage() {
            return stage;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }

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
            synchronized (PairingClient.class) {
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

    public static String sanitizeSecret(String input, String code) {
        return sanitizeSecret(input, null, code);
    }

    public static String sanitizeSecret(String input, String authKey, String code) {
        if (input == null) return "";
        String s = input;
        if (authKey != null && !authKey.isEmpty()) {
            s = s.replace(authKey, "[REDACTED_KEY]");
        }
        if (code != null && !code.trim().isEmpty()) {
            s = s.replace(code.trim(), "[REDACTED_CODE]");
            s = s.replace(code.trim().toUpperCase(), "[REDACTED_CODE]");
            s = s.replace(code.trim().toLowerCase(), "[REDACTED_CODE]");
        }
        s = s.replaceAll("tskey-auth-[a-zA-Z0-9_-]+", "[REDACTED_KEY]");
        s = s.replaceAll("tskey-[a-zA-Z0-9_-]+", "[REDACTED_KEY]");
        s = s.replaceAll("(?i)bearer\\s+[a-zA-Z0-9_.-]+", "Bearer [REDACTED_TOKEN]");
        return s;
    }

    private final int targetPairingPort;

    public PairingClient() {
        this(DEFAULT_PAIRING_PORT);
    }

    public PairingClient(int targetPairingPort) {
        this.targetPairingPort = targetPairingPort;
    }

    public static boolean isRfc1918(InetAddress address) {
        if (address == null || !(address instanceof Inet4Address)) return false;
        byte[] bytes = address.getAddress();
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;
        if (b0 == 10) return true;
        if (b0 == 172 && b1 >= 16 && b1 <= 31) return true;
        if (b0 == 192 && b1 == 168) return true;
        return false;
    }

    public List<InetAddress> discover(long timeoutMs) {
        List<InetAddress> results = new ArrayList<InetAddress>();
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout((int) Math.min(timeoutMs, 5000L));

            byte[] queryBytes = PairingMessages.DISCOVER_QUERY.getBytes(StandardCharsets.UTF_8);
            DatagramPacket sendPacket = new DatagramPacket(
                    queryBytes,
                    queryBytes.length,
                    InetAddress.getByName("255.255.255.255"),
                    DEFAULT_DISCOVERY_PORT
            );
            socket.send(sendPacket);

            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[1024];

            while (System.currentTimeMillis() < deadline) {
                int remaining = (int) (deadline - System.currentTimeMillis());
                if (remaining <= 0) break;
                socket.setSoTimeout(Math.min(remaining, 1000));
                DatagramPacket recvPacket = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(recvPacket);
                    String msg = new String(recvPacket.getData(), recvPacket.getOffset(), recvPacket.getLength(), StandardCharsets.UTF_8).trim();
                    if (PairingMessages.DISCOVER_RESPONSE.equals(msg)) {
                        InetAddress peer = recvPacket.getAddress();
                        if (isRfc1918(peer) && !results.contains(peer)) {
                            results.add(peer);
                        }
                    }
                } catch (IOException e) {
                    // Socket timeout, continue until deadline
                }
            }
        } catch (Exception e) {
            logW(TAG, "[DISCOVERY] " + e.getClass().getSimpleName() + ": " + sanitizeSecret(e.getMessage(), null, null));
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {}
            }
        }
        return results;
    }

    public RemoteConfigStore.RemoteConfig pair(InetAddress host, String code, String deviceName, PcnetBridge bridge) throws PairingException {
        return pair(host, code, deviceName, null, bridge);
    }

    public RemoteConfigStore.RemoteConfig pair(InetAddress host, String code, String deviceName, File stateDir, PcnetBridge bridge) throws PairingException {
        if (host == null) {
            throw new IllegalArgumentException("Host address cannot be null");
        }
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("Pairing code cannot be empty");
        }
        if (bridge == null) {
            throw new IllegalArgumentException("PcnetBridge cannot be null");
        }

        Socket socket = new Socket();
        try {
            logI(TAG, "[CONNECT] Bắt đầu kết nối tới host " + host.getHostAddress() + ":" + targetPairingPort);
            try {
                socket.connect(new InetSocketAddress(host, targetPairingPort), SOCKET_TIMEOUT_MS);
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            } catch (SocketTimeoutException ste) {
                throw new PairingException(Stage.CONNECT, L10n.t("Cannot reach the host (timed out, check the IP and Wi-Fi)", "Không kết nối được tới host (hết thời gian chờ, kiểm tra IP và Wi-Fi)"), ste);
            } catch (ConnectException ce) {
                throw new PairingException(Stage.CONNECT, L10n.t("Cannot reach the host (connection refused, check the IP and Wi-Fi)", "Không kết nối được tới host (kết nối bị từ chối, kiểm tra IP và Wi-Fi)"), ce);
            } catch (IOException ioe) {
                throw new PairingException(Stage.CONNECT, L10n.t("Cannot reach the host (check the IP and Wi-Fi)", "Không kết nối được tới host (kiểm tra IP và Wi-Fi)"), ioe);
            }
            logI(TAG, "[CONNECT] Đã kết nối TCP tới host thành công");

            DataInputStream dis = new DataInputStream(socket.getInputStream());
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());

            BigInteger N = PairingProtocol.N_2048;
            BigInteger g = PairingProtocol.G;
            String hash = PairingProtocol.HASH_SHA256;
            SecureRandom random = new SecureRandom();

            // 1. Send ClientHello & 2. Read ServerHello
            logI(TAG, "[HELLO] Bắt đầu trao đổi Hello");
            BigInteger a = PairingProtocol.randomExponent(random, N);
            BigInteger clientA = PairingProtocol.computeClientA(g, a, N);
            byte[] clientABytes = PairingProtocol.pad(clientA, N);
            String sanitizedDeviceName = PairingProtocol.sanitizeDeviceName(deviceName);

            PairingMessages.ClientHello clientHello = new PairingMessages.ClientHello(
                    PairingMessages.PROTOCOL_VERSION,
                    sanitizedDeviceName,
                    clientABytes
            );

            PairingMessages.ServerHello serverHello;
            try {
                clientHello.write(dos);
                dos.flush();
                serverHello = PairingMessages.ServerHello.read(dis);
            } catch (EOFException eof) {
                throw new PairingException(Stage.HELLO, L10n.t("Invalid reply from the host (disconnected during Hello)", "Host trả lời không hợp lệ (ngắt kết nối khi bắt tay Hello)"), eof);
            } catch (SocketTimeoutException ste) {
                throw new PairingException(Stage.HELLO, L10n.t("The host answered too slowly during Hello", "Host phản hồi quá chậm khi trao đổi Hello"), ste);
            } catch (Exception e) {
                throw new PairingException(Stage.HELLO, L10n.t("Invalid reply from the host", "Host trả lời không hợp lệ"), e);
            }

            if (!PairingMessages.PROTOCOL_VERSION.equals(serverHello.version)) {
                throw new PairingException(Stage.HELLO, L10n.t("Invalid reply from the host (protocol version mismatch)", "Host trả lời không hợp lệ (phiên bản giao thức không khớp)"));
            }
            byte[] salt = serverHello.salt;
            BigInteger serverB;
            try {
                serverB = PairingProtocol.fromBytes(serverHello.serverB);
                PairingProtocol.validateB(serverB, N);
            } catch (Exception e) {
                throw new PairingException(Stage.HELLO, L10n.t("Invalid reply from the host", "Host trả lời không hợp lệ"), e);
            }
            logI(TAG, "[HELLO] Trao đổi Hello thành công");

            // 3. Compute SRP values
            String normalizedCode = PairingProtocol.normalizeCode(code);
            BigInteger x = PairingProtocol.computeX(hash, PairingProtocol.PAIRING_IDENTITY, normalizedCode, salt);
            BigInteger k = PairingProtocol.computeK(hash, N, g);
            BigInteger u = PairingProtocol.computeU(hash, N, clientA, serverB);
            BigInteger clientS = PairingProtocol.computeClientS(serverB, k, g, x, a, u, N);
            byte[] sessionKeyK = PairingProtocol.deriveSessionKeyK(hash, clientS, N);

            byte[] clientM1 = PairingProtocol.computeM1(hash, N, g, PairingProtocol.PAIRING_IDENTITY, salt, clientA, serverB, sessionKeyK);

            // 4. Send client M1
            logI(TAG, "[PROOF] Gửi client M1");
            try {
                dos.write(clientM1);
                dos.flush();
            } catch (Exception e) {
                throw new PairingException(Stage.PROOF_REJECTED, L10n.t("The host refused: wrong or expired code. Tap Add device on the host for a new code", "Host từ chối: mã sai hoặc đã hết hạn — bấm Thêm thiết bị để lấy mã mới"), e);
            }

            // 5. Read M2
            byte[] receivedM2 = new byte[32];
            try {
                dis.readFully(receivedM2);
            } catch (EOFException eof) {
                throw new PairingException(Stage.PROOF_REJECTED, L10n.t("The host refused: wrong or expired code. Tap Add device on the host for a new code", "Host từ chối: mã sai hoặc đã hết hạn — bấm Thêm thiết bị để lấy mã mới"), eof);
            } catch (SocketTimeoutException ste) {
                throw new PairingException(Stage.PROOF_REJECTED, L10n.t("The host refused: wrong or expired code. Tap Add device on the host for a new code", "Host từ chối: mã sai hoặc đã hết hạn — bấm Thêm thiết bị để lấy mã mới"), ste);
            } catch (IOException ioe) {
                throw new PairingException(Stage.PROOF_REJECTED, L10n.t("The host refused: wrong or expired code. Tap Add device on the host for a new code", "Host từ chối: mã sai hoặc đã hết hạn — bấm Thêm thiết bị để lấy mã mới"), ioe);
            }

            byte[] expectedM2 = PairingProtocol.computeM2(hash, N, clientA, clientM1, sessionKeyK);
            if (!MessageDigest.isEqual(receivedM2, expectedM2)) {
                throw new PairingException(Stage.SERVER_PROOF_INVALID, L10n.t("Invalid reply from the host (server proof failed)", "Host trả lời không hợp lệ (xác thực máy chủ thất bại)"));
            }
            logI(TAG, "[PROOF] Xác thực máy chủ thành công");

            // 6. Decrypt credentials record
            logI(TAG, "[CREDENTIALS] Nhận thông tin đăng nhập");
            byte[] transcript = PairingMessages.computeTranscript(
                    PairingMessages.PROTOCOL_VERSION,
                    sanitizedDeviceName,
                    clientABytes,
                    PairingProtocol.pad(serverB, N),
                    salt
            );
            byte[] aesKey = PairingProtocol.deriveAesKey(sessionKeyK);

            String deviceToken;
            String hostTailnetIp;
            int hostPort;
            try {
                PairingMessages.EncryptedRecord credsRecord = PairingMessages.EncryptedRecord.read(dis);
                byte[] credsPlaintext = PairingProtocol.open(aesKey, transcript, credsRecord);
                String credsJson = new String(credsPlaintext, StandardCharsets.UTF_8);

                PairingMessages.Credentials credentials;
                try {
                    credentials = PairingMessages.Credentials.fromJson(credsJson);
                } catch (IllegalArgumentException iae) {
                    throw new PairingException(Stage.CREDENTIALS, L10n.t("Invalid reply from the host: ", "Host trả lời không hợp lệ: ") + iae.getMessage(), iae);
                }
                deviceToken = credentials.deviceToken;
                hostTailnetIp = credentials.hostTailnetIp;
                hostPort = credentials.hostPort;
            } catch (PairingException pe) {
                throw pe;
            } catch (Exception e) {
                throw new PairingException(Stage.CREDENTIALS, L10n.t("Invalid reply from the host (could not read the credentials)", "Host trả lời không hợp lệ (lỗi nhận thông tin xác thực)"), e);
            }
            logI(TAG, "[CREDENTIALS] Nhận và giải mã thông tin kết nối thành công");

            // 7. Get Tailscale node ID from bridge
            logI(TAG, "[NODE_ID] Lấy thông tin Tailscale node ID");
            String nodeId = bridge != null ? bridge.nodeId() : null;
            if (nodeId == null || nodeId.trim().isEmpty()) {
                throw new PairingException(Stage.TSNET_LOGIN, L10n.t("Tailscale sign-in failed: no Tailscale node ID", "Đăng nhập Tailscale thất bại: Không nhận được Tailscale node ID"));
            }
            nodeId = nodeId.trim();
            // 8. Send ACK record containing nodeId
            logI(TAG, "[ACK] Gửi gói tin ACK");
            try {
                String ackJson = "{\"nodeId\":\"" + escapeJson(nodeId.trim()) + "\"}";
                PairingMessages.EncryptedRecord ackRecord = PairingProtocol.seal(
                        aesKey,
                        transcript,
                        ackJson.getBytes(StandardCharsets.UTF_8)
                );
                ackRecord.write(dos);
                dos.flush();
            } catch (Exception e) {
                throw new PairingException(Stage.ACK, L10n.t("The host disconnected while finishing the handshake (ACK not sent)", "Host ngắt kết nối khi hoàn tất bắt tay (lỗi gửi ACK)"), e);
            }

            // 9. Receive final result record from host
            logI(TAG, "[ACK] Đợi kết quả xác nhận cuối cùng từ host");
            PairingMessages.EncryptedRecord resultRecord;
            try {
                resultRecord = PairingMessages.EncryptedRecord.read(dis);
            } catch (Exception e) {
                throw new PairingException(Stage.ACK, L10n.t("No result from the host after the ACK: ", "Host không phản hồi kết quả sau ACK: ") + e.getMessage(), e);
            }

            byte[] resultPlaintext;
            try {
                resultPlaintext = PairingProtocol.open(aesKey, transcript, resultRecord);
            } catch (Exception e) {
                throw new PairingException(Stage.ACK, L10n.t("Cannot decrypt the result from the host", "Không thể giải mã bản ghi kết quả từ host"), e);
            }

            String resultJson = new String(resultPlaintext, StandardCharsets.UTF_8);
            PairingMessages.PairingResult result = PairingMessages.PairingResult.fromJson(resultJson);
            if (!result.isOk()) {
                String codeStr = result.code != null ? result.code : "UNKNOWN";
                String hostMsgText = hostErrorMessage(codeStr, result.message);
                logE(TAG, "[ACK] Host từ chối ghép đôi: mã=" + codeStr + ", thông điệp=" + hostMsgText);
                throw new PairingException(Stage.ACK, codeStr, hostMsgText);
            }

            logI(TAG, "[ACK] Xác nhận ACK hoàn tất, host xác nhận status: ok, ghép đôi thành công");
            return new RemoteConfigStore.RemoteConfig(deviceToken, hostTailnetIp, hostPort);
        } catch (PairingException pe) {
            logE(TAG, "[" + pe.getStage().name() + "] " + pe.getClass().getSimpleName() + ": " + sanitizeSecret(pe.getMessage(), code));
            throw pe;
        } catch (Exception e) {
            PairingException pe = new PairingException(Stage.CONNECT, L10n.t("Cannot reach the host (check the IP and Wi-Fi)", "Không kết nối được tới host (kiểm tra IP và Wi-Fi)"), e);
            logE(TAG, "[" + pe.getStage().name() + "] " + pe.getClass().getSimpleName() + ": " + sanitizeSecret(pe.getMessage(), code));
            throw pe;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    static String hostErrorMessage(String code, String hostMsg) {
        if (PairingMessages.ERR_ACK_NODE_ID_MISSING.equals(code)) {
            return L10n.t("Pairing failed: the host did not get this device's Tailscale node ID (ACK_NODE_ID_MISSING).", "Ghép đôi thất bại: Host không nhận được thông tin Tailscale node ID từ thiết bị (ACK_NODE_ID_MISSING).");
        }
        if (PairingMessages.ERR_NODE_NOT_FOUND.equals(code)) {
            return L10n.t("Pairing failed: the host cannot find this device in the tailnet (NODE_NOT_FOUND).", "Ghép đôi thất bại: Host không tìm thấy node thiết bị trên mạng tailnet (NODE_NOT_FOUND).");
        }
        if (PairingMessages.ERR_NODE_TAG_MISMATCH.equals(code)) {
            return L10n.t("Pairing failed: this device has no valid controller tag (tag:pcdash-controller missing).", "Ghép đôi thất bại: Node thiết bị không có tag quyền điều khiển hợp lệ (thiếu tag:pcdash-controller).");
        }
        if (PairingMessages.ERR_API_ERROR.equals(code)) {
            return L10n.t("Pairing failed: the host got an error from the Tailscale API while checking this device (API_ERROR).", "Ghép đôi thất bại: Host gặp lỗi khi gọi API Tailscale để xác thực thiết bị (API_ERROR).");
        }
        if (PairingMessages.ERR_STORE_ERROR.equals(code)) {
            return L10n.t("Pairing failed: the host could not save this device (STORE_ERROR).", "Ghép đôi thất bại: Host không thể lưu thông tin thiết bị (STORE_ERROR).");
        }
        return L10n.t("Pairing failed on the host: ", "Ghép đôi thất bại từ phía host: ") + (code != null ? code : L10n.t("Unknown error", "Lỗi không xác định"))
                + (hostMsg != null && !hostMsg.isEmpty() ? " (" + hostMsg + ")" : "");
    }

    static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private static String extractString(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    private static int extractInt(String json, String key, int defaultVal) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return defaultVal;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return defaultVal;
        int end = colon + 1;
        while (end < json.length() && (Character.isWhitespace(json.charAt(end)) || Character.isDigit(json.charAt(end)))) {
            end++;
        }
        String numStr = json.substring(colon + 1, end).trim();
        try {
            return Integer.parseInt(numStr);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
