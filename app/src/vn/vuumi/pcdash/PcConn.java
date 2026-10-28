package vn.vuumi.pcdash;

import vn.vuumi.pcdash.i18n.L10n;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class PcConn {
    public static final int DEFAULT_AGENT_PORT = 8799;
    static final int[] STATUS_PORTS = {8799, 445, 135, 3389, 22};
    static final int STATUS_TIMEOUT = 2000;

    private static volatile PcSettingsSource settings = null;

    private static final ExecutorService PROBES = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "pcdash-port-probe");
            t.setDaemon(true);
            return t;
        }
    });

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

    /** PC power state for the host screen, with the line to show. */
    public static final class PcStatus {
        public static final int NOT_CONFIGURED = 0;
        public static final int ON = 1;
        public static final int OFF = 2;

        public final int state;
        public final String line;

        PcStatus(int state, String line) {
            this.state = state;
            this.line = line;
        }
    }

    public static String statusLine() {
        return status().line;
    }

    public static PcStatus status() {
        return status(settings);
    }

    public static PcStatus status(PcSettingsSource s) {
        if (s == null || !s.isConfigured()) {
            return new PcStatus(PcStatus.NOT_CONFIGURED, L10n.t("PC not set up", "Chưa cấu hình PC"));
        }
        int p = firstOpenPort(s.getPcIp(), STATUS_PORTS, STATUS_TIMEOUT);
        if (p > 0) {
            return new PcStatus(PcStatus.ON, L10n.t("PC is ON (port ", "PC đang BẬT (port ") + p + ")");
        }
        return new PcStatus(PcStatus.OFF, L10n.t("PC is OFF / not responding", "PC đang TẮT / không phản hồi"));
    }

    public static String statusJson() {
        return statusJson(settings);
    }

    public static String statusJson(PcSettingsSource s) {
        if (s == null || !s.isConfigured()) {
            return "{\"online\":false,\"port\":null,\"rtt_ms\":0,\"configured\":false}";
        }
        long t0 = System.currentTimeMillis();
        int openPort = firstOpenPort(s.getPcIp(), STATUS_PORTS, STATUS_TIMEOUT);
        long rtt = System.currentTimeMillis() - t0;
        boolean online = openPort != -1;
        String portJson = online ? String.valueOf(openPort) : "null";
        return "{\"online\":" + online + ",\"port\":" + portJson + ",\"rtt_ms\":" + rtt + ",\"configured\":true}";
    }

    /**
     * The first of {@code ports}, in their order, that accepts a TCP connection, or -1. All ports are
     * tried at once, so a PC that is off answers in one timeout instead of one per port, and an open
     * port early in the list answers without waiting for the others.
     */
    static int firstOpenPort(final String ip, final int[] ports, final int timeoutMs) {
        final int[] result = new int[ports.length]; // 0 pending, 1 open, 2 closed
        final Object lock = new Object();
        for (int i = 0; i < ports.length; i++) {
            final int index = i;
            PROBES.execute(new Runnable() {
                @Override
                public void run() {
                    boolean open = tcpOpen(ip, ports[index], timeoutMs);
                    synchronized (lock) {
                        result[index] = open ? 1 : 2;
                        lock.notifyAll();
                    }
                }
            });
        }
        long deadline = System.currentTimeMillis() + timeoutMs + 500L;
        synchronized (lock) {
            while (true) {
                boolean undecided = false;
                for (int i = 0; i < ports.length; i++) {
                    if (result[i] == 1) return ports[i];
                    if (result[i] == 0) {
                        undecided = true;
                        break;
                    }
                }
                if (!undecided) return -1;
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    for (int i = 0; i < ports.length; i++) {
                        if (result[i] == 1) return ports[i];
                    }
                    return -1;
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
        }
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
        return doAction(settings, action);
    }

    public static String doAction(PcSettingsSource s, String action) {
        if (s == null || !s.isConfigured()) {
            return L10n.t("Error: PC not set up", "Lỗi: Chưa cấu hình PC");
        }
        if ("wake".equals(action)) return wol(s);
        return agentExec(s, action);
    }

    public static String wol() {
        return wol(settings);
    }

    static String wol(PcSettingsSource s) {
        if (s == null || !s.isConfigured()) {
            return L10n.t("Error: PC not set up", "Lỗi: Chưa cấu hình PC");
        }
        String macStr = s.getPcMac();
        if (macStr == null || macStr.trim().isEmpty()) {
            return L10n.t("WOL error: the PC's MAC address is not set", "Lỗi WOL: Chưa cấu hình địa chỉ MAC của PC");
        }
        macStr = macStr.trim();
        String pcIp = s.getPcIp();
        try {
            String[] parts = macStr.split("[:-]");
            if (parts.length != 6) {
                return L10n.t("WOL error: invalid MAC address", "Lỗi WOL: Địa chỉ MAC không hợp lệ");
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
            return L10n.t("Sent the magic packet (WOL) to ", "Đã gửi magic packet (WOL) tới ") + macStr;
        } catch (Exception e) {
            return L10n.t("WOL error: ", "Lỗi WOL: ") + e;
        }
    }

    static String agentExec(String action) {
        return agentExec(settings, action);
    }

    static String agentExec(PcSettingsSource s, String action) {
        if (s == null || !s.isConfigured()) {
            return L10n.t("Error: PC not set up", "Lỗi: Chưa cấu hình PC");
        }
        String ip = s.getPcIp();
        int port = s.getAgentPort() > 0 ? s.getAgentPort() : DEFAULT_AGENT_PORT;
        String token = s.getAgentToken();

        HttpURLConnection c = null;
        try {
            c = AgentAuth.connect("http://" + ip + ":" + port + "/exec", "POST",
                    ("{\"action\":\"" + action + "\"}").getBytes(StandardCharsets.UTF_8), token, 5000, 15000);
            int code = c.getResponseCode();
            String refusal = AgentAuth.refusalMessage(code);
            if (refusal != null) {
                return refusal;
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
            return L10n.t("Cannot reach the PC agent: ", "Không kết nối được agent PC: ") + e;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
