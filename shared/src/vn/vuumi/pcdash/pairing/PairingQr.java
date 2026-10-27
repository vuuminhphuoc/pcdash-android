package vn.vuumi.pcdash.pairing;

import java.util.Locale;

/**
 * The QR code the home phone shows while a pairing code is open:
 * {@code pcdash://pair?v=1&ip=192.168.1.20&code=ABC234}. The remote scans it instead of typing the
 * address and the code. Pairing itself does not change: the code still goes through the same
 * handshake, on the same Wi-Fi, within the same 5 minutes.
 */
public final class PairingQr {
    public static final String PREFIX = "pcdash://pair?";
    public static final int CODE_LENGTH = 6;
    // Same alphabet as the host's PairingManager (no 0/O, 1/I/L).
    public static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int MAX_LENGTH = 128;

    public final String ip;
    public final String code;

    private PairingQr(String ip, String code) {
        this.ip = ip;
        this.code = code;
    }

    public static String build(String ip, String code) {
        return PREFIX + "v=1&ip=" + ip + "&code=" + code;
    }

    /** The address and code, or null when the text is not a valid pairing QR code. */
    public static PairingQr parse(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.length() > MAX_LENGTH || !s.startsWith(PREFIX)) return null;
        String version = null;
        String ip = null;
        String code = null;
        for (String pair : s.substring(PREFIX.length()).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String key = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            if ("v".equals(key)) version = value;
            else if ("ip".equals(key)) ip = value;
            else if ("code".equals(key)) code = value.toUpperCase(Locale.US);
        }
        if (!"1".equals(version) || !isPrivateIpv4(ip) || !isCode(code)) return null;
        return new PairingQr(ip, code);
    }

    static boolean isCode(String code) {
        if (code == null || code.length() != CODE_LENGTH) return false;
        for (int i = 0; i < code.length(); i++) {
            if (CODE_ALPHABET.indexOf(code.charAt(i)) < 0) return false;
        }
        return true;
    }

    /** Dotted IPv4 in 10/8, 172.16/12 or 192.168/16: pairing only happens on the home LAN. */
    static boolean isPrivateIpv4(String ip) {
        if (ip == null) return false;
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] b = new int[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) return false;
            for (int j = 0; j < p.length(); j++) {
                if (p.charAt(j) < '0' || p.charAt(j) > '9') return false;
            }
            b[i] = Integer.parseInt(p);
            if (b[i] > 255) return false;
        }
        return b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) || (b[0] == 192 && b[1] == 168);
    }
}
