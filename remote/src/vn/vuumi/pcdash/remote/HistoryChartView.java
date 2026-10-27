package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.i18n.L10n;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class HistoryChartView extends View {

    public enum UnitType {
        PERCENT,
        SPEED,
        TEMP
    }

    private String title = "";
    private UnitType unitType = UnitType.PERCENT;

    private String label1 = "";
    private int color1 = Color.rgb(63, 185, 80);
    private double[] series1 = null;

    private String label2 = null;
    private int color2 = Color.rgb(163, 113, 247);
    private double[] series2 = null;

    private long[] timestamps = null;
    private int[] onlineStatus = null;

    private final Paint bgPaint = new Paint();
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line1Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line2Paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legendDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint offlinePaint = new Paint();

    private final Path path1 = new Path();
    private final Path path2 = new Path();

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final Date reusableDate = new Date();
    private float density = 1.0f;
    public HistoryChartView(Context context) {
        super(context);
        init();
    }

    public HistoryChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        bgPaint.setColor(Color.rgb(16, 20, 26)); // #161b22
        bgPaint.setStyle(Paint.Style.FILL);

        gridPaint.setColor(Color.rgb(48, 54, 61)); // #30363d
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dpToPx(1));
        gridPaint.setPathEffect(new DashPathEffect(new float[]{dpToPx(4), dpToPx(4)}, 0));

        textPaint.setColor(Color.rgb(139, 148, 158)); // #8b949e
        textPaint.setTextSize(dpToPx(10));

        titlePaint.setColor(Color.rgb(230, 237, 243)); // #e6edf3
        titlePaint.setTextSize(dpToPx(13));
        titlePaint.setFakeBoldText(true);

        float stroke = dpToPx(2);
        line1Paint.setStyle(Paint.Style.STROKE);
        line1Paint.setStrokeWidth(stroke);
        line1Paint.setStrokeJoin(Paint.Join.ROUND);
        line1Paint.setStrokeCap(Paint.Cap.ROUND);

        line2Paint.setStyle(Paint.Style.STROKE);
        line2Paint.setStrokeWidth(stroke);
        line2Paint.setStrokeJoin(Paint.Join.ROUND);
        line2Paint.setStrokeCap(Paint.Cap.ROUND);

        legendDotPaint.setStyle(Paint.Style.FILL);

        offlinePaint.setColor(Color.argb(40, 248, 81, 73)); // mờ đỏ
        offlinePaint.setStyle(Paint.Style.FILL);
    }

    public void setChartConfig(String title, UnitType unitType,
                               String label1, int color1,
                               String label2, int color2) {
        this.title = title;
        this.unitType = unitType;
        this.label1 = label1;
        this.color1 = color1;
        this.line1Paint.setColor(color1);
        this.label2 = label2;
        this.color2 = color2;
        this.line2Paint.setColor(color2);
        invalidate();
    }

    public void setData(long[] ts, Double[] s1, Double[] s2, int[] online) {
        if (matchesData(timestamps, series1, series2, onlineStatus, ts, s1, s2, online)) {
            return;
        }
        timestamps = ts;
        onlineStatus = online;
        series1 = toDoubleArray(s1);
        series2 = toDoubleArray(s2);
        invalidate();
    }

    public void setDataLong(long[] ts, Long[] s1, Long[] s2, int[] online) {
        if (matchesLongData(timestamps, series1, series2, onlineStatus, ts, s1, s2, online)) {
            return;
        }
        timestamps = ts;
        onlineStatus = online;
        series1 = toDoubleArray(s1);
        series2 = toDoubleArray(s2);
        invalidate();
    }

    static boolean matchesData(long[] currentTs, double[] currentS1, double[] currentS2, int[] currentOnline,
                               long[] nextTs, Double[] nextS1, Double[] nextS2, int[] nextOnline) {
        return sameLongArray(currentTs, nextTs)
                && sameNullableDoubleArray(currentS1, nextS1)
                && sameNullableDoubleArray(currentS2, nextS2)
                && sameIntArray(currentOnline, nextOnline);
    }

    private static boolean matchesLongData(long[] currentTs, double[] currentS1, double[] currentS2, int[] currentOnline,
                                           long[] nextTs, Long[] nextS1, Long[] nextS2, int[] nextOnline) {
        return sameLongArray(currentTs, nextTs)
                && sameNullableLongArray(currentS1, nextS1)
                && sameNullableLongArray(currentS2, nextS2)
                && sameIntArray(currentOnline, nextOnline);
    }

    private static double[] toDoubleArray(Double[] series) {
        if (series == null) return null;
        double[] result = new double[series.length];
        for (int i = 0; i < series.length; i++) {
            result[i] = series[i] != null ? series[i] : -1.0;
        }
        return result;
    }

    private static double[] toDoubleArray(Long[] series) {
        if (series == null) return null;
        double[] result = new double[series.length];
        for (int i = 0; i < series.length; i++) {
            result[i] = series[i] != null ? series[i].doubleValue() : -1.0;
        }
        return result;
    }

    private static boolean sameLongArray(long[] current, long[] next) {
        if (current == next) return true;
        if (current == null || next == null || current.length != next.length) return false;
        for (int i = 0; i < current.length; i++) {
            if (current[i] != next[i]) return false;
        }
        return true;
    }

    private static boolean sameIntArray(int[] current, int[] next) {
        if (current == next) return true;
        if (current == null || next == null || current.length != next.length) return false;
        for (int i = 0; i < current.length; i++) {
            if (current[i] != next[i]) return false;
        }
        return true;
    }

    private static boolean sameNullableDoubleArray(double[] current, Double[] next) {
        if (current == null || next == null) return current == null && next == null;
        if (current.length != next.length) return false;
        for (int i = 0; i < current.length; i++) {
            double nextValue = next[i] != null ? next[i] : -1.0;
            if (Double.compare(current[i], nextValue) != 0) return false;
        }
        return true;
    }

    private static boolean sameNullableLongArray(double[] current, Long[] next) {
        if (current == null || next == null) return current == null && next == null;
        if (current.length != next.length) return false;
        for (int i = 0; i < current.length; i++) {
            double nextValue = next[i] != null ? next[i].doubleValue() : -1.0;
            if (Double.compare(current[i], nextValue) != 0) return false;
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Vẽ nền bo góc hoặc phẳng
        canvas.drawRoundRect(0, 0, w, h, dpToPx(12), dpToPx(12), bgPaint);

        float padLeft = dpToPx(unitType == UnitType.SPEED ? 58 : 38);
        float padRight = dpToPx(16);
        float padTop = dpToPx(32);
        float padBottom = dpToPx(24);

        // 1. Tiêu đề
        if (title != null && !title.isEmpty()) {
            canvas.drawText(title, dpToPx(12), dpToPx(18), titlePaint);
        }

        // 2. Chú thích (Legend)
        float legendRight = w - padRight;
        boolean hasSeries1 = hasValues(series1);
        boolean hasSeries2 = hasValues(series2);
        boolean showLegend = timestamps != null && timestamps.length > 0;
        if (label2 != null && !label2.isEmpty() && (!showLegend || hasSeries2)) {
            float textW = textPaint.measureText(label2);
            legendRight -= textW;
            canvas.drawText(label2, legendRight, dpToPx(18), textPaint);
            legendRight -= dpToPx(14);
            legendDotPaint.setColor(color2);
            canvas.drawCircle(legendRight + dpToPx(5), dpToPx(14), dpToPx(4), legendDotPaint);
            legendRight -= dpToPx(12);
        }
        if (label1 != null && !label1.isEmpty() && (!showLegend || hasSeries1)) {
            float textW = textPaint.measureText(label1);
            legendRight -= textW;
            canvas.drawText(label1, legendRight, dpToPx(18), textPaint);
            legendRight -= dpToPx(14);
            legendDotPaint.setColor(color1);
            canvas.drawCircle(legendRight + dpToPx(5), dpToPx(14), dpToPx(4), legendDotPaint);
        }

        float chartLeft = padLeft;
        float chartTop = padTop;
        float chartRight = w - padRight;
        float chartBottom = h - padBottom;
        float chartWidth = chartRight - chartLeft;
        float chartHeight = chartBottom - chartTop;

        if (chartWidth <= 0 || chartHeight <= 0) return;

        // Tính max Y
        double maxY = 100.0;
        if (unitType == UnitType.SPEED) {
            double foundMax = 0;
            if (series1 != null) {
                for (double v : series1) {
                    if (v > foundMax) foundMax = v;
                }
            }
            if (series2 != null) {
                for (double v : series2) {
                    if (v > foundMax) foundMax = v;
                }
            }
            maxY = Math.max(foundMax * 1.15, 1024.0); // Tối thiểu 1 KB/s
        }

        // 3. Trục Y & Lưới ngang (0%, 50%, 100%)
        drawYGridAndLabel(canvas, chartLeft, chartRight, chartBottom, chartTop, 0.0, maxY);
        drawYGridAndLabel(canvas, chartLeft, chartRight, chartBottom, chartTop, 0.5, maxY);
        drawYGridAndLabel(canvas, chartLeft, chartRight, chartBottom, chartTop, 1.0, maxY);

        // 4. Nếu không có dữ liệu
        if (timestamps == null || timestamps.length == 0 || (series1 == null && series2 == null)) {
            String emptyText = L10n.t("No data yet", "Chưa có dữ liệu");
            float tw = textPaint.measureText(emptyText);
            canvas.drawText(emptyText, chartLeft + (chartWidth - tw) / 2, chartTop + chartHeight / 2, textPaint);
            return;
        }

        int count = timestamps.length;

        // 5. Trục X (thời gian đầu và cuối)
        if (count >= 2) {
            reusableDate.setTime(timestamps[0]);
            String startStr = timeFmt.format(reusableDate);
            reusableDate.setTime(timestamps[count - 1]);
            String endStr = timeFmt.format(reusableDate);
            canvas.drawText(startStr, chartLeft, h - dpToPx(8), textPaint);
            float endW = textPaint.measureText(endStr);
            canvas.drawText(endStr, chartRight - endW, h - dpToPx(8), textPaint);
        }

        // 6. Đánh dấu khoảng Offline
        if (onlineStatus != null && onlineStatus.length == count) {
            for (int i = 0; i < count; i++) {
                if (onlineStatus[i] == 0) {
                    float x1 = chartLeft + (i > 0 ? ((i - 0.5f) / (count - 1)) * chartWidth : 0);
                    float x2 = chartLeft + (i < count - 1 ? ((i + 0.5f) / (count - 1)) * chartWidth : chartWidth);
                    canvas.drawRect(x1, chartTop, x2, chartBottom, offlinePaint);
                }
            }
        }

        // 7. Vẽ đường đồ thị
        if (series1 != null && series1.length == count) {
            drawSeriesPath(canvas, series1, count, chartLeft, chartTop, chartWidth, chartHeight, maxY, path1, line1Paint);
        }
        if (series2 != null && series2.length == count) {
            drawSeriesPath(canvas, series2, count, chartLeft, chartTop, chartWidth, chartHeight, maxY, path2, line2Paint);
        }
    }

    private void drawYGridAndLabel(Canvas canvas, float left, float right, float bottom, float top, double fraction, double maxY) {
        float y = (float) (bottom - fraction * (bottom - top));
        canvas.drawLine(left, y, right, y, gridPaint);

        double val = fraction * maxY;
        String labelStr;
        if (unitType == UnitType.PERCENT) {
            labelStr = Math.round(val) + "%";
        } else if (unitType == UnitType.TEMP) {
            labelStr = Math.round(val) + "°";
        } else {
            labelStr = formatCompactSpeed(val) + "/s";
        }
        float textW = textPaint.measureText(labelStr);
        canvas.drawText(labelStr, left - textW - dpToPx(4), y + dpToPx(3), textPaint);
    }

    private static boolean hasValues(double[] series) {
        if (series == null) return false;
        for (double v : series) {
            if (v >= 0) return true;
        }
        return false;
    }

    static String formatCompactSpeed(double bytesPerSec) {
        if (bytesPerSec < 1024.0) {
            return Math.round(bytesPerSec) + "B";
        }
        double kb = bytesPerSec / 1024.0;
        if (kb < 1024.0) {
            return Math.round(kb) + "K";
        }
        double mb = kb / 1024.0;
        long tenth = Math.round(mb * 10.0);
        return (tenth / 10) + "." + (tenth % 10) + "M";
    }

    private void drawSeriesPath(Canvas canvas, double[] series, int count,
                                float left, float top, float width, float height,
                                double maxY, Path path, Paint paint) {
        path.reset();
        boolean hasStarted = false;

        for (int i = 0; i < count; i++) {
            double v = series[i];
            if (v < 0) {
                // Missing data point / offline: break line
                hasStarted = false;
                continue;
            }

            float x = left + (count > 1 ? ((float) i / (count - 1)) * width : width / 2);
            float ratio = (float) (v / maxY);
            if (ratio > 1.0f) ratio = 1.0f;
            if (ratio < 0.0f) ratio = 0.0f;
            float y = (top + height) - ratio * height;

            if (!hasStarted) {
                path.moveTo(x, y);
                hasStarted = true;
            } else {
                path.lineTo(x, y);
            }
        }

        canvas.drawPath(path, paint);
    }

    private float dpToPx(float dp) {
        return dp * density;
    }
}
