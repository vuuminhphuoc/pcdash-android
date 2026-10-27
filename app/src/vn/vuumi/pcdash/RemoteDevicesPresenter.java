package vn.vuumi.pcdash;
import vn.vuumi.pcdash.net.PcnetState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class RemoteDevicesPresenter {

    public static class DeviceRow {
        public final String id;
        public final String name;
        public final String nodeId;
        public final long createdAt;
        public final long createdAtMs;
        public final long lastUsedAt;
        public final long lastUsedAtMs;

        public DeviceRow(String id, String name, String nodeId, long createdAt) {
            this(id, name, nodeId, createdAt, 0L);
        }

        public DeviceRow(String id, String name, String nodeId, long createdAt, long lastUsedAt) {
            this.id = id != null ? id : "";
            this.name = name != null ? name : "";
            this.nodeId = nodeId != null ? nodeId : "";
            this.createdAt = createdAt;
            this.createdAtMs = createdAt;
            this.lastUsedAt = lastUsedAt;
            this.lastUsedAtMs = lastUsedAt;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getNodeId() {
            return nodeId;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public long getCreatedAtMs() {
            return createdAt;
        }

        public long getLastUsedAt() {
            return lastUsedAt;
        }

        public long getLastUsedAtMs() {
            return lastUsedAt;
        }

        @Override
        public String toString() {
            return "DeviceRow{id='" + id + "', name='" + name + "', nodeId='" + nodeId + "', createdAt=" + createdAt + ", lastUsedAt=" + lastUsedAt + "}";
        }
    }

    public static class DeleteAction {
        public final String targetDeviceId;
        public final String targetDeviceName;

        public DeleteAction(String targetDeviceId, String targetDeviceName) {
            this.targetDeviceId = targetDeviceId != null ? targetDeviceId : "";
            this.targetDeviceName = targetDeviceName != null ? targetDeviceName : "";
        }

        public String getTargetDeviceId() {
            return targetDeviceId;
        }

        public String getTargetDeviceName() {
            return targetDeviceName;
        }

        public RemoteDeviceStore.RemoteDevice execute(RemoteHostController controller) throws IOException {
            if (controller == null) {
                throw new IllegalArgumentException("RemoteHostController cannot be null");
            }
            return controller.removeDevice(targetDeviceId);
        }

        @Override
        public String toString() {
            return "DeleteAction{targetDeviceId='" + targetDeviceId + "', targetDeviceName='" + targetDeviceName + "'}";
        }
    }

    public static class ViewState {
        public final boolean hasActiveCode;
        public final String pairingCode;
        public final String countdownText;
        public final long remainingMs;
        public final List<DeviceRow> devices;
        public final boolean hasDevices;
        public final String tailscaleStatus;
        public final boolean showLoginButton;
        public final String authUrl;

        public ViewState(boolean hasActiveCode, String pairingCode, String countdownText, long remainingMs, List<DeviceRow> devices) {
            this(hasActiveCode, pairingCode, countdownText, remainingMs, devices, "Chưa kết nối", false, "");
        }

        public ViewState(boolean hasActiveCode, String pairingCode, String countdownText, long remainingMs, List<DeviceRow> devices,
                         String tailscaleStatus, boolean showLoginButton, String authUrl) {
            this.hasActiveCode = hasActiveCode;
            this.pairingCode = pairingCode;
            this.countdownText = countdownText;
            this.remainingMs = remainingMs;
            this.devices = devices != null ? devices : Collections.<DeviceRow>emptyList();
            this.hasDevices = !this.devices.isEmpty();
            this.tailscaleStatus = tailscaleStatus != null ? tailscaleStatus : "Chưa kết nối";
            this.showLoginButton = showLoginButton;
            this.authUrl = authUrl != null ? authUrl : "";
        }

        public boolean hasActiveCode() {
            return hasActiveCode;
        }

        public String getPairingCode() {
            return pairingCode;
        }

        public String getCountdownText() {
            return countdownText;
        }

        public long getRemainingMs() {
            return remainingMs;
        }

        public List<DeviceRow> getDevices() {
            return devices;
        }

        public boolean hasDevices() {
            return hasDevices;
        }

        public String getTailscaleStatus() {
            return tailscaleStatus;
        }

        public boolean isShowLoginButton() {
            return showLoginButton;
        }

        public String getAuthUrl() {
            return authUrl;
        }

        @Override
        public String toString() {
            return "ViewState{hasActiveCode=" + hasActiveCode + ", pairingCode=" + (pairingCode != null ? "[PRESENT]" : "null")
                    + ", countdown=" + countdownText + ", deviceCount=" + devices.size() + "}";
        }
    }

    public static String formatCountdown(long remainingMs) {
        if (remainingMs <= 0L) {
            return "00:00";
        }
        long totalSeconds = (remainingMs + 999L) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    public static DeleteAction createDeleteAction(DeviceRow row) {
        if (row == null) {
            throw new IllegalArgumentException("DeviceRow cannot be null");
        }
        return new DeleteAction(row.id, row.name);
    }

    public static DeleteAction createDeleteAction(String deviceId, String deviceName) {
        return new DeleteAction(deviceId, deviceName);
    }

    public static ViewState present(PairingManager.PairingCode code, List<RemoteDeviceStore.RemoteDevice> devices, long nowEpochMs) {
        return present(code, devices, nowEpochMs, null);
    }

    public static ViewState present(PairingManager.PairingCode code, List<RemoteDeviceStore.RemoteDevice> devices, long nowEpochMs, PcnetState tailnetState) {
        boolean active = false;
        String pCode = null;
        String cText = null;
        long remMs = 0L;

        if (code != null) {
            long rem = code.expiresAtEpochMs - nowEpochMs;
            boolean stateActive = code.getState() == null
                    || code.getState() == PairingManager.SessionState.ACTIVE
                    || code.getState() == PairingManager.SessionState.RESERVED;
            if (rem > 0L && stateActive) {
                active = true;
                pCode = code.getCode();
                cText = formatCountdown(rem);
                remMs = rem;
            }
        }

        List<DeviceRow> rows = new ArrayList<DeviceRow>();
        if (devices != null) {
            for (RemoteDeviceStore.RemoteDevice d : devices) {
                if (d != null) {
                    rows.add(new DeviceRow(d.id, d.name, d.nodeId, d.createdAt, d.lastUsedAt));
                }
            }
        }

        String tsStatus;
        boolean showLogin = false;
        String aUrl = "";
        if (tailnetState == null) {
            tsStatus = "Chưa kết nối";
        } else if (tailnetState.isRunning()) {
            String ip = (tailnetState.tailnetIps != null && !tailnetState.tailnetIps.isEmpty())
                    ? tailnetState.tailnetIps.get(0)
                    : "";
            tsStatus = "Đang chạy" + (!ip.isEmpty() ? " (" + ip + ")" : "");
        } else if (tailnetState.isNeedsLogin()) {
            tsStatus = "Cần đăng nhập Tailscale";
            showLogin = true;
            aUrl = tailnetState.authUrl != null ? tailnetState.authUrl : "";
        } else if (tailnetState.isStarting()) {
            tsStatus = "Đang khởi động...";
        } else if (tailnetState.isError()) {
            tsStatus = "Lỗi: " + (tailnetState.error != null ? tailnetState.error : "");
        } else if (tailnetState.isStopped()) {
            tsStatus = "Đã dừng";
        } else {
            tsStatus = tailnetState.state != null ? tailnetState.state : "Chưa kết nối";
        }

        return new ViewState(active, pCode, cText, remMs, Collections.unmodifiableList(rows), tsStatus, showLogin, aUrl);
    }
}
