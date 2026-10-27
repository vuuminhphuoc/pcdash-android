package vn.vuumi.pcdash;

import java.io.BufferedReader;
import java.io.BufferedWriter;
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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MetricsHistory {
    public static final int MAX_SAMPLES = 720;
    public static final long MAX_AGE_MS = 6 * 3600 * 1000L; // 6 giờ
    public static final int INTERVAL_MS = 30000;

    public static class Sample {
        public final long ts;
        public final Double cpu;
        public final Double gpu;
        public final Double ram;
        public final Long down;
        public final Long up;
        public final Double cpuTemp;
        public final Double gpuTemp;
        public final boolean online;

        public Sample(long ts, Double cpu, Double gpu, Double ram, Long down, Long up, Double cpuTemp, Double gpuTemp, boolean online) {
            this.ts = ts;
            this.cpu = cpu;
            this.gpu = gpu;
            this.ram = ram;
            this.down = down;
            this.up = up;
            this.cpuTemp = cpuTemp;
            this.gpuTemp = gpuTemp;
            this.online = online;
        }

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"ts\":").append(ts);
            sb.append(",\"cpu\":").append(formatDouble(cpu));
            sb.append(",\"gpu\":").append(formatDouble(gpu));
            sb.append(",\"ram\":").append(formatDouble(ram));
            sb.append(",\"down\":").append(down != null ? down.toString() : "null");
            sb.append(",\"up\":").append(up != null ? up.toString() : "null");
            sb.append(",\"cpuTemp\":").append(formatDouble(cpuTemp));
            sb.append(",\"gpuTemp\":").append(formatDouble(gpuTemp));
            sb.append(",\"online\":").append(online ? "true" : "false");
            sb.append("}");
            return sb.toString();
        }
    }

    private static final Pattern P_AGENT_CPU_PERCENT = Pattern.compile("\"cpu\"\\s*:\\s*\\{[^}]*\"percent\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern P_AGENT_CPU_TEMP = Pattern.compile("\"cpu\"\\s*:\\s*\\{[^}]*\"tempC\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern P_AGENT_RAM_PERCENT = Pattern.compile("\"ram\"\\s*:\\s*\\{[^}]*\"percent\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern P_AGENT_GPU_PERCENT = Pattern.compile("\"gpu\"\\s*:\\s*\\{[^}]*\"percent\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern P_AGENT_GPU_TEMP = Pattern.compile("\"gpu\"\\s*:\\s*\\{[^}]*\"tempC\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern P_AGENT_NET_DOWN = Pattern.compile("\"net\"\\s*:\\s*\\{[^}]*\"downBps\"\\s*:\\s*([0-9]+)");
    private static final Pattern P_AGENT_NET_UP = Pattern.compile("\"net\"\\s*:\\s*\\{[^}]*\"upBps\"\\s*:\\s*([0-9]+)");

    private static final Pattern P_SAMPLE_TS = Pattern.compile("\"ts\"\\s*:\\s*([0-9]+)");
    private static final Pattern P_SAMPLE_CPU = Pattern.compile("\"cpu\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern P_SAMPLE_GPU = Pattern.compile("\"gpu\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern P_SAMPLE_RAM = Pattern.compile("\"ram\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern P_SAMPLE_DOWN = Pattern.compile("\"down\"\\s*:\\s*([0-9]+)");
    private static final Pattern P_SAMPLE_UP = Pattern.compile("\"up\"\\s*:\\s*([0-9]+)");
    private static final Pattern P_SAMPLE_CPU_TEMP = Pattern.compile("\"cpuTemp\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern P_SAMPLE_GPU_TEMP = Pattern.compile("\"gpuTemp\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern P_SAMPLE_ONLINE = Pattern.compile("\"online\"\\s*:\\s*(true|false)");

    private final File storageFile;
    private final LinkedList<Sample> samples = new LinkedList<Sample>();
    private boolean dirty = false;

    public MetricsHistory(File storageFile) {
        this.storageFile = storageFile;
        load();
    }

    public synchronized void add(Sample s) {
        if (s == null) return;
        samples.add(s);
        dirty = true;
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
    }

    public synchronized List<Sample> getSamples() {
        return Collections.unmodifiableList(new ArrayList<Sample>(samples));
    }

    public synchronized String toHistoryJson(long now, int minutes) {
        int m = minutes;
        if (m <= 0) {
            m = 60;
        } else if (m > 360) {
            m = 360;
        }

        long cutoff = now - (long) m * 60 * 1000L;
        List<Sample> filtered = new ArrayList<Sample>();
        for (Sample s : samples) {
            if (s.ts >= cutoff) {
                filtered.add(s);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"intervalMs\":").append(INTERVAL_MS);

        sb.append(",\"ts\":[");
        for (int i = 0; i < filtered.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(filtered.get(i).ts);
        }
        sb.append("]");

        sb.append(",\"cpu\":[");
        for (int i = 0; i < filtered.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(formatDouble(filtered.get(i).cpu));
        }
        sb.append("]");

        sb.append(",\"gpu\":[");
        for (int i = 0; i < filtered.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(formatDouble(filtered.get(i).gpu));
        }
        sb.append("]");

        sb.append(",\"ram\":[");
        for (int i = 0; i < filtered.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(formatDouble(filtered.get(i).ram));
        }
        sb.append("]");

        sb.append(",\"down\":[");
        for (int i = 0; i < filtered.size(); i++) {
            if (i > 0) sb.append(',');
            Long down = filtered.get(i).down;
            sb.append(down != null ? down.toString() : "null");
        }
        sb.append("]");

        sb.append(",\"up\":[");
        for (int i = 0; i < filtered.size(); i++) {
            if (i > 0) sb.append(',');
            Long up = filtered.get(i).up;
            sb.append(up != null ? up.toString() : "null");
        }
        sb.append("]");

        sb.append(",\"cpuTemp\":[");
        for (int i = 0; i < filtered.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(formatDouble(filtered.get(i).cpuTemp));
        }
        sb.append("]");

        sb.append(",\"gpuTemp\":[");
        for (int i = 0; i < filtered.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(formatDouble(filtered.get(i).gpuTemp));
        }
        sb.append("]");

        sb.append(",\"online\":[");
        for (int i = 0; i < filtered.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(filtered.get(i).online ? "1" : "0");
        }
        sb.append("]");

        sb.append("}");
        return sb.toString();
    }

    public synchronized void save() {
        if (storageFile == null || !dirty) return;
        try {
            File parent = storageFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            File temp = new File(parent, storageFile.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(temp);
                 BufferedWriter osw = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
                osw.write('[');
                for (int i = 0; i < samples.size(); i++) {
                    if (i > 0) osw.write(',');
                    osw.write(samples.get(i).toJson());
                }
                osw.write(']');
                osw.flush();
                fos.getFD().sync();
            }
            if (storageFile.exists()) {
                storageFile.delete();
            }
            if (!temp.renameTo(storageFile)) {
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
        } catch (IOException ignored) {}
        dirty = false;
    }

    public synchronized void load() {
        samples.clear();
        dirty = false;
        if (storageFile == null || !storageFile.exists() || storageFile.length() == 0) {
            return;
        }
        long minTs = System.currentTimeMillis() - MAX_AGE_MS;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(storageFile), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            String json = sb.toString().trim();
            if (json.startsWith("[") && json.endsWith("]")) {
                parseJsonSamples(json.substring(1, json.length() - 1), minTs, samples);
            }
        } catch (Exception ignored) {}
    }

    private static void parseJsonSamples(String content, long minTs, LinkedList<Sample> out) {
        int len = content.length();
        int braceDepth = 0;
        int start = -1;
        for (int i = 0; i < len; i++) {
            char c = content.charAt(i);
            if (c == '{') {
                if (braceDepth == 0) start = i;
                braceDepth++;
            } else if (c == '}') {
                braceDepth--;
                if (braceDepth == 0 && start >= 0) {
                    String obj = content.substring(start, i + 1);
                    Sample s = parseSampleJson(obj);
                    if (s != null && s.ts >= minTs) {
                        out.add(s);
                        if (out.size() > MAX_SAMPLES) {
                            out.removeFirst();
                        }
                    }
                    start = -1;
                }
            }
        }
    }

    public static Sample parseAgentMetrics(long ts, String agentPcJson) {
        if (agentPcJson == null || agentPcJson.trim().isEmpty()) {
            return new Sample(ts, null, null, null, null, null, null, null, false);
        }
        String json = agentPcJson.trim();
        if (json.contains("\"online\":false")) {
            return new Sample(ts, null, null, null, null, null, null, null, false);
        }

        Double cpu = extractMatchedDouble(json, P_AGENT_CPU_PERCENT);
        Double cpuTemp = extractMatchedDouble(json, P_AGENT_CPU_TEMP);
        Double ram = extractMatchedDouble(json, P_AGENT_RAM_PERCENT);
        Double gpu = extractMatchedDouble(json, P_AGENT_GPU_PERCENT);
        Double gpuTemp = extractMatchedDouble(json, P_AGENT_GPU_TEMP);
        Long down = extractMatchedLong(json, P_AGENT_NET_DOWN);
        Long up = extractMatchedLong(json, P_AGENT_NET_UP);

        return new Sample(ts, cpu, gpu, ram, down, up, cpuTemp, gpuTemp, true);
    }

    private static Double extractMatchedDouble(String json, Pattern p) {
        Matcher m = p.matcher(json);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static Long extractMatchedLong(String json, Pattern p) {
        Matcher m = p.matcher(json);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static Boolean extractMatchedBoolean(String json, Pattern p) {
        Matcher m = p.matcher(json);
        if (m.find()) {
            return Boolean.parseBoolean(m.group(1));
        }
        return null;
    }

    private static Sample parseSampleJson(String obj) {
        try {
            Long ts = extractMatchedLong(obj, P_SAMPLE_TS);
            if (ts == null) return null;
            Double cpu = extractMatchedDouble(obj, P_SAMPLE_CPU);
            Double gpu = extractMatchedDouble(obj, P_SAMPLE_GPU);
            Double ram = extractMatchedDouble(obj, P_SAMPLE_RAM);
            Long down = extractMatchedLong(obj, P_SAMPLE_DOWN);
            Long up = extractMatchedLong(obj, P_SAMPLE_UP);
            Double cpuTemp = extractMatchedDouble(obj, P_SAMPLE_CPU_TEMP);
            Double gpuTemp = extractMatchedDouble(obj, P_SAMPLE_GPU_TEMP);
            Boolean online = extractMatchedBoolean(obj, P_SAMPLE_ONLINE);
            return new Sample(ts, cpu, gpu, ram, down, up, cpuTemp, gpuTemp, online != null ? online : false);
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatDouble(Double val) {
        if (val == null || val.isNaN() || val.isInfinite()) {
            return "null";
        }
        if (val == Math.floor(val)) {
            return String.format(Locale.US, "%.1f", val);
        }
        return String.format(Locale.US, "%.1f", val);
    }
}
