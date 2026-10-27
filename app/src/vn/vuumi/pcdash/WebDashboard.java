package vn.vuumi.pcdash;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class WebDashboard {
    public static final int PORT = 8090;
    static final int MAX_HEADER_BYTES = 8192;
    static final int MAX_HEADER_LINES = 64;

    static final class HeaderTooLargeException extends IOException {
    }

    /** Reads one LF or CRLF terminated line, charging every byte to budget[0]; null at end of stream. */
    static String readLimitedLine(InputStream in, int[] budget) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (--budget[0] < 0) throw new HeaderTooLargeException();
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
        }
        if (c == -1 && buf.size() == 0) return null;
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private final Context ctx;
    private final CommandLog commandLog;
    private final PcSettingsSource settings;
    private volatile boolean running = false;
    private ServerSocket server;
    private ExecutorService requestPool;
    private String page = "";

    public WebDashboard(Context c) {
        this(c, null, null);
    }

    public WebDashboard(Context c, CommandLog commandLog) {
        this(c, commandLog, null);
    }

    public WebDashboard(Context c, CommandLog commandLog, PcSettingsSource settings) {
        this.ctx = c;
        this.commandLog = commandLog;
        this.settings = settings != null ? settings : resolveSettings();
    }

    private static PcSettingsSource resolveSettings() {
        try {
            java.lang.reflect.Method m = PcConn.class.getMethod("getSettings");
            Object obj = m.invoke(null);
            if (obj instanceof PcSettingsSource) return (PcSettingsSource) obj;
        } catch (Throwable ignored) {}
        return null;
    }

    public void start() {
        running = true;
        requestPool = Executors.newFixedThreadPool(4, new ThreadFactory() {
            private int count = 1;
            @Override
            public synchronized Thread newThread(Runnable r) {
                Thread t = new Thread(r, "pcdash-web-req-" + (count++));
                t.setDaemon(true);
                return t;
            }
        });
        page = loadAsset("dash.html");
        Thread t = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "pcdash-web");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        if (requestPool != null) {
            requestPool.shutdownNow();
            requestPool = null;
        }
    }

    private String loadAsset(String name) {
        try {
            InputStream is = ctx.getAssets().open(name);
            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            int ch;
            while ((ch = r.read()) != -1) sb.append((char) ch);
            r.close();
            return sb.toString();
        } catch (IOException e) {
            return "<h1>PCDash</h1><p>Lỗi đọc asset: " + e + "</p>";
        }
    }

    private void loop() {
        try {
            server = new ServerSocket(PORT);
            server.setReuseAddress(true);
        } catch (IOException e) {
            return;
        }
        while (running) {
            try {
                final Socket s = server.accept();
                ExecutorService pool = requestPool;
                if (pool != null && !pool.isShutdown()) {
                    pool.execute(new Runnable() {
                        @Override public void run() { handle(s); }
                    });
                } else {
                    try { s.close(); } catch (IOException ignored) {}
                }
            } catch (IOException e) {
                // server closed hoặc lỗi tạm thời
            }
        }
    }

    private void handle(Socket s) {
        try {
            if (!LanAddressPolicy.isAllowedDashboardPeer(s.getInetAddress())) {
                return;
            }
            s.setSoTimeout(10000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            int[] budget = { MAX_HEADER_BYTES };
            String request = readLimitedLine(in, budget);
            if (request == null) return;
            String[] parts = request.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";
            int qi = path.indexOf('?');
            String query = qi >= 0 ? path.substring(qi + 1) : "";
            if (qi >= 0) path = path.substring(0, qi);

            Map<String, String> headers = new HashMap<String, String>();
            String line;
            int headerLines = 0;
            while ((line = readLimitedLine(in, budget)) != null && !line.isEmpty()) {
                if (++headerLines > MAX_HEADER_LINES) throw new HeaderTooLargeException();
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(), line.substring(i + 1).trim());
            }

            if ("OPTIONS".equalsIgnoreCase(method)) {
                send(s, 403, "application/json", "{\"error\":\"forbidden\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }

            if (path.equals("/")) {
                send(s, 200, "text/html; charset=utf-8", page.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/healthz")) {
                send(s, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/api/status")) {
                send(s, 200, "application/json", PcConn.statusJson().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("POST".equalsIgnoreCase(method) && path.startsWith("/api/")) {
                String action = path.substring(5);
                if (!action.matches("wake|sleep|hibernate|shutdown|abort")) {
                    send(s, 404, "application/json", "{\"error\":\"unknown_action\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }

                String expectedToken = null;
                PcSettingsSource src = settings != null ? settings : resolveSettings();
                if (src != null) {
                    expectedToken = src.getWebToken();
                }
                String provided = headers.get("x-pcdash-token");
                boolean authorized = false;
                if (expectedToken != null && !expectedToken.isEmpty() && provided != null && !provided.isEmpty()) {
                    byte[] a = provided.getBytes(StandardCharsets.UTF_8);
                    byte[] b = expectedToken.getBytes(StandardCharsets.UTF_8);
                    authorized = MessageDigest.isEqual(a, b);
                }

                if (!authorized) {
                    if (commandLog != null) {
                        commandLog.log(System.currentTimeMillis(), "web", "web", action, false);
                    }
                    send(s, 401, "application/json", "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }

                String rawDetail = "";
                boolean ok = true;
                try {
                    rawDetail = PcConn.doAction(action);
                    if (rawDetail != null) {
                        String lower = rawDetail.toLowerCase();
                        if (lower.startsWith("error") || lower.startsWith("lỗi") || lower.startsWith("agent từ chối") || lower.startsWith("agent pc chưa")) {
                            ok = false;
                        }
                    }
                } catch (Exception e) {
                    ok = false;
                    rawDetail = e.getMessage() != null ? e.getMessage() : e.toString();
                }
                if (commandLog != null) {
                    commandLog.log(System.currentTimeMillis(), "web", "web", action, ok);
                }
                String detail = rawDetail != null ? rawDetail.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") : "";
                send(s, 200, "application/json",
                        ("{\"ok\":" + ok + ",\"detail\":\"" + detail + "\"}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(s, 404, "application/json", "{\"error\":\"not_found\"}".getBytes(StandardCharsets.UTF_8));
        } catch (HeaderTooLargeException e) {
            try {
                send(s, 431, "application/json", "{\"error\":\"header_too_large\"}".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        } catch (IOException ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private void send(Socket s, int code, String ctype, byte[] body) throws IOException {
        String reason;
        switch (code) {
            case 200: reason = "OK"; break;
            case 401: reason = "Unauthorized"; break;
            case 403: reason = "Forbidden"; break;
            case 404: reason = "Not Found"; break;
            case 431: reason = "Request Header Fields Too Large"; break;
            default: reason = "Error"; break;
        }
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + ctype + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream os = s.getOutputStream();
        os.write(head.getBytes(StandardCharsets.UTF_8));
        os.write(body);
        os.flush();
    }

    public static String infoText(Context ctx) {
        HostTailnet tailnet = null;
        try {
            Class<?> ds = Class.forName("vn.vuumi.pcdash.DashService");
            java.lang.reflect.Method m = ds.getMethod("getHostTailnet");
            Object res = m.invoke(null);
            if (res instanceof HostTailnet) {
                tailnet = (HostTailnet) res;
            }
        } catch (Throwable ignored) {}
        return infoText(ctx, tailnet);
    }

    public static String infoText(Context ctx, HostTailnet tailnet) {
        StringBuilder sb = new StringBuilder();
        sb.append("Web dashboard (cùng LAN / Tailscale):\n");
        try {
            java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                if (name != null && name.toLowerCase().startsWith("tun")) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof java.net.Inet4Address) {
                        sb.append("  ").append(name).append(": http://")
                          .append(ia.getAddress().getHostAddress()).append(":").append(PORT).append("\n");
                    }
                }
            }
        } catch (Exception ignored) {}
        if (tailnet != null && tailnet.isRunning()) {
            String tsIp = tailnet.tailnetIp();
            if (tsIp != null && !tsIp.isEmpty()) {
                sb.append("  tailscale: http://").append(tsIp).append(":").append(PORT).append("\n");
            }
        }
        sb.append("Lưu ý: nút Wake cần điện thoại đang trong LAN nhà.");
        return sb.toString();
    }
}
