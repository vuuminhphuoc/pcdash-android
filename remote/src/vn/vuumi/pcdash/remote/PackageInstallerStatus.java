package vn.vuumi.pcdash.remote;

public final class PackageInstallerStatus {

    public static final int STATUS_PENDING_USER_ACTION = -1;
    public static final int STATUS_SUCCESS = 0;
    public static final int STATUS_FAILURE = 1;
    public static final int STATUS_FAILURE_BLOCKED = 2;
    public static final int STATUS_FAILURE_ABORTED = 3;
    public static final int STATUS_FAILURE_INVALID = 4;
    public static final int STATUS_FAILURE_CONFLICT = 5;
    public static final int STATUS_FAILURE_STORAGE = 6;
    public static final int STATUS_FAILURE_INCOMPATIBLE = 7;

    private PackageInstallerStatus() {}

    public static String getStatusMessage(int status, String extraMsg) {
        switch (status) {
            case STATUS_SUCCESS:
                return "Cập nhật thành công!";
            case STATUS_FAILURE_ABORTED:
                return "Người dùng đã hủy cài đặt";
            case STATUS_FAILURE_BLOCKED:
                return "Cài đặt bị chặn bởi thiết bị";
            case STATUS_FAILURE_CONFLICT:
                return "Xung đột với bản cài đặt hiện tại (chữ ký không khớp)";
            case STATUS_FAILURE_STORAGE:
                return "Không đủ dung lượng bộ nhớ để cài đặt";
            case STATUS_FAILURE_INCOMPATIBLE:
                return "Bản cập nhật không tương thích với thiết bị";
            case STATUS_FAILURE_INVALID:
                return "Tệp APK cập nhật không hợp lệ";
            case STATUS_FAILURE:
            default:
                if (extraMsg != null && !extraMsg.trim().isEmpty()) {
                    return "Cài đặt thất bại: " + extraMsg.trim();
                }
                return "Cài đặt thất bại";
        }
    }
}
