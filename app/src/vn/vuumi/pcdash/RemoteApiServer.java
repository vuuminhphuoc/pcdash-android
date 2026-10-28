package vn.vuumi.pcdash;

import vn.vuumi.pcdash.i18n.L10n;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
public class RemoteApiServer implements ThreadFactory, Runnable {


    public static final int PORT = 8091;
    public static final int DEFAULT_MAX_THREADS = 8;
    public static final int DEFAULT_QUEUE_SIZE = 8;
    public static final int DEFAULT_REQUEST_TIMEOUT_MS = 10000;
    public static final int APK_DOWNLOAD_TIMEOUT_MS = 600000;
    public static final int MAX_HEADER_SIZE = 8192;
    public static final int MAX_HEADER_COUNT = 50;
    public static final int MAX_BODY_SIZE = 16384;
    private final RemoteDeviceStore devices;
    private final RemoteActionExecutor actions;
    private final UpdateAssetSource updateAssetSource;
    private final CommandLog commandLog;
    private final MetricsHistory metricsHistory;
    private final int maxThreads;
    private final int requestTimeoutMs;
    private final AtomicBoolean apkDownloading = new AtomicBoolean(false);
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private final AtomicInteger threadCounter = new AtomicInteger(1);
    private int boundPort = PORT;
    private ThreadPoolExecutor threadPool;

    public RemoteApiServer(RemoteDeviceStore devices, RemoteActionExecutor actions) {
        this(devices, actions, null, null, null, DEFAULT_REQUEST_TIMEOUT_MS, DEFAULT_MAX_THREADS);
    }

    public RemoteApiServer(RemoteDeviceStore devices, RemoteActionExecutor actions, UpdateAssetSource updateAssetSource) {
        this(devices, actions, updateAssetSource, null, null, DEFAULT_REQUEST_TIMEOUT_MS, DEFAULT_MAX_THREADS);
    }

    public RemoteApiServer(RemoteDeviceStore devices, RemoteActionExecutor actions, int requestTimeoutMs) {
        this(devices, actions, null, null, null, requestTimeoutMs, DEFAULT_MAX_THREADS);
    }

    public RemoteApiServer(RemoteDeviceStore devices, RemoteActionExecutor actions, UpdateAssetSource updateAssetSource, int requestTimeoutMs) {
        this(devices, actions, updateAssetSource, null, null, requestTimeoutMs, DEFAULT_MAX_THREADS);
    }

    public RemoteApiServer(RemoteDeviceStore devices, RemoteActionExecutor actions, int requestTimeoutMs, int maxThreads) {
        this(devices, actions, null, null, null, requestTimeoutMs, maxThreads);
    }

    public RemoteApiServer(RemoteDeviceStore devices, RemoteActionExecutor actions, UpdateAssetSource updateAssetSource, int requestTimeoutMs, int maxThreads) {
        this(devices, actions, updateAssetSource, null, null, requestTimeoutMs, maxThreads);
    }

    public RemoteApiServer(RemoteDeviceStore devices, RemoteActionExecutor actions, UpdateAssetSource updateAssetSource, CommandLog commandLog, MetricsHistory metricsHistory) {
        this(devices, actions, updateAssetSource, commandLog, metricsHistory, DEFAULT_REQUEST_TIMEOUT_MS, DEFAULT_MAX_THREADS);
    }

    public RemoteApiServer(RemoteDeviceStore devices, RemoteActionExecutor actions, UpdateAssetSource updateAssetSource, CommandLog commandLog, MetricsHistory metricsHistory, int requestTimeoutMs, int maxThreads) {
        this.devices = devices;
        this.actions = actions;
        this.updateAssetSource = updateAssetSource;
        this.commandLog = commandLog;
        this.metricsHistory = metricsHistory;
        this.requestTimeoutMs = requestTimeoutMs;
        this.maxThreads = maxThreads;
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized void start() throws IOException {
        start(PORT);
    }

    // Chỉ nghe loopback: truy cập từ ngoài đi qua tsnet của host (HostTailnet chuyển tiếp vào đây).
    private volatile boolean loopbackOnly = true;

    public void setLoopbackOnly(boolean loopbackOnly) {
        this.loopbackOnly = loopbackOnly;
    }

    public synchronized void start(int port) throws IOException {
        if (running) return;
        serverSocket = loopbackOnly
                ? new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                : new ServerSocket(port, 50);
        serverSocket.setReuseAddress(true);
        boundPort = serverSocket.getLocalPort();
        running = true;
        threadPool = new ThreadPoolExecutor(
                Math.min(2, maxThreads),
                maxThreads,
                30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(DEFAULT_QUEUE_SIZE),
                this
        );

        Thread loopThread = new Thread(this, "pcdash-remote-api");
        loopThread.setDaemon(true);
        loopThread.start();
    }

    public int getPort() {
        return boundPort;
    }

    public InetAddress getBindAddress() {
        return serverSocket != null ? serverSocket.getInetAddress() : null;
    }

    public synchronized void close() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
            serverSocket = null;
        }
        if (threadPool != null) {
            threadPool.shutdownNow();
            threadPool = null;
        }
    }

    ThreadPoolExecutor getThreadPool() {
        return threadPool;
    }
    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, "pcdash-remote-pool-" + threadCounter.getAndIncrement());
        t.setDaemon(true);
        return t;
    }

    @Override
    public void run() {
        loop();
    }


    private void loop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                try {
                    threadPool.execute(() -> handle(socket));
                } catch (RejectedExecutionException rejected) {
                    sendQuickResponse(socket, 503, "{\"error\":\"service_unavailable\"}");
                }
            } catch (IOException e) {
                // server closed or connection error
            }
        }
    }

    private static void sendQuickResponse(Socket s, int code, String jsonBody) {
        try {
            byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
            String reason = code == 503 ? "Service Unavailable" : "Error";
            String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            s.setSoTimeout(2000);
            OutputStream os = s.getOutputStream();
            os.write(head.getBytes(StandardCharsets.UTF_8));
            os.write(body);
            os.flush();
        } catch (IOException ignored) {
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {}
        }
    }

    private void handle(Socket socket) {
        try {
            long deadline = System.currentTimeMillis() + requestTimeoutMs;
            int remainingMs = (int) (deadline - System.currentTimeMillis());
            if (remainingMs <= 0) return;
            socket.setSoTimeout(remainingMs);

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = in.readLine();
            if (requestLine == null) return;
            if (requestLine.length() > MAX_HEADER_SIZE) {
                sendResponse(socket, 431, "application/json; charset=utf-8",
                        "{\"error\":\"header_too_large\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }

            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String fullPath = parts.length > 1 ? parts[1] : "/";
            int queryIndex = fullPath.indexOf('?');
            String path = queryIndex >= 0 ? fullPath.substring(0, queryIndex) : fullPath;

            int totalHeaderSize = requestLine.length();
            int headerCount = 0;
            Map<String, String> headers = new HashMap<String, String>();
            String line;
            while (true) {
                remainingMs = (int) (deadline - System.currentTimeMillis());
                if (remainingMs <= 0) {
                    sendResponse(socket, 408, "application/json; charset=utf-8",
                            "{\"error\":\"request_timeout\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                socket.setSoTimeout(remainingMs);
                line = in.readLine();
                if (line == null || line.isEmpty()) {
                    break;
                }
                headerCount++;
                totalHeaderSize += line.length();
                if (headerCount > MAX_HEADER_COUNT || totalHeaderSize > MAX_HEADER_SIZE) {
                    sendResponse(socket, 431, "application/json; charset=utf-8",
                            "{\"error\":\"header_too_large\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim().toLowerCase();
                    String value = line.substring(colon + 1).trim();
                    headers.put(name, value);
                }
            }

            // Messages produced for this request (action results, errors) follow the remote's language.
            L10n.setThreadLanguage(headers.get("accept-language"));

            // Body POST check & bounded read
            int contentLength = 0;
            String clHeader = headers.get("content-length");
            if (clHeader != null) {
                try {
                    contentLength = Integer.parseInt(clHeader.trim());
                } catch (NumberFormatException e) {
                    sendResponse(socket, 400, "application/json; charset=utf-8",
                            "{\"error\":\"invalid_content_length\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if (contentLength < 0 || contentLength > MAX_BODY_SIZE) {
                    sendResponse(socket, 413, "application/json; charset=utf-8",
                            "{\"error\":\"payload_too_large\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            if (contentLength > 0) {
                char[] bodyBuf = new char[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    remainingMs = (int) (deadline - System.currentTimeMillis());
                    if (remainingMs <= 0) {
                        sendResponse(socket, 408, "application/json; charset=utf-8",
                                "{\"error\":\"request_timeout\"}".getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    socket.setSoTimeout(remainingMs);
                    int read = in.read(bodyBuf, totalRead, contentLength - totalRead);
                    if (read < 0) break;
                    totalRead += read;
                }
            }

            // Authentication verification: Authorization: Bearer <token>
            String authHeader = headers.get("authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                sendResponse(socket, 401, "application/json; charset=utf-8",
                        "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }

            String token = authHeader.substring(7).trim();
            RemoteDeviceStore.RemoteDevice callerDevice = devices.identify(token);
            if (callerDevice == null) {
                sendResponse(socket, 401, "application/json; charset=utf-8",
                        "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }

            // Authenticated routing. "?pc=<id>" picks one of several PCs; without it, the first PC.
            final String pcId = pcParam(fullPath);
            if ("GET".equalsIgnoreCase(method) && "/v1/pcs".equals(path)) {
                String pcsJson = actions != null ? actions.pcsJson() : null;
                if (pcsJson == null) {
                    sendResponse(socket, 404, "application/json; charset=utf-8",
                            "{\"error\":\"not_found\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                sendResponse(socket, 200, "application/json; charset=utf-8",
                        pcsJson.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("GET".equalsIgnoreCase(method) && "/v1/status".equals(path)) {
                String statusJson = actions.statusJson(pcId);
                sendResponse(socket, 200, "application/json; charset=utf-8",
                        statusJson.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("GET".equalsIgnoreCase(method) && "/v1/metrics".equals(path)) {
                String metricsJson = actions != null ? actions.metricsJson(pcId) : null;
                if (metricsJson == null) {
                    metricsJson = "{\"ts\":" + System.currentTimeMillis() + ",\"pc\":{\"online\":false},\"phone\":null}";
                }
                sendResponse(socket, 200, "application/json; charset=utf-8",
                        metricsJson.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("GET".equalsIgnoreCase(method) && "/v1/quota".equals(path)) {
                boolean refresh = false;
                int qIdx = fullPath.indexOf('?');
                if (qIdx >= 0) {
                    String q = fullPath.substring(qIdx + 1);
                    String[] pairs = q.split("&");
                    for (String pair : pairs) {
                        int eq = pair.indexOf('=');
                        if (eq > 0) {
                            String k = pair.substring(0, eq).trim();
                            String v = pair.substring(eq + 1).trim();
                            if ("refresh".equalsIgnoreCase(k) && ("1".equals(v) || "true".equalsIgnoreCase(v))) {
                                refresh = true;
                            }
                        }
                    }
                }
                String quotaJson = actions != null ? actions.quotaJson(pcId, refresh) : null;
                if (quotaJson == null) {
                    quotaJson = "{\"providers\":[],\"stale\":true}";
                }
                sendResponse(socket, 200, "application/json; charset=utf-8",
                        quotaJson.getBytes(StandardCharsets.UTF_8));
                return;
            }


            if ("GET".equalsIgnoreCase(method) && "/v1/update/info".equals(path)) {
                byte[] infoBytes = null;
                if (updateAssetSource != null) {
                    try {
                        InputStream is = updateAssetSource.openInfo();
                        if (is != null) {
                            try {
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                byte[] buf = new byte[1024];
                                int r;
                                while ((r = is.read(buf)) != -1) {
                                    baos.write(buf, 0, r);
                                }
                                if (baos.size() > 0) {
                                    infoBytes = baos.toByteArray();
                                }
                            } finally {
                                try { is.close(); } catch (IOException ignored) {}
                            }
                        }
                    } catch (IOException ignored) {}
                }
                if (infoBytes == null) {
                    infoBytes = "{\"available\":false}".getBytes(StandardCharsets.UTF_8);
                }
                sendResponse(socket, 200, "application/json; charset=utf-8", infoBytes);
                return;
            }

            if ("GET".equalsIgnoreCase(method) && "/v1/update/apk".equals(path)) {
                if (updateAssetSource == null) {
                    sendResponse(socket, 404, "application/json; charset=utf-8",
                            "{\"error\":\"not_found\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                long size = updateAssetSource.apkSize();
                if (size <= 0) {
                    sendResponse(socket, 404, "application/json; charset=utf-8",
                            "{\"error\":\"not_found\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }

                if (!apkDownloading.compareAndSet(false, true)) {
                    sendResponse(socket, 503, "application/json; charset=utf-8",
                            "{\"error\":\"download_in_progress\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }

                try {
                    handleApkDownload(socket, size);
                } finally {
                    apkDownloading.set(false);
                }
                return;
            }

            if ("GET".equalsIgnoreCase(method) && "/v1/log".equals(path)) {
                int limit = 50;
                int qIdx = fullPath.indexOf('?');
                if (qIdx >= 0) {
                    String q = fullPath.substring(qIdx + 1);
                    String[] pairs = q.split("&");
                    for (String pair : pairs) {
                        int eq = pair.indexOf('=');
                        if (eq > 0) {
                            String k = pair.substring(0, eq).trim();
                            String v = pair.substring(eq + 1).trim();
                            if ("limit".equalsIgnoreCase(k)) {
                                try {
                                    int parsed = Integer.parseInt(v);
                                    if (parsed <= 0) {
                                        limit = 50;
                                    } else if (parsed > 200) {
                                        limit = 200;
                                    } else {
                                        limit = parsed;
                                    }
                                } catch (NumberFormatException e) {
                                    limit = 50;
                                }
                            }
                        }
                    }
                }
                String logJson = commandLog != null ? commandLog.toJson(limit) : "{\"entries\":[]}";
                sendResponse(socket, 200, "application/json; charset=utf-8",
                        logJson.getBytes(StandardCharsets.UTF_8));
                return;
            }

            if ("GET".equalsIgnoreCase(method) && "/v1/history".equals(path)) {
                int minutes = 60;
                int qIdx = fullPath.indexOf('?');
                if (qIdx >= 0) {
                    String q = fullPath.substring(qIdx + 1);
                    String[] pairs = q.split("&");
                    for (String pair : pairs) {
                        int eq = pair.indexOf('=');
                        if (eq > 0) {
                            String k = pair.substring(0, eq).trim();
                            String v = pair.substring(eq + 1).trim();
                            if ("minutes".equalsIgnoreCase(k)) {
                                try {
                                    int parsed = Integer.parseInt(v);
                                    if (parsed <= 0) {
                                        minutes = 60;
                                    } else if (parsed > 360) {
                                        minutes = 360;
                                    } else {
                                        minutes = parsed;
                                    }
                                } catch (NumberFormatException e) {
                                    minutes = 60;
                                }
                            }
                        }
                    }
                }
                // The executor keeps one history per PC; without one, the server's own history.
                String historyJson = actions != null ? actions.historyJson(pcId, minutes) : null;
                if (historyJson == null && metricsHistory != null) {
                    historyJson = metricsHistory.toHistoryJson(System.currentTimeMillis(), minutes);
                }
                if (historyJson == null) {
                    historyJson = "{\"intervalMs\":30000,\"ts\":[],\"cpu\":[],\"gpu\":[],\"ram\":[],\"down\":[],\"up\":[],\"cpuTemp\":[],\"gpuTemp\":[],\"online\":[]}";
                }
                sendResponse(socket, 200, "application/json; charset=utf-8",
                        historyJson.getBytes(StandardCharsets.UTF_8));
                return;
            }

            if ("POST".equalsIgnoreCase(method) && path.startsWith("/v1/")) {
                String action = path.substring(4);
                if (action.matches("^(wake|sleep|hibernate|shutdown|restart|lock|logoff|abort)$")) {
                    String detail = "";
                    boolean ok = true;
                    try {
                        detail = actions.doAction(pcId, action);
                        ok = !RemoteActionExecutor.isFailure(detail);
                    } catch (Exception e) {
                        ok = false;
                        detail = e.getMessage() != null ? e.getMessage() : e.toString();
                    }

                    if (commandLog != null) {
                        String devName = (callerDevice != null && callerDevice.name != null && !callerDevice.name.trim().isEmpty())
                                ? callerDevice.name.trim() : "remote";
                        String label = actions != null ? actions.pcLabel(pcId) : null;
                        commandLog.log(System.currentTimeMillis(), devName, "remote",
                                label != null ? action + " · " + label : action, ok);
                    }

                    String safeDetail = detail != null ? detail.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") : "";
                    String body = "{\"ok\":" + ok + ",\"action\":\"" + action + "\",\"detail\":\"" + safeDetail + "\"}";
                    sendResponse(socket, 200, "application/json; charset=utf-8",
                            body.getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }

            sendResponse(socket, 404, "application/json; charset=utf-8",
                    "{\"error\":\"not_found\"}".getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        } finally {
            L10n.setThreadLanguage(null);
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    /** The "pc" query parameter if it is a valid PC id, else null (the first PC). */
    static String pcParam(String fullPath) {
        int q = fullPath.indexOf('?');
        if (q < 0) return null;
        for (String pair : fullPath.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "pc".equals(pair.substring(0, eq).trim())) {
                String v = pair.substring(eq + 1).trim();
                return v.matches("^[A-Za-z0-9_-]{1,24}$") ? v : null;
            }
        }
        return null;
    }

    private void sendResponse(Socket s, int code, String contentType, byte[] body) throws IOException {
        String reason;
        switch (code) {
            case 200: reason = "OK"; break;
            case 400: reason = "Bad Request"; break;
            case 401: reason = "Unauthorized"; break;
            case 404: reason = "Not Found"; break;
            case 408: reason = "Request Timeout"; break;
            case 413: reason = "Payload Too Large"; break;
            case 431: reason = "Request Header Fields Too Large"; break;
            case 503: reason = "Service Unavailable"; break;
            default: reason = "Error"; break;
        }
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream os = s.getOutputStream();
        os.write(head.getBytes(StandardCharsets.UTF_8));
        os.write(body);
        os.flush();
    }

    private void handleApkDownload(Socket socket, long size) throws IOException {
        InputStream apkStream = updateAssetSource.openApk();
        if (apkStream == null) {
            sendResponse(socket, 404, "application/json; charset=utf-8",
                    "{\"error\":\"not_found\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }

        try {
            socket.setSoTimeout(APK_DOWNLOAD_TIMEOUT_MS);
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/vnd.android.package-archive\r\n"
                    + "Content-Length: " + size + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream os = socket.getOutputStream();
            os.write(head.getBytes(StandardCharsets.UTF_8));
            os.flush();

            byte[] buf = new byte[16384];
            long deadline = System.currentTimeMillis() + APK_DOWNLOAD_TIMEOUT_MS;
            long written = 0;
            while (written < size && System.currentTimeMillis() <= deadline) {
                int toRead = (int) Math.min(buf.length, size - written);
                int r = apkStream.read(buf, 0, toRead);
                if (r < 0) {
                    break;
                }
                os.write(buf, 0, r);
                os.flush();
                written += r;
            }
        } finally {
            try {
                apkStream.close();
            } catch (IOException ignored) {}
        }
    }
}
