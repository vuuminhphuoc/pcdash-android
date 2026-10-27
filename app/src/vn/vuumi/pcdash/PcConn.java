package vn.vuumi.pcdash;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class PcConn {
    public static final int DEFAULT_AGENT_PORT = 8799;
    static final int[] STATUS_PORTS = {8799, 445, 135, 3389, 22};
    static final int STATUS_TIMEOUT = 2000;

    private static volatile PcSettingsSource settings = null;

    public static void configure(PcSettingsSource source) {
        settings = source;
    }

    public static PcSettingsSource getSettings() {
        return settings;
    }

    public static boolean isConfigured() {
        PcSettingsSource s = settings;
        return s != null && s.isConfigured();
    }

    public static String statusLine() {
        PcSettingsSource s = settings;
        if (s == null || !s.isConfigured()) {
            return "Chưa cấu hình PC";
        }
        String ip = s.getPcIp();
        for (int p : STATUS_PORTS) {
            if (tcpOpen(ip, p, STATUS_TIMEOUT)) return "PC đang BẬT (port " + p + ")";
        }
        return "PC đang TẮT / không phản hồi";
    }

    public static String statusJson() {
        PcSettingsSource s = settings;
        if (s == null || !s.isConfigured()) {
            return "{\"online\":false,\"port\":null,\"rtt_ms\":0,\"configured\":false}";
        }
        String ip = s.getPcIp();
        long t0 = System.currentTimeMillis();
        int openPort = -1;
        for (int p : STATUS_PORTS) {
            if (tcpOpen(ip, p, STATUS_TIMEOUT)) {
                openPort = p;
                break;
            }
        }
        long rtt = System.currentTimeMillis() - t0;
        boolean online = openPort != -1;
        String portJson = online ? String.valueOf(openPort) : "null";
        return "{\"online\":" + online + ",\"port\":" + portJson + ",\"rtt_ms\":" + rtt + ",\"configured\":true}";
    }

    static boolean tcpOpen(String ip, int port, int timeoutMs) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) { try { s.close(); } catch (Exception ignored) {} }
        }
    }

    public static String doAction(String action) {
        PcSettingsSource s = settings;
        if (s == null || !s.isConfigured()) {
            return "Lỗi: Chưa cấu hình PC";
        }
        if ("wake".equals(action)) return wol();
        return agentExec(action);
    }

    public static String wol() {
        PcSettingsSource s = settings;
        if (s == null || !s.isConfigured()) {
            return "Lỗi: Chưa cấu hình PC";
        }
        String macStr = s.getPcMac();
        if (macStr == null || macStr.trim().isEmpty()) {
            return "Lỗi WOL: Chưa cấu hình địa chỉ MAC của PC";
        }
        macStr = macStr.trim();
        String pcIp = s.getPcIp();
        try {
            String[] parts = macStr.split("[:-]");
            if (parts.length != 6) {
                return "Lỗi WOL: Địa chỉ MAC không hợp lệ";
            }
            byte[] mac = new byte[6];
            for (int i = 0; i < 6; i++) mac[i] = (byte) Integer.parseInt(parts[i], 16);
            byte[] packet = new byte[6 + 16 * 6];
            for (int i = 0; i < 6; i++) packet[i] = (byte) 0xFF;
            for (int i = 0; i < 16; i++) System.arraycopy(mac, 0, packet, 6 + i * 6, 6);

            DatagramSocket sock = new DatagramSocket();
            sock.setBroadcast(true);

            List<String> targets = new ArrayList<String>();
            targets.add("255.255.255.255");
            int lastDot = pcIp != null ? pcIp.lastIndexOf('.') : -1;
            if (lastDot > 0) {
                String bcast24 = pcIp.substring(0, lastDot) + ".255";
                if (!targets.contains(bcast24)) {
                    targets.add(bcast24);
                }
            }
            if (pcIp != null && !pcIp.isEmpty() && !targets.contains(pcIp)) {
                targets.add(pcIp);
            }

            int[] ports = {9, 7};
            for (String t : targets) {
                for (int pt : ports) {
                    try {
                        sock.send(new DatagramPacket(packet, packet.length, InetAddress.getByName(t), pt));
                    } catch (Exception ignored) {}
                }
            }
            sock.close();
            return "Đã gửi magic packet (WOL) tới " + macStr;
        } catch (Exception e) {
            return "Lỗi WOL: " + e;
        }
    }

    static String agentExec(String action) {
        PcSettingsSource s = settings;
        if (s == null || !s.isConfigured()) {
            return "Lỗi: Chưa cấu hình PC";
        }
        String ip = s.getPcIp();
        int port = s.getAgentPort() > 0 ? s.getAgentPort() : DEFAULT_AGENT_PORT;
        String token = s.getAgentToken();

        HttpURLConnection c = null;
        try {
            URL u = new URL("http://" + ip + ":" + port + "/exec");
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(5000);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-PcAgent-Token", token != null ? token : "");
            OutputStream os = c.getOutputStream();
            os.write(("{\"action\":\"" + action + "\"}").getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            int code = c.getResponseCode();
            if (code == 403) {
                return "Agent từ chối: sai mã token";
            }
            if (code == 503) {
                return "Agent PC chưa cấu hình token";
            }
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
            }
            return "Agent[" + code + "]: " + sb;
        } catch (Exception e) {
            return "Không kết nối được agent PC: " + e;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
