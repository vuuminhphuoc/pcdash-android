package vn.vuumi.pcdash.remote;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

public class RemoteConfigStore {

    public static class RemoteConfig {
        public final String deviceToken;
        public final String hostTailnetIp;
        public final int hostPort;
        public final boolean requireDangerousAuth;

        public RemoteConfig(String deviceToken, String hostTailnetIp, int hostPort) {
            this(deviceToken, hostTailnetIp, hostPort, true);
        }

        public RemoteConfig(String deviceToken, String hostTailnetIp, int hostPort, boolean requireDangerousAuth) {
            this.deviceToken = deviceToken != null ? deviceToken.trim() : "";
            this.hostTailnetIp = hostTailnetIp != null ? hostTailnetIp.trim() : "";
            this.hostPort = hostPort > 0 ? hostPort : 8091;
            this.requireDangerousAuth = requireDangerousAuth;
        }

        @Override
        public String toString() {
            return "RemoteConfig[host=" + hostTailnetIp + ":" + hostPort + ", auth=" + requireDangerousAuth + "]";
        }
    }
    public interface AtomicFileWriter {
        void writeSync(File tempFile, byte[] data) throws IOException;
        void atomicReplace(File tempFile, File destFile) throws IOException;
    }

    static final AtomicFileWriter DEFAULT_WRITER = new AtomicFileWriter() {
        @Override
        public void writeSync(File tempFile, byte[] data) throws IOException {
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(tempFile);
                fos.write(data);
                fos.flush();
                fos.getFD().sync();
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException ignored) {}
                }
            }
        }

        @Override
        public void atomicReplace(File tempFile, File destFile) throws IOException {
            defaultAtomicReplace(tempFile, destFile);
        }
    };

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

    public static final String HOSTNAME_PREFIX = "pcdash-remote-";
    private static final String HOSTNAME_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final File configFile;
    private final File hostnameFile;
    private final AtomicFileWriter writer;
    private volatile RemoteConfig cachedConfig = null;
    private volatile String cachedHostname = null;
    public RemoteConfigStore(File configFile) {
        this(configFile, DEFAULT_WRITER);
    }

    public RemoteConfigStore(File configFile, AtomicFileWriter writer) {
        if (configFile == null) {
            throw new IllegalArgumentException("configFile cannot be null");
        }
        this.configFile = configFile;
        File parent = configFile.getParentFile();
        this.hostnameFile = parent != null ? new File(parent, "remote-hostname.txt") : new File("remote-hostname.txt");
        this.writer = writer != null ? writer : DEFAULT_WRITER;
    }

    public synchronized String getOrCreateHostname() {
        if (cachedHostname != null && !cachedHostname.isEmpty()) {
            return cachedHostname;
        }
        if (hostnameFile.exists() && hostnameFile.length() > 0) {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(hostnameFile), StandardCharsets.UTF_8));
                String line = r.readLine();
                if (line != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith(HOSTNAME_PREFIX) && trimmed.length() == HOSTNAME_PREFIX.length() + 4) {
                        cachedHostname = trimmed;
                        return cachedHostname;
                    }
                }
            } catch (IOException ignored) {
            } finally {
                if (r != null) {
                    try { r.close(); } catch (IOException ignored) {}
                }
            }
        }

        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(HOSTNAME_PREFIX);
        for (int i = 0; i < 4; i++) {
            sb.append(HOSTNAME_CHARS.charAt(rng.nextInt(HOSTNAME_CHARS.length())));
        }
        String generated = sb.toString();
        cachedHostname = generated;
        persistHostname(generated);
        return generated;
    }

    public synchronized void setHostname(String hostname) {
        if (hostname != null && !hostname.trim().isEmpty()) {
            this.cachedHostname = hostname.trim();
            persistHostname(this.cachedHostname);
        }
    }

    public String getCachedHostname() {
        return cachedHostname;
    }

    private void persistHostname(String name) {
        try {
            File parent = hostnameFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            File tmp = new File(parent != null ? parent : new File("."), hostnameFile.getName() + ".tmp");
            byte[] bytes = (name + "\n").getBytes(StandardCharsets.UTF_8);
            writer.writeSync(tmp, bytes);
            writer.atomicReplace(tmp, hostnameFile);
        } catch (Exception ignored) {}
    }

    public synchronized RemoteConfig load() throws IOException {
        File bakFile = new File(configFile.getAbsolutePath() + ".bak");
        if (bakFile.exists()) {
            if (configFile.exists()) {
                bakFile.delete();
            } else {
                bakFile.renameTo(configFile);
            }
        }

        if (!configFile.exists() || configFile.length() == 0) {
            cachedConfig = null;
            return null;
        }

        StringBuilder sb = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {}
            }
        }

        String json = sb.toString();
        String token = extractString(json, "deviceToken");
        String host = extractString(json, "hostTailnetIp");
        int port = extractInt(json, "hostPort", 8091);
        boolean requireAuth = extractBoolean(json, "requireDangerousAuth", true);
        String h = extractString(json, "hostname");
        if (h != null && h.startsWith(HOSTNAME_PREFIX) && cachedHostname == null) {
            cachedHostname = h;
            persistHostname(h);
        }
        if (token == null || token.isEmpty() || host == null || host.isEmpty()) {
            cachedConfig = null;
            return null;
        }
        RemoteConfig loaded = new RemoteConfig(token, host, port, requireAuth);
        cachedConfig = loaded;
        return loaded;
    }

    public synchronized void save(RemoteConfig config) throws IOException {
        if (config == null) {
            clear();
            return;
        }
        cachedConfig = config;

        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        File tmp = new File(parent, configFile.getName() + ".tmp");
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"deviceToken\": \"").append(escapeJson(config.deviceToken)).append("\",\n");
        sb.append("  \"hostTailnetIp\": \"").append(escapeJson(config.hostTailnetIp)).append("\",\n");
        sb.append("  \"hostPort\": ").append(config.hostPort).append(",\n");
        String h = getOrCreateHostname();
        sb.append("  \"requireDangerousAuth\": ").append(config.requireDangerousAuth).append(",\n");
        sb.append("  \"hostname\": \"").append(escapeJson(h)).append("\"\n");
        sb.append("}\n");
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        try {
            writer.writeSync(tmp, bytes);
            writer.atomicReplace(tmp, configFile);
        } finally {
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    public synchronized void clear() {
        cachedConfig = null;
        if (configFile.exists()) {
            configFile.delete();
        }
        File parent = configFile.getParentFile();
        if (parent != null) {
            File tmp = new File(parent, configFile.getName() + ".tmp");
            if (tmp.exists()) {
                tmp.delete();
            }
            File bak = new File(parent, configFile.getName() + ".bak");
            if (bak.exists()) {
                bak.delete();
            }
        }
    }
    public synchronized void delete() {
        clear();
    }

    public RemoteConfig getCached() {
        return cachedConfig;
    }

    public synchronized void setRequireDangerousAuth(boolean require) throws IOException {
        RemoteConfig cur = load();
        if (cur != null) {
            save(new RemoteConfig(cur.deviceToken, cur.hostTailnetIp, cur.hostPort, require));
        }
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

    private static boolean extractBoolean(String json, String key, boolean defaultVal) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return defaultVal;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return defaultVal;
        int end = colon + 1;
        while (end < json.length() && (Character.isWhitespace(json.charAt(end)) || Character.isLetter(json.charAt(end)))) {
            end++;
        }
        String valStr = json.substring(colon + 1, end).trim();
        if ("true".equalsIgnoreCase(valStr)) return true;
        if ("false".equalsIgnoreCase(valStr)) return false;
        return defaultVal;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
