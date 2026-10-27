package vn.vuumi.pcdash;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.regex.Pattern;

public class PcSettings implements PcSettingsSource {
    public static final String PREFS_NAME = "pc_settings";
    public static final String KEY_PC_IP = "pc_ip";
    public static final String KEY_PC_MAC = "pc_mac";
    public static final String KEY_AGENT_PORT = "agent_port";
    public static final String KEY_AGENT_TOKEN_ENC = "agent_token_enc";
    public static final String KEY_WEB_TOKEN_ENC = "web_token_enc";

    public static final int DEFAULT_AGENT_PORT = 8799;

    private static final Pattern MAC_PATTERN = Pattern.compile("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$");

    private final SharedPreferences prefs;
    private final SecretCipher cipher;

    private volatile String cachedWebToken = null;
    private volatile String cachedAgentToken = null;
    private String agentTokenEncSeen = null;
    private String webTokenEncSeen = null;

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
