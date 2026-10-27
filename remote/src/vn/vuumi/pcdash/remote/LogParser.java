package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.i18n.L10n;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class LogParser {

    private static final int MAX_JSON_LEN = 128 * 1024; // 128 KB
    private static final int MAX_ENTRIES = 500;
    private static final int MAX_STRING_LEN = 128;

    private LogParser() {}

    public static final class LogEntry {
        public final long ts;
        public final String device;
        public final String source;
        public final String action;
        public final boolean ok;

        public LogEntry(long ts, String device, String source, String action, boolean ok) {
            this.ts = ts;
            this.device = device != null ? device : "";
            this.source = source != null ? source : "";
            this.action = action != null ? action : "";
            this.ok = ok;
        }

        @Override
        public String toString() {
            return "LogEntry[ts=" + ts + ", device=" + device + ", action=" + action + ", ok=" + ok + "]";
        }
    }

    public static final class LogResponse {
        public final List<LogEntry> entries;

        public LogResponse(List<LogEntry> entries) {
            this.entries = entries != null
                    ? Collections.unmodifiableList(entries)
                    : Collections.<LogEntry>emptyList();
        }
    }

    public static LogResponse parse(String json) throws IllegalArgumentException {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException(L10n.t("Empty or null JSON", "JSON rỗng hoặc null"));
        }
        if (json.length() > MAX_JSON_LEN) {
            throw new IllegalArgumentException(L10n.t("JSON larger than the limit", "Kích thước JSON vượt quá giới hạn"));
        }
        Map<String, Object> map = MetricsParser.parseMap(json);
        if (map == null) {
            throw new IllegalArgumentException(L10n.t("Invalid JSON", "JSON không hợp lệ"));
        }
        Object entriesObj = map.get("entries");
        if (!(entriesObj instanceof List)) {
            throw new IllegalArgumentException(L10n.t("entries array missing or malformed", "Thiếu mảng entries hoặc sai định dạng"));
        }
        List<?> list = (List<?>) entriesObj;
        if (list.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException(L10n.t("More entries than the limit ", "Số lượng entries vượt quá giới hạn ") + MAX_ENTRIES);
        }

        List<LogEntry> result = new ArrayList<LogEntry>();
        for (Object item : list) {
            if (!(item instanceof Map)) {
                throw new IllegalArgumentException(L10n.t("An entry is not an object", "Phần tử entry không phải object"));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entryMap = (Map<String, Object>) item;

            Object tsObj = entryMap.get("ts");
            if (!(tsObj instanceof Number)) {
                throw new IllegalArgumentException(L10n.t("ts is not a number", "ts không phải số"));
            }
            long ts = ((Number) tsObj).longValue();
            if (ts <= 0) {
                throw new IllegalArgumentException(L10n.t("ts must be positive", "ts phải là số dương"));
            }

            Object deviceObj = entryMap.get("device");
            if (!(deviceObj instanceof String)) {
                throw new IllegalArgumentException(L10n.t("device is not a string", "device không phải chuỗi"));
            }
            String device = (String) deviceObj;
            if (device.length() > MAX_STRING_LEN) {
                throw new IllegalArgumentException(L10n.t("device is too long", "device vượt quá độ dài tối đa"));
            }

            Object sourceObj = entryMap.get("source");
            if (!(sourceObj instanceof String)) {
                throw new IllegalArgumentException(L10n.t("source is not a string", "source không phải chuỗi"));
            }
            String source = (String) sourceObj;
            if (source.length() > MAX_STRING_LEN) {
                throw new IllegalArgumentException(L10n.t("source is too long", "source vượt quá độ dài tối đa"));
            }

            Object actionObj = entryMap.get("action");
            if (!(actionObj instanceof String)) {
                throw new IllegalArgumentException(L10n.t("action is not a string", "action không phải chuỗi"));
            }
            String action = (String) actionObj;
            if (action.length() > MAX_STRING_LEN) {
                throw new IllegalArgumentException(L10n.t("action is too long", "action vượt quá độ dài tối đa"));
            }

            Object okObj = entryMap.get("ok");
            if (!(okObj instanceof Boolean)) {
                throw new IllegalArgumentException(L10n.t("ok is not a boolean", "ok không phải boolean"));
            }
            boolean ok = ((Boolean) okObj).booleanValue();

            result.add(new LogEntry(ts, device, source, action, ok));
        }

        return new LogResponse(result);
    }

    public static LogResponse optParse(String json) {
        try {
            return parse(json);
        } catch (Exception ignored) {
            return null;
        }
    }
}
