package vn.vuumi.pcdash;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommandLog {
    public static final int MAX_ENTRIES = 500;
    private final File storageFile;
    private final LinkedList<LogEntry> entries = new LinkedList<LogEntry>();

    public static class LogEntry {
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

        public String toJson() {
            return "{\"ts\":" + ts
                    + ",\"device\":\"" + escapeJson(device) + "\""
                    + ",\"source\":\"" + escapeJson(source) + "\""
                    + ",\"action\":\"" + escapeJson(action) + "\""
                    + ",\"ok\":" + (ok ? "true" : "false")
                    + "}";
        }
    }

    public CommandLog(File file) {
        this.storageFile = file;
        load();
    }

    public synchronized void log(long ts, String device, String source, String action, boolean ok) {
        LogEntry entry = new LogEntry(ts, device, source, action, ok);
        entries.add(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
        try {
            save();
        } catch (IOException ignored) {}
    }

    public synchronized List<LogEntry> getEntries(int limit) {
        int count = limit > 0 ? Math.min(limit, entries.size()) : entries.size();
        List<LogEntry> result = new ArrayList<LogEntry>(count);
        // Newest first: iterate backward from entries
        int startIndex = entries.size() - 1;
        for (int i = 0; i < count; i++) {
            result.add(entries.get(startIndex - i));
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized String toJson(int limit) {
        List<LogEntry> list = getEntries(limit);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"entries\":[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(list.get(i).toJson());
        }
        sb.append("]}");
        return sb.toString();
    }

    public synchronized void clear() {
        entries.clear();
        if (storageFile != null && storageFile.exists()) {
            try {
                save();
            } catch (IOException ignored) {
                storageFile.delete();
            }
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    private synchronized void load() {
        entries.clear();
        if (storageFile == null || !storageFile.exists() || storageFile.length() == 0) {
            return;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(storageFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                LogEntry entry = parseLine(line);
                if (entry != null) {
                    entries.add(entry);
                    if (entries.size() > MAX_ENTRIES) {
                        entries.removeFirst();
                    }
                }
            }
        } catch (IOException ignored) {}
    }

    private synchronized void save() throws IOException {
        if (storageFile == null) return;
        File parent = storageFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File temp = new File(parent, storageFile.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(temp);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            for (LogEntry entry : entries) {
                osw.write(entry.toJson());
                osw.write('\n');
            }
            osw.flush();
            fos.getFD().sync();
        }

        if (storageFile.exists()) {
            storageFile.delete();
        }
        if (!temp.renameTo(storageFile)) {
            // Fallback copy if rename fails
            try (FileInputStream in = new FileInputStream(temp);
                 FileOutputStream out = new FileOutputStream(storageFile)) {
                byte[] buf = new byte[4096];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
                out.flush();
                out.getFD().sync();
            } finally {
                temp.delete();
            }
        }
    }

    private static final Pattern PATTERN_TS = Pattern.compile("\"ts\"\\s*:\\s*(\\d+)");
    private static final Pattern PATTERN_DEVICE = Pattern.compile("\"device\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern PATTERN_SOURCE = Pattern.compile("\"source\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern PATTERN_ACTION = Pattern.compile("\"action\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern PATTERN_OK = Pattern.compile("\"ok\"\\s*:\\s*(true|false)");

    private static LogEntry parseLine(String line) {
        if (!line.startsWith("{") || !line.endsWith("}")) {
            return null;
        }
        try {
            Matcher mTs = PATTERN_TS.matcher(line);
            if (!mTs.find()) return null;
            long ts = Long.parseLong(mTs.group(1));

            Matcher mDev = PATTERN_DEVICE.matcher(line);
            if (!mDev.find()) return null;
            String dev = unescapeJson(mDev.group(1));

            Matcher mSrc = PATTERN_SOURCE.matcher(line);
            if (!mSrc.find()) return null;
            String src = unescapeJson(mSrc.group(1));

            Matcher mAct = PATTERN_ACTION.matcher(line);
            if (!mAct.find()) return null;
            String act = unescapeJson(mAct.group(1));

            Matcher mOk = PATTERN_OK.matcher(line);
            if (!mOk.find()) return null;
            boolean ok = Boolean.parseBoolean(mOk.group(1));

            return new LogEntry(ts, dev, src, act, ok);
        } catch (Exception e) {
            return null;
        }
    }

    private static String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static String unescapeJson(String escaped) {
        if (escaped == null) return "";
        return escaped.replace("\\\"", "\"")
                .replace("\\r", "\r")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }
}
