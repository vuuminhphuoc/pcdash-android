package vn.vuumi.pcdash;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import vn.vuumi.pcdash.i18n.L10n;

/**
 * How the host talks to PcAgent. A request is signed (HMAC-SHA256, keyed with the agent token, over
 * timestamp, nonce, method, target and body) so the token itself never crosses the LAN and a captured
 * request cannot be replayed. An older agent only knows the plain token header; when it answers 403 to a
 * signed request the host asks once more with the token, so an agent that was not updated keeps working.
 */
final class AgentAuth {
    static final String TOKEN_HEADER = "X-PcAgent-Token";
    static final String TIMESTAMP_HEADER = "X-PcAgent-Ts";
    static final String NONCE_HEADER = "X-PcAgent-Nonce";
    static final String SIGNATURE_HEADER = "X-PcAgent-Sig";

    private static final SecureRandom RANDOM = new SecureRandom();

    private AgentAuth() {}

    /**
     * Sends the request and returns the connection with its response status ready: signed first, then
     * with the plain token when the agent refuses the signature (an older agent, or a wrong token).
     * [body] is null for a request without one.
     */
    static HttpURLConnection connect(String urlStr, String method, byte[] body, String token,
                                     int connectTimeoutMs, int readTimeoutMs) throws IOException {
        String t = token != null ? token : "";
        HttpURLConnection c = open(urlStr, method, body, t, connectTimeoutMs, readTimeoutMs, true);
        if (c.getResponseCode() != 403 || t.isEmpty()) return c;
        c.disconnect();
        return open(urlStr, method, body, t, connectTimeoutMs, readTimeoutMs, false);
    }

    private static HttpURLConnection open(String urlStr, String method, byte[] body, String token,
                                          int connectTimeoutMs, int readTimeoutMs, boolean signed) throws IOException {
        URL u = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(connectTimeoutMs);
        c.setReadTimeout(readTimeoutMs);
        c.setUseCaches(false);
        if (signed && !token.isEmpty()) {
            String ts = Long.toString(System.currentTimeMillis() / 1000L);
            String nonce = nonce();
            String target = u.getFile();
            c.setRequestProperty(TIMESTAMP_HEADER, ts);
            c.setRequestProperty(NONCE_HEADER, nonce);
            c.setRequestProperty(SIGNATURE_HEADER, signature(token, ts, nonce, method, target, body));
        } else {
            c.setRequestProperty(TOKEN_HEADER, token);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.flush();
            os.close();
        }
        // Reads the status now so a refusal can be answered with the plain token before the caller looks.
        c.getResponseCode();
        return c;
    }

    /** Lower-case hex HMAC-SHA256 as PcAgent computes it. */
    static String signature(String token, String timestamp, String nonce, String method, String target, byte[] body) {
        try {
            String head = timestamp + "\n" + nonce + "\n" + method + "\n" + target + "\n";
            byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(headBytes);
            if (body != null) mac.update(body);
            return hex(mac.doFinal());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nonce() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return hex(b);
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = digits[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = digits[bytes[i] & 0xF];
        }
        return new String(out);
    }

    /**
     * A message for a status the agent uses for a refusal, or null when the status is not one. 401 is a
     * signature the agent rejects only for its time (the clocks differ), 403 a wrong token, 429 too many
     * recent failures from this phone, 503 an agent that has no token.
     */
    static String refusalMessage(int code) {
        switch (code) {
            case 401:
                return L10n.t("The PC's clock and this phone's clock differ by more than 5 minutes",
                        "Đồng hồ PC và điện thoại lệch nhau quá 5 phút");
            case 403:
                return L10n.t("Agent refused: wrong token", "Agent từ chối: sai mã token");
            case 429:
                return L10n.t("The PC agent paused requests after too many refusals; try again in a minute",
                        "Agent PC tạm dừng nhận lệnh vì bị từ chối quá nhiều lần, thử lại sau một phút");
            case 503:
                return L10n.t("The PC agent has no token set", "Agent PC chưa cấu hình token");
            default:
                return null;
        }
    }
}
