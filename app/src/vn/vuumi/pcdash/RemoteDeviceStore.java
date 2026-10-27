package vn.vuumi.pcdash;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RemoteDeviceStore {

    public static class RemoteDevice {
        public final String id;
        public final String name;
        public final String tokenHash;
        public final String nodeId;
        public final long createdAt;
        public final long createdAtMs;
        public final long lastUsedAt;
        public final long lastUsedAtMs;

        public RemoteDevice(String id, String name, String tokenHash, String nodeId, long createdAt) {
            this(id, name, tokenHash, nodeId, createdAt, 0L);
        }

        public RemoteDevice(String id, String name, String tokenHash, String nodeId, long createdAt, long lastUsedAt) {
            this.id = id;
            this.name = name;
            this.tokenHash = tokenHash;
            this.nodeId = nodeId;
            this.createdAt = createdAt;
            this.createdAtMs = createdAt;
            this.lastUsedAt = lastUsedAt;
            this.lastUsedAtMs = lastUsedAt;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getTokenHash() { return tokenHash; }
        public String getNodeId() { return nodeId; }
        public long getCreatedAt() { return createdAt; }
        public long getCreatedAtMs() { return createdAt; }
        public long getLastUsedAt() { return lastUsedAt; }
        public long getLastUsedAtMs() { return lastUsedAt; }

        @Override
        public String toString() {
            return "RemoteDevice{id='" + id + "', name='" + name + "', nodeId='" + nodeId + "', createdAt=" + createdAt + ", lastUsedAt=" + lastUsedAt + "}";
        }
    }

    public interface AtomicFileWriter {
        void writeSync(File tempFile, byte[] data) throws IOException;
        void atomicReplace(File tempFile, File destFile) throws IOException;
    }

    static final AtomicFileWriter DEFAULT_WRITER = new AtomicFileWriter() {
        @Override
        public void writeSync(File tempFile, byte[] data) throws IOException {
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(data);
                fos.flush();
                fos.getFD().sync();
            }
        }

        @Override
        public void atomicReplace(File tempFile, File destFile) throws IOException {
            defaultAtomicReplace(tempFile, destFile);
        }
    };

    private final File storageFile;
    private final AtomicFileWriter writer;
    private final List<RemoteDevice> devices = new ArrayList<RemoteDevice>();

    public RemoteDeviceStore(File file) throws IOException {
        this(file, DEFAULT_WRITER);
    }

    public RemoteDeviceStore(File file, AtomicFileWriter writer) throws IOException {
        this.storageFile = file;
        this.writer = writer != null ? writer : DEFAULT_WRITER;
        load();
    }

    public synchronized RemoteDevice add(String name, String token, String nodeId) throws IOException {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("Token cannot be null or empty");
        }
        String id = UUID.randomUUID().toString();
        String hashHex = sha256Hex(token);
        long now = System.currentTimeMillis();
        RemoteDevice device = new RemoteDevice(id, name != null ? name : "", hashHex, nodeId != null ? nodeId : "", now);
        devices.add(device);
        try {
            save();
        } catch (IOException e) {
            devices.remove(devices.size() - 1);
            throw e;
        }
        return device;
    }

    public synchronized RemoteDevice identify(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        byte[] givenHash = sha256Bytes(token);
        for (int i = 0; i < devices.size(); i++) {
            RemoteDevice device = devices.get(i);
            byte[] storedHash = hexToBytes(device.tokenHash);
            if (storedHash != null && MessageDigest.isEqual(givenHash, storedHash)) {
                long now = System.currentTimeMillis();
                if (now - device.lastUsedAt >= 60000L) {
                    RemoteDevice updated = new RemoteDevice(device.id, device.name, device.tokenHash, device.nodeId, device.createdAt, now);
                    devices.set(i, updated);
                    try {
                        save();
                    } catch (IOException ignored) {}
                    return updated;
                }
                return device;
            }
        }
        return null;
    }

    public synchronized boolean accepts(String token) {
        return identify(token) != null;
    }

    public synchronized RemoteDevice remove(String id) throws IOException {
        if (id == null) return null;
        for (int i = 0; i < devices.size(); i++) {
            RemoteDevice dev = devices.get(i);
            if (id.equals(dev.id)) {
                devices.remove(i);
                try {
                    save();
                } catch (IOException e) {
                    devices.add(i, dev);
                    throw e;
                }
                return dev;
            }
        }
        return null;
    }

    public synchronized void setNodeId(String id, String nodeId) throws IOException {
        if (id == null) return;
        for (int i = 0; i < devices.size(); i++) {
            RemoteDevice dev = devices.get(i);
            if (id.equals(dev.id)) {
                RemoteDevice updated = new RemoteDevice(dev.id, dev.name, dev.tokenHash, nodeId != null ? nodeId : "", dev.createdAt, dev.lastUsedAt);
                devices.set(i, updated);
                try {
                    save();
                } catch (IOException e) {
                    devices.set(i, dev);
                    throw e;
                }
                return;
            }
        }
    }

    public synchronized List<RemoteDevice> list() {
        return Collections.unmodifiableList(new ArrayList<RemoteDevice>(devices));
    }

    private synchronized void load() throws IOException {
        devices.clear();
        File bakFile = new File(storageFile.getAbsolutePath() + ".bak");
        if (!storageFile.exists() && bakFile.exists()) {
            bakFile.renameTo(storageFile);
        }
        if (bakFile.exists()) {
            bakFile.delete();
        }
        if (!storageFile.exists() || storageFile.length() == 0) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(storageFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }

        parseJsonArray(sb.toString(), devices);
    }

    private synchronized void save() throws IOException {
        File parent = storageFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        File tempFile = new File(storageFile.getParentFile(), storageFile.getName() + ".tmp");
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < devices.size(); i++) {
            RemoteDevice dev = devices.get(i);
            sb.append("  {\n");
            sb.append("    \"id\": \"").append(escapeJson(dev.id)).append("\",\n");
            sb.append("    \"name\": \"").append(escapeJson(dev.name)).append("\",\n");
            sb.append("    \"tokenHash\": \"").append(escapeJson(dev.tokenHash)).append("\",\n");
            sb.append("    \"nodeId\": \"").append(escapeJson(dev.nodeId)).append("\",\n");
            sb.append("    \"createdAt\": ").append(dev.createdAt).append(",\n");
            sb.append("    \"createdAtMs\": ").append(dev.createdAt).append(",\n");
            sb.append("    \"lastUsedAt\": ").append(dev.lastUsedAt).append(",\n");
            sb.append("    \"lastUsedAtMs\": ").append(dev.lastUsedAt).append("\n");
            sb.append("  }").append(i < devices.size() - 1 ? "," : "").append("\n");
        }
        sb.append("]\n");

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        try {
            writer.writeSync(tempFile, bytes);
            writer.atomicReplace(tempFile, storageFile);
        } catch (IOException e) {
            if (tempFile.exists()) {
                tempFile.delete();
            }
            throw e;
        }
    }

    static void defaultAtomicReplace(File tempFile, File destFile) throws IOException {
        if (tempFile.renameTo(destFile)) {
            return;
        }

        File bakFile = new File(destFile.getAbsolutePath() + ".bak");
        if (bakFile.exists()) {
            bakFile.delete();
        }
        if (destFile.exists() && !destFile.renameTo(bakFile)) {
            throw new IOException("Failed to move destination to backup: " + destFile);
        }
        if (!tempFile.renameTo(destFile)) {
            if (bakFile.exists()) {
                bakFile.renameTo(destFile);
            }
            throw new IOException("Failed to rename temp file to destination: " + tempFile);
        }
        bakFile.delete();
    }

    static void parseJsonArray(String json, List<RemoteDevice> outList) {
        Pattern objPattern = Pattern.compile("\\{[^{}]*\\}");
        Matcher objMatcher = objPattern.matcher(json);
        while (objMatcher.find()) {
            String objText = objMatcher.group();
            String id = extractField(objText, "id");
            String name = extractField(objText, "name");
            String tokenHash = extractField(objText, "tokenHash");
            String nodeId = extractField(objText, "nodeId");
            String createdAtStr = extractField(objText, "createdAt");
            if (createdAtStr == null || createdAtStr.isEmpty()) {
                createdAtStr = extractField(objText, "createdAtMs");
            }
            long createdAt = 0L;
            if (createdAtStr != null && !createdAtStr.isEmpty()) {
                try {
                    createdAt = Long.parseLong(createdAtStr.trim());
                } catch (NumberFormatException ignored) {}
            }
            String lastUsedAtStr = extractField(objText, "lastUsedAt");
            if (lastUsedAtStr == null || lastUsedAtStr.isEmpty()) {
                lastUsedAtStr = extractField(objText, "lastUsedAtMs");
            }
            long lastUsedAt = 0L;
            if (lastUsedAtStr != null && !lastUsedAtStr.isEmpty()) {
                try {
                    lastUsedAt = Long.parseLong(lastUsedAtStr.trim());
                } catch (NumberFormatException ignored) {}
            }
            if (id != null && tokenHash != null) {
                outList.add(new RemoteDevice(id, name != null ? name : "", tokenHash, nodeId != null ? nodeId : "", createdAt, lastUsedAt));
            }
        }
    }

    private static String extractField(String jsonObject, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(?:\"([^\"]*)\"|([0-9]+))");
        Matcher matcher = pattern.matcher(jsonObject);
        if (matcher.find()) {
            if (matcher.group(1) != null) {
                return unescapeJson(matcher.group(1));
            }
            return matcher.group(2);
        }
        return null;
    }

    private static String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescapeJson(String escaped) {
        if (escaped == null) return "";
        return escaped.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r");
    }

    private static byte[] sha256Bytes(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String sha256Hex(String value) {
        byte[] hash = sha256Bytes(value);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) return null;
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi == -1 || lo == -1) return null;
            data[i / 2] = (byte) ((hi << 4) + lo);
        }
        return data;
    }
}
