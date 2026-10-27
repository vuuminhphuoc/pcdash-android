package vn.vuumi.pcdash;

import vn.vuumi.pcdash.i18n.L10n;
import java.io.File;
import java.io.IOException;
import java.util.List;

public class RemoteHostController {
    private final File privateDir;
    private final RemoteActionExecutor actions;
    private final RemoteDeviceStore deviceStore;
    private final CommandLog commandLog;
    private final MetricsHistory metricsHistory;
    private final RemoteApiServer apiServer;
    private final HostTailnet hostTailnet;
    private final PairingManager pairingManager;
    private final TimeSource clock;
    private boolean running = false;
    private final Object lifecycleLock = new Object();

    public RemoteHostController(File privateDir, RemoteActionExecutor actions) throws IOException {
        this(privateDir, actions, null, null, null, TimeSource.SYSTEM, null, null);
    }

    public RemoteHostController(File privateDir, RemoteActionExecutor actions, UpdateAssetSource updateAssetSource) throws IOException {
        this(privateDir, actions, updateAssetSource, null, null, TimeSource.SYSTEM, null, null);
    }

    public RemoteHostController(File privateDir, RemoteActionExecutor actions, UpdateAssetSource updateAssetSource, CommandLog commandLog, MetricsHistory metricsHistory) throws IOException {
        this(privateDir, actions, updateAssetSource, null, null, TimeSource.SYSTEM, commandLog, metricsHistory);
    }

    public RemoteHostController(File privateDir, RemoteActionExecutor actions,
                                HostTailnet customHostTailnet,
                                PairingManager customPairingManager,
                                TimeSource clock) throws IOException {
        this(privateDir, actions, null, customHostTailnet, customPairingManager, clock, null, null);
    }

    public RemoteHostController(File privateDir, RemoteActionExecutor actions,
                                UpdateAssetSource updateAssetSource,
                                HostTailnet customHostTailnet,
                                PairingManager customPairingManager,
                                TimeSource clock) throws IOException {
        this(privateDir, actions, updateAssetSource, customHostTailnet, customPairingManager, clock, null, null);
    }

    public RemoteHostController(File privateDir, RemoteActionExecutor actions,
                                UpdateAssetSource updateAssetSource,
                                HostTailnet customHostTailnet,
                                PairingManager customPairingManager,
                                TimeSource clock,
                                CommandLog customCommandLog,
                                MetricsHistory customMetricsHistory) throws IOException {
        this.privateDir = privateDir;
        this.actions = actions;
        this.clock = clock != null ? clock : TimeSource.SYSTEM;
        if (!privateDir.exists()) {
            privateDir.mkdirs();
        }
        File devicesFile = new File(privateDir, "remote_devices.json");
        this.deviceStore = new RemoteDeviceStore(devicesFile);
        this.commandLog = customCommandLog != null ? customCommandLog : new CommandLog(new File(privateDir, "command_log.jsonl"));
        this.metricsHistory = customMetricsHistory != null ? customMetricsHistory : new MetricsHistory(new File(privateDir, "metrics_history.json"));
        this.apiServer = new RemoteApiServer(this.deviceStore, actions, updateAssetSource, this.commandLog, this.metricsHistory);
        this.hostTailnet = customHostTailnet != null ? customHostTailnet : new HostTailnet();
        this.pairingManager = customPairingManager != null ? customPairingManager : new PairingManager(this.deviceStore, this.hostTailnet, this.clock);
    }

    public void start() throws IOException {
        synchronized (lifecycleLock) {
            if (running) return;
            running = true;
            apiServer.start(RemoteApiServer.PORT);
            if (pairingManager != null) {
                try {
                    pairingManager.start();
                } catch (IOException ignored) {}
            }
        }
    }

    public void close() {
        synchronized (lifecycleLock) {
            running = false;
            apiServer.close();
            if (pairingManager != null) {
                pairingManager.close();
            }
            if (hostTailnet != null) {
                hostTailnet.stop();
            }
        }
    }

    public PairingManager.PairingCode beginPairing() throws PairingManager.PairingException {
        synchronized (lifecycleLock) {
            if (pairingManager == null) {
                throw new PairingManager.PairingException(L10n.t("The pairing service is not started", "Dịch vụ ghép đôi chưa khởi tạo"));
            }
            return pairingManager.begin();
        }
    }

    public void cancelPairing() {
        synchronized (lifecycleLock) {
            if (pairingManager != null) {
                pairingManager.cancel();
            }
        }
    }

    public RemoteDeviceStore deviceStore() {
        return deviceStore;
    }

    public RemoteApiServer apiServer() {
        return apiServer;
    }

    public CommandLog commandLog() {
        return commandLog;
    }

    public MetricsHistory metricsHistory() {
        return metricsHistory;
    }

    public HostTailnet getHostTailnet() {
        return hostTailnet;
    }

    public PairingManager pairingManager() {
        synchronized (lifecycleLock) {
            return pairingManager;
        }
    }

    public List<RemoteDeviceStore.RemoteDevice> listDevices() {
        return deviceStore.list();
    }

    public RemoteDeviceStore.RemoteDevice removeDevice(String id) throws IOException {
        return deviceStore.remove(id);
    }
}
