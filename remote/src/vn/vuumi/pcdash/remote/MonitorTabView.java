package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.ui.Tap;
import vn.vuumi.pcdash.i18n.L10n;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MonitorTabView implements TabContent {

    public interface Callback {
        void onChartIntervalChanged(int minutes);
    }

    private final ScrollView scrollView;
    private final TextView pcStaleBadge;
    private final TextView pcOfflineView;
    private final LinearLayout pcMetricsRows;

    private final TextView phoneStaleBadge;
    private final LinearLayout phoneMetricsRows;

    private final Button btnChart1h;
    private final Button btnChart6h;
    private final TextView chartStatusView;
    private final LinearLayout chartsContainer;
    private final HistoryChartView cpuGpuChartView;
    private final HistoryChartView ramChartView;
    private final HistoryChartView netChartView;
    private final HistoryChartView tempChartView;

    private int chartMinutes = 60;

    public MonitorTabView(final Context context, final Callback callback) {
        scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(Color.rgb(0, 0, 0));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dpToPx(context, 24), dpToPx(context, 24), dpToPx(context, 24), dpToPx(context, 32));
        scrollView.addView(container);

        TextView title = new TextView(context);
        title.setText(L10n.t("Monitor", "Giám sát"));
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(230, 237, 243));
        container.addView(title);

        TextView sub = new TextView(context);
        sub.setText(L10n.t("PC and home phone performance", "Số liệu hiệu năng PC & Thiết bị"));
        sub.setTextSize(13);
        sub.setTextColor(Color.rgb(139, 148, 158));
        sub.setPadding(0, dpToPx(context, 4), 0, dpToPx(context, 20));
        container.addView(sub);

        // --- Khối PC ---
        LinearLayout pcBlock = new LinearLayout(context);
        pcBlock.setOrientation(LinearLayout.VERTICAL);
        pcBlock.setPadding(dpToPx(context, 18), dpToPx(context, 16), dpToPx(context, 18), dpToPx(context, 16));
        UiKit.styleCard(pcBlock);

        LinearLayout pcHeader = new LinearLayout(context);
        pcHeader.setOrientation(LinearLayout.HORIZONTAL);
        TextView pcCardTitle = new TextView(context);
        pcCardTitle.setText("PC");
        pcCardTitle.setTextSize(18);
        pcCardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        pcCardTitle.setTextColor(Color.rgb(230, 237, 243));
        LinearLayout.LayoutParams pcTitleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        pcCardTitle.setLayoutParams(pcTitleLp);
        pcHeader.addView(pcCardTitle);

        pcStaleBadge = new TextView(context);
        pcStaleBadge.setText(L10n.t("stale", "dữ liệu cũ"));
        pcStaleBadge.setTextSize(12);
        pcStaleBadge.setTextColor(Color.rgb(240, 136, 62));
        pcStaleBadge.setVisibility(View.GONE);
        pcHeader.addView(pcStaleBadge);
        pcBlock.addView(pcHeader);

        pcOfflineView = new TextView(context);
        pcOfflineView.setText(L10n.t("PC not responding", "PC không phản hồi"));
        pcOfflineView.setTextSize(13);
        pcOfflineView.setTextColor(Color.rgb(248, 81, 73));
        pcOfflineView.setPadding(0, dpToPx(context, 6), 0, 0);
        pcOfflineView.setVisibility(View.GONE);
        pcBlock.addView(pcOfflineView);

        pcMetricsRows = new LinearLayout(context);
        pcMetricsRows.setOrientation(LinearLayout.VERTICAL);
        pcMetricsRows.setPadding(0, dpToPx(context, 6), 0, 0);

        TextView pcLoading = new TextView(context);
        pcLoading.setText(L10n.t("Loading PC metrics...", "Đang tải số liệu PC..."));
        pcLoading.setTextSize(13);
        pcLoading.setTextColor(Color.rgb(139, 148, 158));
        pcMetricsRows.addView(pcLoading);

        pcBlock.addView(pcMetricsRows);
        container.addView(pcBlock);
        addSpacing(container, dpToPx(context, 16));

        // --- Khối Điện thoại nhà ---
        LinearLayout phoneBlock = new LinearLayout(context);
        phoneBlock.setOrientation(LinearLayout.VERTICAL);
        phoneBlock.setPadding(dpToPx(context, 18), dpToPx(context, 16), dpToPx(context, 18), dpToPx(context, 16));
        UiKit.styleCard(phoneBlock);

        LinearLayout phoneHeader = new LinearLayout(context);
        phoneHeader.setOrientation(LinearLayout.HORIZONTAL);
        TextView phoneCardTitle = new TextView(context);
        phoneCardTitle.setText(L10n.t("Home phone", "Điện thoại nhà"));
        phoneCardTitle.setTextSize(18);
        phoneCardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        phoneCardTitle.setTextColor(Color.rgb(230, 237, 243));
        LinearLayout.LayoutParams phoneTitleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        phoneCardTitle.setLayoutParams(phoneTitleLp);
        phoneHeader.addView(phoneCardTitle);

        phoneStaleBadge = new TextView(context);
        phoneStaleBadge.setText(L10n.t("stale", "dữ liệu cũ"));
        phoneStaleBadge.setTextSize(12);
        phoneStaleBadge.setTextColor(Color.rgb(240, 136, 62));
        phoneStaleBadge.setVisibility(View.GONE);
        phoneHeader.addView(phoneStaleBadge);
        phoneBlock.addView(phoneHeader);

        phoneMetricsRows = new LinearLayout(context);
        phoneMetricsRows.setOrientation(LinearLayout.VERTICAL);
        phoneMetricsRows.setPadding(0, dpToPx(context, 6), 0, 0);

        TextView phoneLoading = new TextView(context);
        phoneLoading.setText(L10n.t("Loading phone metrics...", "Đang tải số liệu điện thoại..."));
        phoneLoading.setTextSize(13);
        phoneLoading.setTextColor(Color.rgb(139, 148, 158));
        phoneMetricsRows.addView(phoneLoading);

        phoneBlock.addView(phoneMetricsRows);
        container.addView(phoneBlock);
        addSpacing(container, dpToPx(context, 16));

        // --- Khối Biểu đồ ---
        LinearLayout chartBlock = new LinearLayout(context);
        chartBlock.setOrientation(LinearLayout.VERTICAL);
        chartBlock.setPadding(dpToPx(context, 18), dpToPx(context, 16), dpToPx(context, 18), dpToPx(context, 16));
        UiKit.styleCard(chartBlock);

        LinearLayout chartHeader = new LinearLayout(context);
        chartHeader.setOrientation(LinearLayout.HORIZONTAL);
        chartHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView chartTitle = new TextView(context);
        chartTitle.setText(L10n.t("Charts", "Biểu đồ"));
        chartTitle.setTextSize(18);
        chartTitle.setTypeface(Typeface.DEFAULT_BOLD);
        chartTitle.setTextColor(Color.rgb(230, 237, 243));
        LinearLayout.LayoutParams chartTitleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        chartTitle.setLayoutParams(chartTitleLp);
        chartHeader.addView(chartTitle);

        btnChart1h = new Button(context);
        btnChart1h.setText("1h");
        btnChart1h.setTextSize(12);
        btnChart1h.setPadding(dpToPx(context, 12), 0, dpToPx(context, 12), 0);

        btnChart6h = new Button(context);
        btnChart6h.setText("6h");
        btnChart6h.setTextSize(12);
        btnChart6h.setPadding(dpToPx(context, 12), 0, dpToPx(context, 12), 0);

        updateChartIntervalButtons();

        Tap.wire(btnChart1h, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (chartMinutes != 60) {
                    chartMinutes = 60;
                    updateChartIntervalButtons();
                    if (callback != null) callback.onChartIntervalChanged(chartMinutes);
                }
            }
        });

        Tap.wire(btnChart6h, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (chartMinutes != 360) {
                    chartMinutes = 360;
                    updateChartIntervalButtons();
                    if (callback != null) callback.onChartIntervalChanged(chartMinutes);
                }
            }
        });

        chartHeader.addView(btnChart1h);
        chartHeader.addView(btnChart6h);
        chartBlock.addView(chartHeader);

        chartStatusView = new TextView(context);
        chartStatusView.setTextSize(13);
        chartStatusView.setTextColor(Color.rgb(139, 148, 158));
        chartStatusView.setPadding(0, dpToPx(context, 8), 0, dpToPx(context, 4));
        chartStatusView.setText(L10n.t("Loading chart data...", "Đang tải dữ liệu biểu đồ..."));
        chartBlock.addView(chartStatusView);

        chartsContainer = new LinearLayout(context);
        chartsContainer.setOrientation(LinearLayout.VERTICAL);

        int chartHeight = (int) (150 * context.getResources().getDisplayMetrics().density + 0.5f);
        LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, chartHeight);
        chartLp.topMargin = dpToPx(context, 6);

        cpuGpuChartView = new HistoryChartView(context);
        cpuGpuChartView.setChartConfig("CPU & GPU (%)", HistoryChartView.UnitType.PERCENT,
                "CPU", Color.rgb(63, 185, 80),
                "GPU", Color.rgb(163, 113, 247));
        cpuGpuChartView.setLayoutParams(chartLp);
        chartsContainer.addView(cpuGpuChartView);

        ramChartView = new HistoryChartView(context);
        ramChartView.setChartConfig("RAM (%)", HistoryChartView.UnitType.PERCENT,
                "RAM", Color.rgb(88, 166, 255),
                null, 0);
        ramChartView.setLayoutParams(chartLp);
        chartsContainer.addView(ramChartView);

        netChartView = new HistoryChartView(context);
        netChartView.setChartConfig(L10n.t("Network (download / upload)", "Mạng (Tải xuống / Tải lên)"), HistoryChartView.UnitType.SPEED,
                L10n.t("Download", "Tải xuống"), Color.rgb(63, 185, 80),
                L10n.t("Upload", "Tải lên"), Color.rgb(88, 166, 255));
        netChartView.setLayoutParams(chartLp);
        chartsContainer.addView(netChartView);

        tempChartView = new HistoryChartView(context);
        tempChartView.setChartConfig(L10n.t("Temperature (°C)", "Nhiệt độ (°C)"), HistoryChartView.UnitType.TEMP,
                "CPU", Color.rgb(240, 136, 62),
                "GPU", Color.rgb(248, 81, 73));
        tempChartView.setLayoutParams(chartLp);
        chartsContainer.addView(tempChartView);

        chartBlock.addView(chartsContainer);
        container.addView(chartBlock);
    }

    @Override
    public View view() {
        return scrollView;
    }

    @Override
    public void onShow() {}

    @Override
    public void onHide() {}

    public int getChartMinutes() {
        return chartMinutes;
    }

    public void setChartInterval(int minutes) {
        this.chartMinutes = minutes;
        updateChartIntervalButtons();
    }

    private void updateChartIntervalButtons() {
        styleIntervalButton(btnChart1h, chartMinutes == 60);
        styleIntervalButton(btnChart6h, chartMinutes == 360);
    }

    // A plain color background would drop the press ripple each time the selection changes.
    private static void styleIntervalButton(Button b, boolean selected) {
        if (b == null) return;
        int fill = selected ? Color.rgb(26, 84, 176) : Color.rgb(33, 38, 45);
        b.setBackground(UiKit.withRipple(UiKit.roundedFill(b.getContext(), fill, 8)));
        b.setTextColor(selected ? Color.WHITE : Color.rgb(139, 148, 158));
    }

    public void updateMetrics(MetricsSnapshot snapshot, boolean isOld) {
        if (pcStaleBadge != null) {
            pcStaleBadge.setVisibility(isOld ? View.VISIBLE : View.GONE);
        }
        if (phoneStaleBadge != null) {
            phoneStaleBadge.setVisibility(isOld ? View.VISIBLE : View.GONE);
        }

        // --- Khối PC ---
        if (pcMetricsRows != null && pcOfflineView != null) {
            boolean pcOnline = snapshot != null && snapshot.pc != null
                    && Boolean.TRUE.equals(snapshot.pc.online);

            if (!pcOnline) {
                pcOfflineView.setText(L10n.t("PC not responding", "PC không phản hồi"));
                pcOfflineView.setVisibility(View.VISIBLE);
                pcMetricsRows.setVisibility(View.GONE);
            } else {
                pcOfflineView.setVisibility(View.GONE);
                pcMetricsRows.setVisibility(View.VISIBLE);
                MetricsSnapshot.Pc pc = snapshot.pc;
                int pcRowIndex = 0;

                if (pc.cpu != null && pc.cpu.percent != null) {
                    String label = "CPU";
                    String val = MetricsFormatter.formatPercent(pc.cpu.percent);
                    if (pc.cpu.name != null && !pc.cpu.name.trim().isEmpty()) {
                        val += " · " + pc.cpu.name.trim();
                    }
                    setOrUpdateMetricRow(pcMetricsRows, pcRowIndex++, label, val, pc.cpu.percent);
                }

                if (pc.gpu != null && pc.gpu.percent != null) {
                    String label = "GPU";
                    String val = MetricsFormatter.formatPercent(pc.gpu.percent);
                    if (pc.gpu.name != null && !pc.gpu.name.trim().isEmpty()) {
                        val += " · " + pc.gpu.name.trim();
                    }
                    setOrUpdateMetricRow(pcMetricsRows, pcRowIndex++, label, val, pc.gpu.percent);
                }

                if (pc.ram != null && (pc.ram.usedMB != null || pc.ram.totalMB != null)) {
                    Double ramPct = pc.ram.percent;
                    if (ramPct == null && pc.ram.usedMB != null && pc.ram.totalMB != null && pc.ram.totalMB > 0) {
                        ramPct = (pc.ram.usedMB * 100.0) / pc.ram.totalMB;
                    }
                    String val = MetricsFormatter.formatRam(pc.ram.usedMB, pc.ram.totalMB);
                    if (ramPct != null) {
                        val += " (" + MetricsFormatter.formatPercent(ramPct) + ")";
                    }
                    setOrUpdateMetricRow(pcMetricsRows, pcRowIndex++, "RAM", val, ramPct);
                }

                if (pc.net != null && (pc.net.downBps != null || pc.net.upBps != null)) {
                    String val = MetricsFormatter.formatNetBoth(pc.net.downBps, pc.net.upBps);
                    setOrUpdateMetricRow(pcMetricsRows, pcRowIndex++, L10n.t("Network", "Mạng"), val, null);
                }

                String cpuOrSys = MetricsFormatter.formatCpuOrSysTemp(pc);
                boolean hasGpuTemp = pc.gpu != null && pc.gpu.tempC != null;
                if (cpuOrSys != null || hasGpuTemp) {
                    StringBuilder sb = new StringBuilder();
                    if (cpuOrSys != null) {
                        sb.append(cpuOrSys);
                    }
                    if (hasGpuTemp) {
                        if (sb.length() > 0) sb.append("  ·  ");
                        sb.append("GPU: ").append(MetricsFormatter.formatTemp(pc.gpu.tempC));
                    }
                    setOrUpdateMetricRow(pcMetricsRows, pcRowIndex++, L10n.t("Temperature", "Nhiệt độ"), sb.toString(), null);
                }

                if (pc.disks != null && !pc.disks.isEmpty()) {
                    for (int i = 0; i < pc.disks.size(); i++) {
                        MetricsSnapshot.Storage d = pc.disks.get(i);
                        if (d != null && (d.usedGB != null || d.totalGB != null)) {
                            Double diskPct = (d.usedGB != null && d.totalGB != null && d.totalGB > 0)
                                    ? (d.usedGB * 100.0 / d.totalGB) : null;
                            String dLabel = (d.name != null && !d.name.trim().isEmpty())
                                    ? L10n.t("Disk (", "Đĩa (") + d.name.trim() + ")" : L10n.t("Disk", "Đĩa");
                            String dVal = MetricsFormatter.formatDisk(d.usedGB, d.totalGB);
                            if (diskPct != null) {
                                dVal += " (" + MetricsFormatter.formatPercent(diskPct) + ")";
                            }
                            setOrUpdateMetricRow(pcMetricsRows, pcRowIndex++, dLabel, dVal, diskPct);
                        }
                    }
                }

                if (pc.uptimeSec != null) {
                    setOrUpdateMetricRow(pcMetricsRows, pcRowIndex++, L10n.t("Uptime", "Thời gian hoạt động"), MetricsFormatter.formatUptime(pc.uptimeSec), null);
                }

                trimTrailingRows(pcMetricsRows, pcRowIndex);
            }
        }

        // --- Khối Điện thoại nhà ---
        if (phoneMetricsRows != null) {
            MetricsSnapshot.Phone phone = (snapshot != null) ? snapshot.phone : null;
            if (phone == null) {
                if (phoneMetricsRows.getChildCount() != 1 || !(phoneMetricsRows.getChildAt(0) instanceof TextView) || phoneMetricsRows.getChildAt(0).getTag() != null) {
                    phoneMetricsRows.removeAllViews();
                    TextView emptyTv = new TextView(scrollView.getContext());
                    emptyTv.setText(L10n.t("No phone data", "Không có dữ liệu điện thoại"));
                    emptyTv.setTextSize(13);
                    emptyTv.setTextColor(Color.rgb(139, 148, 158));
                    phoneMetricsRows.addView(emptyTv);
                }
            } else {
                int phoneRowIndex = 0;
                if (phone.battery != null && phone.battery.percent != null) {
                    setOrUpdateMetricRow(phoneMetricsRows, phoneRowIndex++, L10n.t("Battery", "Pin"),
                            MetricsFormatter.formatBattery(phone.battery.percent, phone.battery.charging),
                            phone.battery.percent,
                            Double.valueOf(100.0 - phone.battery.percent.doubleValue()));
                }

                if (phone.battery != null && phone.battery.tempC != null) {
                    setOrUpdateMetricRow(phoneMetricsRows, phoneRowIndex++, L10n.t("Battery temp", "Nhiệt pin"),
                            MetricsFormatter.formatTemp(phone.battery.tempC), null);
                }

                if (phone.ram != null && (phone.ram.availMB != null || phone.ram.totalMB != null)) {
                    Double phoneRamPct = null;
                    if (phone.ram.availMB != null && phone.ram.totalMB != null && phone.ram.totalMB > 0) {
                        long usedMB = phone.ram.totalMB - phone.ram.availMB;
                        phoneRamPct = (usedMB * 100.0) / phone.ram.totalMB;
                    }
                    String val = MetricsFormatter.formatPhoneRam(phone.ram.availMB, phone.ram.totalMB);
                    if (phoneRamPct != null) {
                        val += " (" + MetricsFormatter.formatPercent(phoneRamPct) + ")";
                    }
                    setOrUpdateMetricRow(phoneMetricsRows, phoneRowIndex++, "RAM", val, phoneRamPct);
                }

                if (phone.storage != null && (phone.storage.freeGB != null || phone.storage.totalGB != null)) {
                    Double storagePct = null;
                    if (phone.storage.freeGB != null && phone.storage.totalGB != null && phone.storage.totalGB > 0) {
                        double usedGB = phone.storage.totalGB - phone.storage.freeGB;
                        storagePct = (usedGB * 100.0) / phone.storage.totalGB;
                    }
                    String val = MetricsFormatter.formatStorage(phone.storage.freeGB, phone.storage.totalGB);
                    if (storagePct != null) {
                        val += " (" + MetricsFormatter.formatPercent(storagePct) + ")";
                    }
                    setOrUpdateMetricRow(phoneMetricsRows, phoneRowIndex++, L10n.t("Storage", "Bộ nhớ"), val, storagePct);
                }

                if (phone.wifi != null && (phone.wifi.rssiDbm != null || phone.wifi.linkMbps != null)) {
                    setOrUpdateMetricRow(phoneMetricsRows, phoneRowIndex++, "Wi-Fi",
                            MetricsFormatter.formatWifi(phone.wifi.rssiDbm, phone.wifi.linkMbps), null);
                }

                if (phone.net != null && (phone.net.downBps != null || phone.net.upBps != null)) {
                    setOrUpdateMetricRow(phoneMetricsRows, phoneRowIndex++, L10n.t("Network", "Mạng"),
                            MetricsFormatter.formatNetBoth(phone.net.downBps, phone.net.upBps), null);
                }

                if (phone.tailscale != null) {
                    setOrUpdateMetricRow(phoneMetricsRows, phoneRowIndex++, "Tailscale (host)",
                            phone.tailscale ? L10n.t("Connected", "Đã kết nối") : L10n.t("Not connected", "Chưa kết nối"), null);
                }

                if (phone.uptimeSec != null) {
                    setOrUpdateMetricRow(phoneMetricsRows, phoneRowIndex++, L10n.t("Uptime", "Thời gian hoạt động"),
                            MetricsFormatter.formatUptime(phone.uptimeSec), null);
                }

                trimTrailingRows(phoneMetricsRows, phoneRowIndex);
            }
        }
    }

    public void updateMetricsError(String errorMsg) {
        if (pcOfflineView != null) {
            pcOfflineView.setText(errorMsg);
            pcOfflineView.setVisibility(View.VISIBLE);
        }
        if (pcMetricsRows != null) {
            pcMetricsRows.setVisibility(View.GONE);
        }
    }

    public void updateHistory(HistoryParser.HistoryData data, Exception error) {
        if (error != null) {
            if (chartStatusView != null) {
                chartStatusView.setVisibility(View.VISIBLE);
                chartStatusView.setTextColor(Color.rgb(248, 81, 73));
                if (error instanceof RemoteApiClient.UnsupportedRouteException
                        || (error.getMessage() != null && error.getMessage().contains("404"))) {
                    chartStatusView.setText(RemoteApiClient.UnsupportedRouteException.hostNeedsUpdateMessage());
                } else {
                    chartStatusView.setText(L10n.t("Could not load history: ", "Lỗi tải lịch sử: ") + (error.getMessage() != null ? error.getMessage() : "Timeout"));
                }
            }
            if (chartsContainer != null) {
                chartsContainer.setVisibility(View.GONE);
            }
            return;
        }

        if (chartStatusView != null) {
            chartStatusView.setVisibility(View.GONE);
        }
        if (chartsContainer != null) {
            chartsContainer.setVisibility(View.VISIBLE);
        }

        if (data != null) {
            if (cpuGpuChartView != null) {
                cpuGpuChartView.setData(data.ts, data.cpu, data.gpu, data.online);
            }
            if (ramChartView != null) {
                ramChartView.setData(data.ts, data.ram, null, data.online);
            }
            if (netChartView != null) {
                netChartView.setDataLong(data.ts, data.down, data.up, data.online);
            }
            if (tempChartView != null) {
                tempChartView.setData(data.ts, data.cpuTemp, data.gpuTemp, data.online);
            }
        }
    }

    private static class MetricRowHolder {
        final LinearLayout row;
        final TextView lbl;
        final TextView val;
        final LinearLayout track;
        final View fill;
        final View empty;
        String label;
        String valText;
        MetricsFormatter.AlertLevel alertLevel;
        double percent = -1.0;

        MetricRowHolder(LinearLayout row, TextView lbl, TextView val, LinearLayout track, View fill, View empty) {
            this.row = row;
            this.lbl = lbl;
            this.val = val;
            this.track = track;
            this.fill = fill;
            this.empty = empty;
        }
    }

    private static void setOrUpdateMetricRow(LinearLayout container, int index, String label, String valueText, Double percent) {
        setOrUpdateMetricRow(container, index, label, valueText, percent, percent);
    }

    private static void setOrUpdateMetricRow(LinearLayout container, int index, String label, String valueText, Double percent, Double alertPercent) {
        Context ctx = container.getContext();
        MetricsFormatter.AlertLevel level = percent != null
                ? MetricsFormatter.getAlertLevel(alertPercent != null ? alertPercent : percent)
                : null;
        int targetColor = level != null ? getColorForAlert(level) : Color.rgb(201, 209, 217);
        boolean needsBar = (percent != null);

        MetricRowHolder holder = null;
        if (index < container.getChildCount()) {
            View child = container.getChildAt(index);
            Object tag = child.getTag();
            if (tag instanceof MetricRowHolder) {
                MetricRowHolder existing = (MetricRowHolder) tag;
                if ((existing.track != null) == needsBar) {
                    holder = existing;
                }
            }
        }

        if (holder == null) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dpToPx(ctx, 4), 0, dpToPx(ctx, 4));

            LinearLayout textRow = new LinearLayout(ctx);
            textRow.setOrientation(LinearLayout.HORIZONTAL);

            TextView lbl = new TextView(ctx);
            lbl.setText(label);
            lbl.setTextSize(13);
            lbl.setTextColor(Color.rgb(139, 148, 158));
            LinearLayout.LayoutParams lpLbl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            lbl.setLayoutParams(lpLbl);
            textRow.addView(lbl);

            TextView val = new TextView(ctx);
            val.setText(valueText);
            val.setTextSize(13);
            val.setTypeface(Typeface.DEFAULT_BOLD);
            val.setTextColor(targetColor);
            textRow.addView(val);
            row.addView(textRow);

            LinearLayout track = null;
            View fill = null;
            View empty = null;

            if (needsBar) {
                double clamped = Math.max(0.0, Math.min(100.0, percent));
                track = new LinearLayout(ctx);
                track.setOrientation(LinearLayout.HORIZONTAL);
                track.setBackgroundColor(Color.rgb(33, 38, 45));
                track.setWeightSum(100f);

                fill = new View(ctx);
                fill.setBackgroundColor(targetColor);
                fill.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (float) clamped));
                track.addView(fill);

                empty = new View(ctx);
                empty.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (float) (100.0 - clamped)));
                track.addView(empty);

                LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(ctx, 6));
                barLp.topMargin = dpToPx(ctx, 4);
                track.setLayoutParams(barLp);
                row.addView(track);
            }

            holder = new MetricRowHolder(row, lbl, val, track, fill, empty);
            holder.label = label;
            holder.valText = valueText;
            holder.alertLevel = level;
            holder.percent = percent != null ? percent : -1.0;
            row.setTag(holder);

            if (index < container.getChildCount()) {
                container.removeViewAt(index);
                container.addView(row, index);
            } else {
                container.addView(row);
            }
            return;
        }

        if (!label.equals(holder.label)) {
            holder.label = label;
            holder.lbl.setText(label);
        }
        if (!valueText.equals(holder.valText)) {
            holder.valText = valueText;
            holder.val.setText(valueText);
        }
        if (level != holder.alertLevel) {
            holder.alertLevel = level;
            holder.val.setTextColor(targetColor);
            if (holder.fill != null) {
                holder.fill.setBackgroundColor(targetColor);
            }
        }
        if (needsBar && holder.fill != null && holder.empty != null) {
            double currentPct = percent != null ? percent : 0.0;
            if (Double.compare(currentPct, holder.percent) != 0) {
                holder.percent = currentPct;
                double clamped = Math.max(0.0, Math.min(100.0, currentPct));
                LinearLayout.LayoutParams fillLp = (LinearLayout.LayoutParams) holder.fill.getLayoutParams();
                LinearLayout.LayoutParams emptyLp = (LinearLayout.LayoutParams) holder.empty.getLayoutParams();
                fillLp.weight = (float) clamped;
                emptyLp.weight = (float) (100.0 - clamped);
                holder.track.requestLayout();
            }
        }
    }

    private static void trimTrailingRows(LinearLayout container, int targetCount) {
        int current = container.getChildCount();
        if (current > targetCount) {
            container.removeViews(targetCount, current - targetCount);
        }
    }

    private static int getColorForAlert(MetricsFormatter.AlertLevel level) {
        if (level == MetricsFormatter.AlertLevel.CRITICAL) {
            return Color.rgb(248, 81, 73);
        } else if (level == MetricsFormatter.AlertLevel.WARNING) {
            return Color.rgb(240, 136, 62);
        } else {
            return Color.rgb(63, 185, 80);
        }
    }

    private static void addSpacing(LinearLayout layout, int heightPx) {
        View spacer = new View(layout.getContext());
        spacer.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heightPx));
        layout.addView(spacer);
    }

    private static int dpToPx(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
