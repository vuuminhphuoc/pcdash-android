package vn.vuumi.pcdash.remote;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LogTabView implements TabContent {

    public interface Callback {
        void onLoadLog(LogResultCallback callback);
    }

    public interface LogResultCallback {
        void onResult(LogParser.LogResponse response, Exception error);
    }

    private final ScrollView scrollView;
    private final TextView statusView;
    private final ProgressBar progressBar;
    private final LinearLayout listContainer;
    private final Callback callback;
    private boolean hasLoaded = false;

    public LogTabView(final Context context, final Callback callback) {
        this.callback = callback;

        scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(Color.rgb(0, 0, 0));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dpToPx(context, 24), dpToPx(context, 24), dpToPx(context, 24), dpToPx(context, 32));
        scrollView.addView(container);

        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText("Nhật ký lệnh");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(230, 237, 243));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        title.setLayoutParams(titleLp);
        headerRow.addView(title);

        Button refreshBtn = new Button(context);
        refreshBtn.setText("Làm mới");
        refreshBtn.setTextColor(Color.rgb(88, 166, 255));
        refreshBtn.setBackgroundColor(Color.rgb(33, 38, 45));
        refreshBtn.setTextSize(13);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadLogs();
            }
        });
        headerRow.addView(refreshBtn);
        container.addView(headerRow);

        TextView sub = new TextView(context);
        sub.setText("Lịch sử các lệnh điều khiển gửi tới host");
        sub.setTextSize(13);
        sub.setTextColor(Color.rgb(139, 148, 158));
        sub.setPadding(0, dpToPx(context, 4), 0, dpToPx(context, 16));
        container.addView(sub);

        statusView = new TextView(context);
        statusView.setText("Đang tải nhật ký lệnh...");
        statusView.setTextSize(13);
        statusView.setTextColor(Color.rgb(139, 148, 158));
        statusView.setPadding(0, dpToPx(context, 8), 0, dpToPx(context, 8));
        container.addView(statusView);

        progressBar = new ProgressBar(context);
        progressBar.setPadding(0, dpToPx(context, 16), 0, dpToPx(context, 16));
        container.addView(progressBar);

        listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        container.addView(listContainer);
    }

    @Override
    public View view() {
        return scrollView;
    }

    @Override
    public void onShow() {
        if (!hasLoaded) {
            loadLogs();
        }
    }

    @Override
    public void onHide() {}

    public void loadLogs() {
        statusView.setVisibility(View.VISIBLE);
        statusView.setTextColor(Color.rgb(139, 148, 158));
        statusView.setText("Đang tải nhật ký lệnh...");
        progressBar.setVisibility(View.VISIBLE);
        listContainer.removeAllViews();

        if (callback != null) {
            callback.onLoadLog(new LogResultCallback() {
                @Override
                public void onResult(final LogParser.LogResponse response, final Exception error) {
                    hasLoaded = true;
                    progressBar.setVisibility(View.GONE);

                    if (error != null) {
                        statusView.setVisibility(View.VISIBLE);
                        statusView.setTextColor(Color.rgb(248, 81, 73));
                        if (error instanceof RemoteApiClient.UnsupportedRouteException
                                || (error.getMessage() != null && error.getMessage().contains("404"))) {
                            statusView.setText("Máy ở nhà chưa hỗ trợ, hãy cập nhật PCDash host");
                        } else {
                            statusView.setText("Lỗi: " + (error.getMessage() != null ? error.getMessage() : "Timeout"));
                        }
                        return;
                    }

                    if (response == null || response.entries == null || response.entries.isEmpty()) {
                        statusView.setVisibility(View.VISIBLE);
                        statusView.setTextColor(Color.rgb(139, 148, 158));
                        statusView.setText("Chưa có lệnh nào được ghi nhận.");
                        return;
                    }

                    statusView.setVisibility(View.GONE);
                    listContainer.removeAllViews();

                    Context ctx = listContainer.getContext();
                    SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault());
                    for (LogParser.LogEntry entry : response.entries) {
                        LinearLayout row = new LinearLayout(ctx);
                        row.setOrientation(LinearLayout.VERTICAL);
                        row.setPadding(0, dpToPx(ctx, 12), 0, dpToPx(ctx, 12));

                        TextView line1 = new TextView(ctx);
                        line1.setTextSize(13);
                        line1.setTypeface(Typeface.DEFAULT_BOLD);
                        String statusMark = entry.ok ? "✓ Thành công" : "✗ Thất bại";
                        line1.setText(entry.action.toUpperCase(Locale.ROOT) + "  ·  " + statusMark);
                        if (entry.ok) {
                            line1.setTextColor(Color.rgb(63, 185, 80));
                        } else {
                            line1.setTextColor(Color.rgb(248, 81, 73));
                        }
                        row.addView(line1);

                        TextView line2 = new TextView(ctx);
                        line2.setTextSize(11);
                        line2.setTextColor(Color.rgb(139, 148, 158));
                        line2.setText(sdf.format(new Date(entry.ts)) + "  ·  Thiết bị: " + entry.device + " (" + entry.source + ")");
                        row.addView(line2);

                        View divider = new View(ctx);
                        divider.setBackgroundColor(Color.rgb(48, 54, 61));
                        divider.setLayoutParams(new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, 1));
                        row.addView(divider);

                        listContainer.addView(row);
                    }
                }
            });
        }
    }

    private static int dpToPx(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
