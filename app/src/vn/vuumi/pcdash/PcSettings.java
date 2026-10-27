package vn.vuumi.pcdash;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The host's settings: the web dashboard code and the PCs it controls. The first PC keeps the keys it
 * always had (so nothing moves for a single-PC setup, and an older app version still finds it); more
 * PCs are kept in {@link #KEY_EXTRA_PCS}. As a {@link PcSettingsSource} this object is the first PC.
 */
public class PcSettings implements PcSettingsSource {
    public static final String PREFS_NAME = "pc_settings";
    public static final String KEY_PC_IP = "pc_ip";
    public static final String KEY_PC_MAC = "pc_mac";
    public static final String KEY_AGENT_PORT = "agent_port";
    public static final String KEY_AGENT_TOKEN_ENC = "agent_token_enc";
    public static final String KEY_WEB_TOKEN_ENC = "web_token_enc";
    public static final String KEY_PC_NAME = "pc_name";
    public static final String KEY_PC_ID = "pc_id";
    public static final String KEY_EXTRA_PCS = "extra_pcs";

    public static final String FIRST_PC_ID = "pc1";
    public static final int MAX_PCS = 8;

    public static final int DEFAULT_AGENT_PORT = 8799;

    private static final Pattern MAC_PATTERN = Pattern.compile("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$");

    private final SharedPreferences prefs;
    private final SecretCipher cipher;

    private volatile String cachedWebToken = null;
    private volatile String cachedAgentToken = null;
    private String agentTokenEncSeen = null;
    private String webTokenEncSeen = null;
    private String extrasRawSeen = null;
    private List<PcProfile> extrasCache = Collections.emptyList();

    public PcSettings(Context context) {
        this(context != null ? context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) : null, createDefaultCipher());
    }

    public PcSettings(Context context, SecretCipher cipher) {
        this(context != null ? context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) : null, cipher);
    }

    public PcSettings(SharedPreferences prefs, SecretCipher cipher) {
        this.prefs = prefs;
        this.cipher = cipher;
        ensureWebToken();
    }

    private static SecretCipher createDefaultCipher() {
        try {
            return new AndroidSecretCipher();
        } catch (Throwable t) {
            return null;
        }
    }

    private synchronized void ensureWebToken() {
        if (prefs == null) {
            if (cachedWebToken == null) {
                cachedWebToken = generateRandomHex(24);
            }
            return;
        }
        String enc = prefs.getString(KEY_WEB_TOKEN_ENC, null);
        if (enc != null && !enc.isEmpty()) {
            try {
                cachedWebToken = cipher != null ? cipher.decrypt(enc) : enc;
                webTokenEncSeen = enc;
            } catch (Exception e) {
                regenerateWebToken();
            }
        } else {
            regenerateWebToken();
        }
    }

    public synchronized String regenerateWebToken() {
        String token = generateRandomHex(24);
        cachedWebToken = token;
        if (prefs != null) {
            String enc = token;
            if (cipher != null) {
                try {
                    enc = cipher.encrypt(token);
                } catch (Exception ignored) {}
            }
            prefs.edit().putString(KEY_WEB_TOKEN_ENC, enc).apply();
            webTokenEncSeen = enc;
        }
        return token;
    }

    @Override
    public String getPcIp() {
        return prefs != null ? prefs.getString(KEY_PC_IP, "") : "";
    }

    @Override
    public String getPcMac() {
        return prefs != null ? prefs.getString(KEY_PC_MAC, "") : "";
    }

    @Override
    public int getAgentPort() {
        return prefs != null ? prefs.getInt(KEY_AGENT_PORT, DEFAULT_AGENT_PORT) : DEFAULT_AGENT_PORT;
    }

    // Another PcSettings instance (the settings screen) may have saved new values, so the encrypted
    // value in the shared preferences is the source of truth; decrypt again only when it changed.
    @Override
    public synchronized String getAgentToken() {
        if (prefs == null) {
            return cachedAgentToken != null ? cachedAgentToken : "";
        }
        String enc = prefs.getString(KEY_AGENT_TOKEN_ENC, null);
        if (enc == null || enc.isEmpty()) {
            agentTokenEncSeen = null;
            cachedAgentToken = "";
            return "";
        }
        if (!enc.equals(agentTokenEncSeen)) {
            String plain;
            try {
                plain = cipher != null ? cipher.decrypt(enc) : enc;
            } catch (Exception e) {
                plain = "";
            }
            cachedAgentToken = plain != null ? plain : "";
            agentTokenEncSeen = enc;
        }
        return cachedAgentToken;
    }

    @Override
    public synchronized String getWebToken() {
        if (prefs != null) {
            String enc = prefs.getString(KEY_WEB_TOKEN_ENC, null);
            if (enc == null || enc.isEmpty()) {
                regenerateWebToken();
            } else if (!enc.equals(webTokenEncSeen)) {
                try {
                    cachedWebToken = cipher != null ? cipher.decrypt(enc) : enc;
                    webTokenEncSeen = enc;
                } catch (Exception e) {
                    regenerateWebToken();
                }
            }
        } else if (cachedWebToken == null) {
            ensureWebToken();
        }
        return cachedWebToken != null ? cachedWebToken : "";
    }

    @Override
    public boolean isConfigured() {
        return isValidRfc1918Ip(getPcIp());
    }

    public synchronized void save(String ip, String mac, int port, String agentToken) {
        if (prefs == null) return;
        cachedAgentToken = agentToken != null ? agentToken.trim() : "";
        String encAgentToken = cachedAgentToken;
        if (cipher != null && !cachedAgentToken.isEmpty()) {
            try {
                encAgentToken = cipher.encrypt(cachedAgentToken);
            } catch (Exception ignored) {}
        }
        prefs.edit()
                .putString(KEY_PC_IP, ip != null ? ip.trim() : "")
                .putString(KEY_PC_MAC, mac != null ? mac.trim() : "")
                .putInt(KEY_AGENT_PORT, port > 0 ? port : DEFAULT_AGENT_PORT)
                .putString(KEY_AGENT_TOKEN_ENC, encAgentToken)
                .apply();
    }

    // ---- Several PCs ----

    /** Every PC, the first one first. Empty when none is set up. */
    public synchronized List<PcProfile> listPcs() {
        List<PcProfile> out = new ArrayList<PcProfile>();
        if (isConfigured()) {
            out.add(firstPc());
        }
        out.addAll(extras());
        return out;
    }

    /** The PC with this id; null id means the first PC. Null when there is no such PC. */
    public synchronized PcProfile findPc(String id) {
        List<PcProfile> all = listPcs();
        if (id == null || id.isEmpty()) {
            return all.isEmpty() ? null : all.get(0);
        }
        for (PcProfile p : all) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    /**
     * A live view of one PC: every call reads the current settings, so a long-lived client (metrics,
     * quota) follows edits made on the settings screen. Null id means the first PC.
     */
    public PcSettingsSource source(final String id) {
        return new PcSettingsSource() {
            private PcProfile pc() {
                PcProfile p = findPc(id);
                return p != null ? p : new PcProfile(id, "", "", "", 0, "");
            }

            @Override
            public String getPcIp() {
                return pc().ip;
            }

            @Override
            public String getPcMac() {
                return pc().mac;
            }

            @Override
            public int getAgentPort() {
                return pc().port;
            }

            @Override
            public String getAgentToken() {
                return pc().token;
            }

            @Override
            public String getWebToken() {
                return PcSettings.this.getWebToken();
            }

            @Override
            public boolean isConfigured() {
                return pc().isConfigured();
            }
        };
    }

    public synchronized String getPcName() {
        String n = prefs != null ? prefs.getString(KEY_PC_NAME, null) : null;
        return n != null && !n.trim().isEmpty() ? n.trim() : "PC";
    }

    /** Adds a PC and returns its id. The first PC goes where a single PC always went. */
    public synchronized String addPc(String name, String ip, String mac, int port, String token) {
        if (!isConfigured()) {
            save(ip, mac, port, token);
            putFirstMeta(nonEmpty(name, "PC"), FIRST_PC_ID);
            return FIRST_PC_ID;
        }
        List<PcProfile> extras = new ArrayList<PcProfile>(extras());
        if (extras.size() + 1 >= MAX_PCS) {
            throw new IllegalStateException("At most " + MAX_PCS + " PCs");
        }
        String id = newId();
        extras.add(new PcProfile(id, nonEmpty(name, "PC " + (extras.size() + 2)), ip, mac, port, token));
        writeExtras(extras);
        return id;
    }

    public synchronized void updatePc(String id, String name, String ip, String mac, int port, String token) {
        if (id == null || id.equals(firstPcId())) {
            save(ip, mac, port, token);
            putFirstMeta(nonEmpty(name, "PC"), firstPcId());
            return;
        }
        List<PcProfile> extras = new ArrayList<PcProfile>(extras());
        for (int i = 0; i < extras.size(); i++) {
            if (extras.get(i).id.equals(id)) {
                extras.set(i, new PcProfile(id, nonEmpty(name, extras.get(i).name), ip, mac, port, token));
                writeExtras(extras);
                return;
            }
        }
    }

    /** Removes a PC; when the first one goes, the next takes its place (and keeps its id). */
    public synchronized void removePc(String id) {
        if (id == null) return;
        List<PcProfile> extras = new ArrayList<PcProfile>(extras());
        if (id.equals(firstPcId())) {
            if (extras.isEmpty()) {
                save("", "", DEFAULT_AGENT_PORT, "");
                putFirstMeta(null, null);
            } else {
                PcProfile next = extras.remove(0);
                save(next.ip, next.mac, next.port, next.token);
                putFirstMeta(next.name, next.id);
                writeExtras(extras);
            }
            return;
        }
        for (int i = 0; i < extras.size(); i++) {
            if (extras.get(i).id.equals(id)) {
                extras.remove(i);
                writeExtras(extras);
                return;
            }
        }
    }

    private PcProfile firstPc() {
        return new PcProfile(firstPcId(), getPcName(), getPcIp(), getPcMac(), getAgentPort(), getAgentToken());
    }

    private String firstPcId() {
        String id = prefs != null ? prefs.getString(KEY_PC_ID, null) : null;
        return id != null && !id.isEmpty() ? id : FIRST_PC_ID;
    }

    private void putFirstMeta(String name, String id) {
        if (prefs == null) return;
        SharedPreferences.Editor e = prefs.edit();
        if (name != null) e.putString(KEY_PC_NAME, name); else e.remove(KEY_PC_NAME);
        if (id != null) e.putString(KEY_PC_ID, id); else e.remove(KEY_PC_ID);
        e.apply();
    }

    private String newId() {
        List<PcProfile> all = listPcs();
        while (true) {
            String id = "pc" + generateRandomHex(6);
            boolean used = id.equals(firstPcId());
            for (PcProfile p : all) {
                if (p.id.equals(id)) used = true;
            }
            if (!used) return id;
        }
    }

    // One PC per line; fields URL-encoded and separated by '|'; the token encrypted like the first PC's.
    private List<PcProfile> extras() {
        String raw = prefs != null ? prefs.getString(KEY_EXTRA_PCS, "") : "";
        if (raw == null) raw = "";
        if (raw.equals(extrasRawSeen)) return extrasCache;
        List<PcProfile> out = new ArrayList<PcProfile>();
        for (String line : raw.split("\n")) {
            String[] f = line.split("\\|", -1);
            if (f.length != 6) continue;
            String token = decode(f[5]);
            try {
                token = cipher != null && !token.isEmpty() ? cipher.decrypt(token) : token;
            } catch (Exception e) {
                token = "";
            }
            int port;
            try {
                port = Integer.parseInt(f[4]);
            } catch (NumberFormatException e) {
                port = DEFAULT_AGENT_PORT;
            }
            out.add(new PcProfile(decode(f[0]), decode(f[1]), decode(f[2]), decode(f[3]), port, token));
        }
        extrasRawSeen = raw;
        extrasCache = Collections.unmodifiableList(out);
        return extrasCache;
    }

    private void writeExtras(List<PcProfile> pcs) {
        if (prefs == null) return;
        StringBuilder sb = new StringBuilder();
        for (PcProfile p : pcs) {
            String token = p.token;
            if (cipher != null && !token.isEmpty()) {
                try {
                    token = cipher.encrypt(token);
                } catch (Exception ignored) {}
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(encode(p.id)).append('|').append(encode(p.name)).append('|').append(encode(p.ip))
                    .append('|').append(encode(p.mac)).append('|').append(p.port).append('|').append(encode(token));
        }
        prefs.edit().putString(KEY_EXTRA_PCS, sb.toString()).apply();
    }

    private static String nonEmpty(String s, String fallback) {
        return s != null && !s.trim().isEmpty() ? s.trim() : fallback;
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s != null ? s : "", "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return "";
        }
    }

    public static boolean isValidRfc1918Ip(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return false;
        }
        String trimmed = ip.trim();
        String[] parts = trimmed.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int val = Integer.parseInt(parts[i]);
                if (val < 0 || val > 255) return false;
                if (parts[i].length() > 1 && parts[i].startsWith("0")) return false;
                bytes[i] = (byte) val;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        try {
            InetAddress addr = InetAddress.getByAddress(bytes);
            return LanAddressPolicy.isAllowedPairingPeer(addr);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isValidMac(String mac) {
        if (mac == null || mac.trim().isEmpty()) {
            return false;
        }
        return MAC_PATTERN.matcher(mac.trim()).matches();
    }

    public static String generateRandomHex(int numChars) {
        SecureRandom sr = new SecureRandom();
        byte[] bytes = new byte[(numChars + 1) / 2];
        sr.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.substring(0, numChars);
    }
}
