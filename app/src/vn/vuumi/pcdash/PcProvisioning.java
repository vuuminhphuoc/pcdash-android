package vn.vuumi.pcdash;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * Setup payload that PcAgent shows as a QR code on the PC:
 * {@code pcdash://setup?v=1&ip=192.168.1.50&mac=AA:BB:CC:DD:EE:FF&port=8799&token=...}
 * Pure Java so it can be unit-tested without Android.
 */
public final class PcProvisioning {
    public static final String SCHEME_PREFIX = "pcdash://setup?";
    public static final int MIN_TOKEN_LENGTH = 16;
    public static final int MAX_TOKEN_LENGTH = 128;
    public static final int MAX_PAYLOAD_LENGTH = 512;

    public final String ip;
    public final String mac;
    public final int port;
    public final String token;

    private PcProvisioning(String ip, String mac, int port, String token) {
        this.ip = ip;
        this.mac = mac;
        this.port = port;
        this.token = token;
    }

    /** Returns the parsed setup, or null when the text is not a valid PCDash setup payload. */
    public static PcProvisioning parse(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.length() > MAX_PAYLOAD_LENGTH || !s.startsWith(SCHEME_PREFIX)) return null;

        String ip = null;
        String mac = null;
        String portStr = null;
        String token = null;
        String version = null;
        for (String pair : s.substring(SCHEME_PREFIX.length()).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String key = pair.substring(0, eq);
            String value = decode(pair.substring(eq + 1));
            if (value == null) return null;
            if ("ip".equals(key)) ip = value;
            else if ("mac".equals(key)) mac = value;
            else if ("port".equals(key)) portStr = value;
            else if ("token".equals(key)) token = value;
            else if ("v".equals(key)) version = value;
        }

        if (!"1".equals(version)) return null;
        if (!PcSettings.isValidRfc1918Ip(ip)) return null;
        if (mac == null || !PcSettings.isValidMac(mac)) return null;
        if (token == null || token.length() < MIN_TOKEN_LENGTH || token.length() > MAX_TOKEN_LENGTH) return null;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '-' || c == '_';
            if (!ok) return null;
        }
        int port = PcSettings.DEFAULT_AGENT_PORT;
        if (portStr != null && !portStr.isEmpty()) {
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                return null;
            }
            if (port < 1 || port > 65535) return null;
        }
        return new PcProvisioning(ip.trim(), mac.trim().toUpperCase(java.util.Locale.ROOT), port, token);
    }

    private static String decode(String v) {
        try {
            return URLDecoder.decode(v, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
