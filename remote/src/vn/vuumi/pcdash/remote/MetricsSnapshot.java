package vn.vuumi.pcdash.remote;

import java.util.Collections;
import java.util.List;

public final class MetricsSnapshot {
    public static final MetricsSnapshot EMPTY = new MetricsSnapshot(null, null, null);

    public final Long ts;
    public final Pc pc;
    public final Phone phone;

    public MetricsSnapshot(Long ts, Pc pc, Phone phone) {
        this.ts = ts;
        this.pc = pc;
        this.phone = phone;
    }

    public static final class Pc {
        public final Boolean online;
        public final Processor cpu;
        public final Ram ram;
        public final Processor gpu;
        public final Net net;
        public final List<Storage> disks;
        public final Long uptimeSec;
        public final Sys sys;

        public Pc(Boolean online, Processor cpu, Ram ram, Processor gpu, Net net, List<Storage> disks, Long uptimeSec) {
            this(online, cpu, ram, gpu, net, disks, uptimeSec, null);
        }

        public Pc(Boolean online, Processor cpu, Ram ram, Processor gpu, Net net, List<Storage> disks, Long uptimeSec, Sys sys) {
            this.online = online;
            this.cpu = cpu;
            this.ram = ram;
            this.gpu = gpu;
            this.net = net;
            this.disks = disks != null ? Collections.unmodifiableList(disks) : null;
            this.uptimeSec = uptimeSec;
            this.sys = sys;
        }
    }

    public static final class Sys {
        public final Double tempC;

        public Sys(Double tempC) {
            this.tempC = tempC;
        }
    }

    public static final class Processor {
        public final Double percent;
        public final Double tempC;
        public final Long memUsedMB;
        public final Long memTotalMB;
        public final String name;

        public Processor(Double percent, Double tempC, String name) {
            this(percent, tempC, null, null, name);
        }

        public Processor(Double percent, Double tempC, Long memUsedMB, Long memTotalMB, String name) {
            this.percent = percent;
            this.tempC = tempC;
            this.memUsedMB = memUsedMB;
            this.memTotalMB = memTotalMB;
            this.name = name;
        }
    }

    public static final class Ram {
        public final Long usedMB;
        public final Long totalMB;
        public final Long availMB;
        public final Double percent;

        public Ram(Long usedMB, Long totalMB, Double percent) {
            this(usedMB, totalMB, null, percent);
        }

        public Ram(Long usedMB, Long totalMB, Long availMB, Double percent) {
            this.usedMB = usedMB;
            this.totalMB = totalMB;
            this.availMB = availMB;
            this.percent = percent;
        }
    }

    public static final class Storage {
        public final String name;
        public final Double usedGB;
        public final Double freeGB;
        public final Double totalGB;

        public Storage(String name, Double usedGB, Double totalGB) {
            this(name, usedGB, null, totalGB);
        }

        public Storage(Double freeGB, Double totalGB) {
            this(null, null, freeGB, totalGB);
        }

        public Storage(String name, Double usedGB, Double freeGB, Double totalGB) {
            this.name = name;
            this.usedGB = usedGB;
            this.freeGB = freeGB;
            this.totalGB = totalGB;
        }
    }

    public static final class Net {
        public final Long downBps;
        public final Long upBps;

        public Net(Long downBps, Long upBps) {
            this.downBps = downBps;
            this.upBps = upBps;
        }
    }

    public static final class Phone {
        public final Battery battery;
        public final Ram ram;
        public final Storage storage;
        public final Wifi wifi;
        public final Net net;
        public final Boolean tailscale;
        public final String thermal;
        public final Long uptimeSec;

        public Phone(Battery battery, Ram ram, Storage storage, Wifi wifi, Net net,
                     Boolean tailscale, String thermal, Long uptimeSec) {
            this.battery = battery;
            this.ram = ram;
            this.storage = storage;
            this.wifi = wifi;
            this.net = net;
            this.tailscale = tailscale;
            this.thermal = thermal;
            this.uptimeSec = uptimeSec;
        }
    }

    public static final class Battery {
        public final Double percent;
        public final Boolean charging;
        public final Double tempC;

        public Battery(Double percent, Boolean charging, Double tempC) {
            this.percent = percent;
            this.charging = charging;
            this.tempC = tempC;
        }
    }

    public static final class Wifi {
        public final Integer rssiDbm;
        public final Integer linkMbps;

        public Wifi(Integer rssiDbm, Integer linkMbps) {
            this.rssiDbm = rssiDbm;
            this.linkMbps = linkMbps;
        }
    }
}
