package vn.vuumi.pcdash.remote;

public final class ActionGuard {

    public enum AuthRequirement {
        NONE,
        CONFIRM_DIALOG,
        DEVICE_CREDENTIAL
    }

    private ActionGuard() {}

    public static boolean isDangerousAction(String action) {
        if (action == null) {
            return false;
        }
        String a = action.trim();
        return "hibernate".equalsIgnoreCase(a) || "shutdown".equalsIgnoreCase(a)
                || "restart".equalsIgnoreCase(a) || "logoff".equalsIgnoreCase(a);
    }

    /** Quên host luôn cần xác thực khi máy có khóa màn hình, không phụ thuộc công tắc lệnh nguy hiểm. */
    public static AuthRequirement checkForgetHostRequirement(boolean isDeviceSecure) {
        return isDeviceSecure ? AuthRequirement.DEVICE_CREDENTIAL : AuthRequirement.CONFIRM_DIALOG;
    }

    public static AuthRequirement checkAuthRequirement(String action, boolean requireAuthSetting, boolean isDeviceSecure) {
        if (!isDangerousAction(action)) {
            return AuthRequirement.NONE;
        }
        if (!requireAuthSetting) {
            return AuthRequirement.NONE;
        }
        if (isDeviceSecure) {
            return AuthRequirement.DEVICE_CREDENTIAL;
        }
        return AuthRequirement.CONFIRM_DIALOG;
    }
}
