package vn.vuumi.pcdash.remote;

import java.util.List;
import java.util.Map;

public final class HistoryParser {

    private static final int MAX_JSON_LEN = 512 * 1024; // 512 KB
    private static final int MAX_SAMPLES = 1000;

    private HistoryParser() {}

    public static final class HistoryData {
        public final long intervalMs;
        public final int sampleCount;
        public final long[] ts;
        public final Double[] cpu;
        public final Double[] gpu;
        public final Double[] ram;
        public final Long[] down;
        public final Long[] up;
        public final Double[] cpuTemp;
        public final Double[] gpuTemp;
        public final int[] online;

        public HistoryData(long intervalMs, long[] ts, Double[] cpu, Double[] gpu, Double[] ram,
                           Long[] down, Long[] up, Double[] cpuTemp, Double[] gpuTemp, int[] online) {
            this.intervalMs = intervalMs;
            this.sampleCount = ts != null ? ts.length : 0;
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

        public boolean isEmpty() {
            return sampleCount == 0;
        }
    }

    public static HistoryData parse(String json) throws IllegalArgumentException {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("JSON rỗng hoặc null");
        }
        if (json.length() > MAX_JSON_LEN) {
            throw new IllegalArgumentException("Kích thước JSON vượt quá giới hạn " + MAX_JSON_LEN);
        }

        Map<String, Object> map = MetricsParser.parseMap(json);
        if (map == null) {
            throw new IllegalArgumentException("JSON không hợp lệ");
        }

        Object intervalObj = map.get("intervalMs");
        if (!(intervalObj instanceof Number)) {
            throw new IllegalArgumentException("intervalMs thiếu hoặc không phải số");
        }
        long intervalMs = ((Number) intervalObj).longValue();
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("intervalMs phải là số dương");
        }

        List<?> listTs = getList(map, "ts");
        List<?> listCpu = getList(map, "cpu");
        List<?> listGpu = getList(map, "gpu");
        List<?> listRam = getList(map, "ram");
        List<?> listDown = getList(map, "down");
        List<?> listUp = getList(map, "up");
        List<?> listCpuTemp = getList(map, "cpuTemp");
        List<?> listGpuTemp = getList(map, "gpuTemp");
        List<?> listOnline = getList(map, "online");

        int len = listTs.size();
        if (len > MAX_SAMPLES) {
            throw new IllegalArgumentException("Số lượng mẫu vượt quá giới hạn " + MAX_SAMPLES);
        }

        // Kiểm tra độ dài các mảng: bắt buộc phải bằng nhau
        if (listCpu.size() != len || listGpu.size() != len || listRam.size() != len
                || listDown.size() != len || listUp.size() != len
                || listCpuTemp.size() != len || listGpuTemp.size() != len
                || listOnline.size() != len) {
            throw new IllegalArgumentException("Mảng lệch độ dài: ts=" + len
                    + ", cpu=" + listCpu.size() + ", gpu=" + listGpu.size()
                    + ", ram=" + listRam.size() + ", down=" + listDown.size()
                    + ", up=" + listUp.size() + ", cpuTemp=" + listCpuTemp.size()
                    + ", gpuTemp=" + listGpuTemp.size() + ", online=" + listOnline.size());
        }

        long[] ts = new long[len];
        Double[] cpu = new Double[len];
        Double[] gpu = new Double[len];
        Double[] ram = new Double[len];
        Long[] down = new Long[len];
        Long[] up = new Long[len];
        Double[] cpuTemp = new Double[len];
        Double[] gpuTemp = new Double[len];
        int[] online = new int[len];

        long prevTs = -1;
        for (int i = 0; i < len; i++) {
            // ts: bắt buộc số dương, tăng dần theo thời gian
            Object tObj = listTs.get(i);
            if (!(tObj instanceof Number)) {
                throw new IllegalArgumentException("ts[" + i + "] không phải số");
            }
            long curTs = ((Number) tObj).longValue();
            if (curTs <= 0) {
                throw new IllegalArgumentException("ts[" + i + "] phải dương");
            }
            if (i > 0 && curTs < prevTs) {
                throw new IllegalArgumentException("ts không tăng dần theo thời gian tại chỉ mục " + i);
            }
            prevTs = curTs;
            ts[i] = curTs;

            cpu[i] = parseDouble(listCpu.get(i), "cpu", i);
            gpu[i] = parseDouble(listGpu.get(i), "gpu", i);
            ram[i] = parseDouble(listRam.get(i), "ram", i);

            down[i] = parseLong(listDown.get(i), "down", i);
            up[i] = parseLong(listUp.get(i), "up", i);

            cpuTemp[i] = parseDouble(listCpuTemp.get(i), "cpuTemp", i);
            gpuTemp[i] = parseDouble(listGpuTemp.get(i), "gpuTemp", i);

            online[i] = parseOnline(listOnline.get(i), i);
        }

        return new HistoryData(intervalMs, ts, cpu, gpu, ram, down, up, cpuTemp, gpuTemp, online);
    }

    public static HistoryData optParse(String json) {
        try {
            return parse(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<?> getList(Map<String, Object> map, String key) {
        Object obj = map.get(key);
        if (!(obj instanceof List)) {
            throw new IllegalArgumentException("Thiếu hoặc sai kiểu mảng: " + key);
        }
        return (List<?>) obj;
    }

    private static Double parseDouble(Object obj, String key, int idx) {
        if (obj == null) return null;
        if (obj instanceof Number) {
            return ((Number) obj).doubleValue();
        }
        throw new IllegalArgumentException(key + "[" + idx + "] không phải số hoặc null");
    }

    private static Long parseLong(Object obj, String key, int idx) {
        if (obj == null) return null;
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        }
        throw new IllegalArgumentException(key + "[" + idx + "] không phải số hoặc null");
    }

    private static int parseOnline(Object obj, int idx) {
        if (obj == null) return 0;
        if (obj instanceof Number) {
            return ((Number) obj).intValue() != 0 ? 1 : 0;
        }
        if (obj instanceof Boolean) {
            return ((Boolean) obj).booleanValue() ? 1 : 0;
        }
        throw new IllegalArgumentException("online[" + idx + "] không phải số/boolean");
    }
}
