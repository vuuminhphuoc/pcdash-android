package vn.vuumi.pcdash.pairing;

import vn.vuumi.pcdash.i18n.L10n;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class PairingMessages {

    public static final String PROTOCOL_VERSION = "2";
    public static final String DISCOVER_QUERY = "PCDASH_DISCOVER_V1";
    public static final String DISCOVER_RESPONSE = "PCDASH_HOST_V1";

    public static final String STATUS_OK = "ok";
    public static final String STATUS_ERROR = "error";

    public static final String ERR_ACK_NODE_ID_MISSING = "ACK_NODE_ID_MISSING";
    public static final String ERR_NODE_NOT_FOUND = "NODE_NOT_FOUND";
    public static final String ERR_NODE_TAG_MISMATCH = "NODE_TAG_MISMATCH";
    public static final String ERR_API_ERROR = "API_ERROR";
    public static final String ERR_STORE_ERROR = "STORE_ERROR";
    public static final String ERR_VERSION = "version";
    public static String versionMismatchMessage() {
        return L10n.t("Update PCDash host/remote", "Cần cập nhật PCDash host/remote");
    }

    public static class PairingResult {
        public final String status;
        public final String code;
        public final String message;

        public PairingResult(String status, String code, String message) {
            this.status = status;
            this.code = code;
            this.message = message;
        }

        public boolean isOk() {
            return STATUS_OK.equals(status);
        }

        public static PairingResult ok() {
            return new PairingResult(STATUS_OK, null, null);
        }

        public static PairingResult error(String code, String message) {
            return new PairingResult(STATUS_ERROR, code, message);
        }

        public String toJson() {
            if (isOk()) {
                return "{\"status\":\"ok\"}";
            }
            return "{\"status\":\"error\",\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message) + "\"}";
        }

        public static PairingResult fromJson(String json) {
            if (json == null) return error(ERR_API_ERROR, "Empty response");
            String status = extract(json, "status");
            if (STATUS_OK.equals(status)) {
                return ok();
            }
            String code = extract(json, "code");
            String message = extract(json, "message");
            return error(code != null ? code : "UNKNOWN", message != null ? message : "");
        }

        private static String escape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private static String extract(String json, String key) {
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
    }

    public static class Credentials {
        public final String deviceToken;
        public final String hostTailnetIp;
        public final int hostPort;

        public Credentials(String deviceToken, String hostTailnetIp, int hostPort) {
            this.deviceToken = deviceToken;
            this.hostTailnetIp = hostTailnetIp;
            this.hostPort = hostPort;
        }

        public String toJson() {
            return "{\"deviceToken\":\"" + escape(deviceToken)
                    + "\",\"hostTailnetIp\":\"" + escape(hostTailnetIp)
                    + "\",\"hostPort\":" + hostPort + "}";
        }

        public static Credentials fromJson(String json) {
            if (json == null) {
                throw new IllegalArgumentException("Credentials JSON cannot be null");
            }
            if (json.contains("\"authKey\"")) {
                throw new IllegalArgumentException("Legacy credentials with authKey are rejected");
            }
            String token = extract(json, "deviceToken");
            String ip = extract(json, "hostTailnetIp");
            if (token == null || ip == null) {
                throw new IllegalArgumentException("Missing deviceToken or hostTailnetIp");
            }
            int port = 8091;
            int idx = json.indexOf("\"hostPort\"");
            if (idx >= 0) {
                int colon = json.indexOf(':', idx);
                if (colon >= 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = colon + 1; i < json.length(); i++) {
                        char c = json.charAt(i);
                        if (Character.isDigit(c)) {
                            sb.append(c);
                        } else if (sb.length() > 0) {
                            break;
                        }
                    }
                    if (sb.length() > 0) {
                        try {
                            port = Integer.parseInt(sb.toString());
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            return new Credentials(token, ip, port);
        }
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String extract(String json, String key) {
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
    public static class EncryptedRecord {
        public final byte[] nonce;
        public final byte[] ciphertext;

        public EncryptedRecord(byte[] nonce, byte[] ciphertext) {
            if (nonce == null || nonce.length != 12) {
                throw new IllegalArgumentException("Nonce must be 12 bytes");
            }
            if (ciphertext == null) {
                throw new IllegalArgumentException("Ciphertext cannot be null");
            }
            this.nonce = nonce;
            this.ciphertext = ciphertext;
        }

        public byte[] toBytes() {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                write(dos);
                dos.flush();
                return baos.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException("Serialization error", e);
            }
        }

        public void write(DataOutputStream out) throws IOException {
            out.writeByte(nonce.length);
            out.write(nonce);
            out.writeInt(ciphertext.length);
            out.write(ciphertext);
        }

        public static EncryptedRecord fromBytes(byte[] bytes) throws IOException {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
            return read(dis);
        }

        public static EncryptedRecord read(DataInputStream in) throws IOException {
            int nonceLen = in.readUnsignedByte();
            if (nonceLen != 12) {
                throw new IOException("Invalid nonce length: " + nonceLen);
            }
            byte[] nonce = new byte[nonceLen];
            in.readFully(nonce);

            int ctLen = in.readInt();
            if (ctLen < 0 || ctLen > 65536) {
                throw new IOException("Invalid ciphertext length: " + ctLen);
            }
            byte[] ciphertext = new byte[ctLen];
            in.readFully(ciphertext);

            return new EncryptedRecord(nonce, ciphertext);
        }
    }

    public static class ClientHello {
        public final String version;
        public final String deviceName;
        public final byte[] clientA;

        public ClientHello(String version, String deviceName, byte[] clientA) {
            this.version = version;
            this.deviceName = deviceName != null ? deviceName : "Remote";
            this.clientA = clientA;
        }

        public byte[] toBytes() {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                write(dos);
                dos.flush();
                return baos.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException("Serialization error", e);
            }
        }

        public void write(DataOutputStream out) throws IOException {
            out.writeUTF(version);
            out.writeUTF(deviceName);
            out.writeShort(clientA.length);
            out.write(clientA);
        }

        public static ClientHello fromBytes(byte[] bytes) throws IOException {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
            return read(dis);
        }

        public static ClientHello read(DataInputStream in) throws IOException {
            String ver = in.readUTF();
            String name = in.readUTF();
            int keyLen = in.readUnsignedShort();
            if (keyLen <= 0 || keyLen > 512) {
                throw new IOException("Invalid client public value length: " + keyLen);
            }
            byte[] key = new byte[keyLen];
            in.readFully(key);
            return new ClientHello(ver, name, key);
        }
    }

    public static class ServerHello {
        public final String version;
        public final byte[] salt;
        public final byte[] serverB;

        public ServerHello(String version, byte[] salt, byte[] serverB) {
            this.version = version;
            this.salt = salt;
            this.serverB = serverB;
        }

        public byte[] toBytes() {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                write(dos);
                dos.flush();
                return baos.toByteArray();
            } catch (IOException e) {
                throw new RuntimeException("Serialization error", e);
            }
        }

        public void write(DataOutputStream out) throws IOException {
            out.writeUTF(version);
            out.writeByte(salt.length);
            out.write(salt);
            out.writeShort(serverB.length);
            out.write(serverB);
        }

        public static ServerHello fromBytes(byte[] bytes) throws IOException {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
            return read(dis);
        }

        public static ServerHello read(DataInputStream in) throws IOException {
            String ver = in.readUTF();
            int saltLen = in.readUnsignedByte();
            if (saltLen != 16) {
                throw new IOException("Invalid salt length: " + saltLen);
            }
            byte[] salt = new byte[saltLen];
            in.readFully(salt);

            int keyLen = in.readUnsignedShort();
            if (keyLen <= 0 || keyLen > 512) {
                throw new IOException("Invalid server public value length: " + keyLen);
            }
            byte[] key = new byte[keyLen];
            in.readFully(key);
            return new ServerHello(ver, salt, key);
        }
    }

    public static byte[] computeTranscript(String version, String deviceName, byte[] clientA, byte[] serverB, byte[] salt) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeUTF(version != null ? version : "");
            dos.writeUTF(deviceName != null ? deviceName : "");
            dos.writeShort(clientA != null ? clientA.length : 0);
            if (clientA != null) dos.write(clientA);
            dos.writeShort(serverB != null ? serverB.length : 0);
            if (serverB != null) dos.write(serverB);
            dos.writeShort(salt != null ? salt.length : 0);
            if (salt != null) dos.write(salt);
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Transcript computation failed", e);
        }
    }
}
