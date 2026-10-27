package vn.vuumi.pcdash.remote;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class UpdateInfo {

    public static final long MAX_SIZE = 64 * 1024 * 1024; // 64 MB

    public final boolean available;
    public final int versionCode;
    public final String versionName;
    public final String sha256;
    public final long size;

    public UpdateInfo(boolean available, int versionCode, String versionName, String sha256, long size) {
        this.available = available;
        this.versionCode = versionCode;
        this.versionName = versionName != null ? versionName : "";
        this.sha256 = sha256 != null ? sha256 : "";
        this.size = size;
    }

    public static UpdateInfo parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("JSON string cannot be null");
        }

        Map<String, Object> map = MetricsParser.parseMap(json);
        if (map == null) {
            throw new IllegalArgumentException("Invalid JSON: must be a JSON object");
        }

        Set<String> allowedKeys = new HashSet<String>(Arrays.asList(
                "available", "versionCode", "versionName", "sha256", "size"
        ));
        for (String key : map.keySet()) {
            if (!allowedKeys.contains(key)) {
                throw new IllegalArgumentException("Unexpected field in update JSON: " + key);
            }
        }

        Object availableObj = map.get("available");
        if (availableObj != null) {
            if (!(availableObj instanceof Boolean)) {
                throw new IllegalArgumentException("Field 'available' must be boolean");
            }
            if (Boolean.FALSE.equals(availableObj)) {
                if (map.size() > 1) {
                    throw new IllegalArgumentException("Unexpected fields when 'available' is false");
                }
                return new UpdateInfo(false, 0, "", "", 0);
            }
        }

        // Available is true or implicitly true because fields are present
        if (!map.containsKey("versionCode")) {
            throw new IllegalArgumentException("Missing required field 'versionCode'");
        }
        if (!map.containsKey("versionName")) {
            throw new IllegalArgumentException("Missing required field 'versionName'");
        }
        if (!map.containsKey("sha256")) {
            throw new IllegalArgumentException("Missing required field 'sha256'");
        }
        if (!map.containsKey("size")) {
            throw new IllegalArgumentException("Missing required field 'size'");
        }

        Object vcObj = map.get("versionCode");
        if (!(vcObj instanceof Number) || (vcObj instanceof Double) || (vcObj instanceof Float)) {
            throw new IllegalArgumentException("Field 'versionCode' must be an integer");
        }
        int versionCode = ((Number) vcObj).intValue();
        if (versionCode <= 0) {
            throw new IllegalArgumentException("Field 'versionCode' must be positive: " + versionCode);
        }

        Object vnObj = map.get("versionName");
        if (!(vnObj instanceof String)) {
            throw new IllegalArgumentException("Field 'versionName' must be a string");
        }
        String versionName = ((String) vnObj).trim();
        if (versionName.isEmpty()) {
            throw new IllegalArgumentException("Field 'versionName' cannot be empty");
        }

        Object shaObj = map.get("sha256");
        if (!(shaObj instanceof String)) {
            throw new IllegalArgumentException("Field 'sha256' must be a string");
        }
        String sha256 = (String) shaObj;
        if (sha256.length() != 64) {
            throw new IllegalArgumentException("Field 'sha256' must be exactly 64 characters, got " + sha256.length());
        }
        for (int i = 0; i < 64; i++) {
            char c = sha256.charAt(i);
            boolean isHex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!isHex) {
                throw new IllegalArgumentException("Field 'sha256' contains non-hex character: " + c);
            }
        }

        Object szObj = map.get("size");
        if (!(szObj instanceof Number) || (szObj instanceof Double) || (szObj instanceof Float)) {
            throw new IllegalArgumentException("Field 'size' must be an integer");
        }
        long size = ((Number) szObj).longValue();
        if (size <= 0) {
            throw new IllegalArgumentException("Field 'size' must be positive: " + size);
        }
        if (size > MAX_SIZE) {
            throw new IllegalArgumentException("Field 'size' exceeds 64 MB: " + size);
        }

        return new UpdateInfo(true, versionCode, versionName, sha256.toLowerCase(Locale.ROOT), size);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UpdateInfo that = (UpdateInfo) o;
        if (available != that.available) return false;
        if (versionCode != that.versionCode) return false;
        if (size != that.size) return false;
        if (!versionName.equals(that.versionName)) return false;
        return sha256.equals(that.sha256);
    }

    @Override
    public int hashCode() {
        int result = (available ? 1 : 0);
        result = 31 * result + versionCode;
        result = 31 * result + versionName.hashCode();
        result = 31 * result + sha256.hashCode();
        result = 31 * result + (int) (size ^ (size >>> 32));
        return result;
    }

    @Override
    public String toString() {
        if (!available) {
            return "UpdateInfo[available=false]";
        }
        return "UpdateInfo[available=true, versionCode=" + versionCode
                + ", versionName=" + versionName + ", sha256=" + sha256
                + ", size=" + size + "]";
    }
}
