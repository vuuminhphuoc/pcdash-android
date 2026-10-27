package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.ui.Tap;
import vn.vuumi.pcdash.i18n.L10n;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class ControlTabView implements TabContent {

    public interface Callback {
        void onActionClicked(String action);
        void onStartUpdate();
        void onCancelUpdate();
    }

    private final ScrollView scrollView;
    private final TextView pcStatusView;
    private final TextView pcDetailView;
    // What the last power command is doing (sending, waiting for the PC, how it went).
    private final TextView actionLineView;
    private final Map<String, ActionTile> tiles = new LinkedHashMap<String, ActionTile>();
    private final Runnable hideActionLine = new Runnable() {
        @Override
        public void run() {
            actionLineView.setVisibility(View.GONE);
        }
    };

    private final LinearLayout updateCardContainer;
    private final TextView updateTitleView;
    private final TextView updateSubView;
    private final TextView permissionNoticeView;
    private final ProgressBar updateProgressBar;
    private final Button updateBtn;
    private final Button cancelUpdateBtn;
    private final Button retryUpdateBtn;

    public ControlTabView(Context context, final Callback callback) {
        scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(Color.rgb(0, 0, 0));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dpToPx(context, 24), dpToPx(context, 24), dpToPx(context, 24), dpToPx(context, 32));
        scrollView.addView(container);

        // Status Card
        LinearLayout statusCard = new LinearLayout(context);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dpToPx(context, 18), dpToPx(context, 16), dpToPx(context, 18), dpToPx(context, 16));
        UiKit.styleCard(statusCard);

        pcStatusView = new TextView(context);
        pcStatusView.setText(L10n.t("Checking the PC...", "Đang kiểm tra trạng thái PC..."));
        pcStatusView.setTextSize(18);
        pcStatusView.setTypeface(Typeface.DEFAULT_BOLD);
        pcStatusView.setTextColor(Color.rgb(210, 153, 34));
        statusCard.addView(pcStatusView);

        pcDetailView = new TextView(context);
        pcDetailView.setText(L10n.t("Loading...", "Đang tải dữ liệu..."));
        pcDetailView.setTextSize(12);
        pcDetailView.setTextColor(Color.rgb(139, 148, 158));
        statusCard.addView(pcDetailView);

        actionLineView = new TextView(context);
        actionLineView.setTextSize(13);
        actionLineView.setPadding(0, dpToPx(context, 8), 0, 0);
        actionLineView.setVisibility(View.GONE);
        actionLineView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusCard.addView(actionLineView);

        container.addView(statusCard);
        addSpacing(container, dpToPx(context, 18));

        // Update Card
        updateCardContainer = new LinearLayout(context);
        updateCardContainer.setOrientation(LinearLayout.VERTICAL);
        updateCardContainer.setPadding(dpToPx(context, 18), dpToPx(context, 14), dpToPx(context, 18), dpToPx(context, 14));
        UiKit.styleCard(updateCardContainer);
        updateCardContainer.setVisibility(View.GONE);

        updateTitleView = new TextView(context);
        updateTitleView.setTextSize(15);
        updateTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        updateTitleView.setTextColor(Color.rgb(230, 237, 243));
        updateCardContainer.addView(updateTitleView);

        updateSubView = new TextView(context);
        updateSubView.setTextSize(12);
        updateSubView.setTextColor(Color.rgb(139, 148, 158));
        updateSubView.setPadding(0, dpToPx(context, 4), 0, 0);
        updateCardContainer.addView(updateSubView);

        permissionNoticeView = new TextView(context);
        permissionNoticeView.setTextSize(13);
        permissionNoticeView.setTextColor(Color.rgb(240, 136, 62));
        permissionNoticeView.setText(L10n.t("Turn on Allow from this source, then come back and tap Update", "Bật Cho phép từ nguồn này rồi quay lại bấm Cập nhật"));
        permissionNoticeView.setPadding(0, dpToPx(context, 4), 0, 0);
        updateCardContainer.addView(permissionNoticeView);

        updateProgressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        updateProgressBar.setMax(100);
        updateProgressBar.setPadding(0, dpToPx(context, 8), 0, dpToPx(context, 8));
        updateCardContainer.addView(updateProgressBar);

        LinearLayout updateActionRow = new LinearLayout(context);
        updateActionRow.setOrientation(LinearLayout.HORIZONTAL);
        updateActionRow.setPadding(0, dpToPx(context, 8), 0, 0);

        updateBtn = new Button(context);
        updateBtn.setText(L10n.t("Update", "Cập nhật"));
        updateBtn.setTextColor(Color.WHITE);
        updateBtn.setBackgroundColor(Color.rgb(27, 102, 42));
        Tap.wire(updateBtn, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callback != null) callback.onStartUpdate();
            }
        });
        updateActionRow.addView(updateBtn);

        cancelUpdateBtn = new Button(context);
        cancelUpdateBtn.setText(L10n.t("Cancel", "Hủy"));
        cancelUpdateBtn.setTextColor(Color.WHITE);
        cancelUpdateBtn.setBackgroundColor(Color.rgb(110, 118, 129));
        Tap.wire(cancelUpdateBtn, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callback != null) callback.onCancelUpdate();
            }
        });
        updateActionRow.addView(cancelUpdateBtn);

        retryUpdateBtn = new Button(context);
        retryUpdateBtn.setText(L10n.t("Retry", "Thử lại"));
        retryUpdateBtn.setTextColor(Color.WHITE);
        retryUpdateBtn.setBackgroundColor(Color.rgb(26, 84, 176));
        Tap.wire(retryUpdateBtn, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callback != null) callback.onStartUpdate();
            }
        });
        updateActionRow.addView(retryUpdateBtn);

        updateCardContainer.addView(updateActionRow);
        container.addView(updateCardContainer);

        // Control buttons
        container.addView(UiKit.sectionLabel(context, L10n.t("POWER", "NGUỒN")));
        container.addView(makeTile(context, IconDrawable.Type.WAKE, L10n.t("Turn on / Wake PC", "Bật / Đánh thức PC"),
                Color.rgb(27, 102, 42), 0, Color.WHITE, 60, "wake", callback));
        addSpacing(container, dpToPx(context, 10));

        LinearLayout sleepRow = new LinearLayout(context);
        sleepRow.setOrientation(LinearLayout.HORIZONTAL);
        View sleepTile = makeTile(context, IconDrawable.Type.SLEEP, "Sleep",
                Color.rgb(26, 84, 176), 0, Color.WHITE, 56, "sleep", callback);
        LinearLayout.LayoutParams sleepLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        sleepLp.rightMargin = dpToPx(context, 5);
        sleepTile.setLayoutParams(sleepLp);
        sleepRow.addView(sleepTile);
        View hibernateTile = makeTile(context, IconDrawable.Type.HIBERNATE, "Hibernate",
                Color.rgb(100, 64, 168), 0, Color.WHITE, 56, "hibernate", callback);
        LinearLayout.LayoutParams hibLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        hibLp.leftMargin = dpToPx(context, 5);
        hibernateTile.setLayoutParams(hibLp);
        sleepRow.addView(hibernateTile);
        container.addView(sleepRow);
        addSpacing(container, dpToPx(context, 18));

        container.addView(UiKit.sectionLabel(context, L10n.t("DANGER ZONE", "VÙNG NGUY HIỂM")));
        int danger = Color.rgb(248, 81, 73);
        container.addView(makeTile(context, IconDrawable.Type.SHUTDOWN, L10n.t("Shut down", "Tắt máy (Shutdown)"),
                Color.rgb(34, 20, 22), danger, danger, 56, "shutdown", callback));
        addSpacing(container, dpToPx(context, 10));
        container.addView(makeTile(context, IconDrawable.Type.ABORT, L10n.t("Cancel shutdown", "Hủy lệnh tắt máy"),
                UiKit.CARD, UiKit.BORDER, Color.rgb(201, 209, 217), 48, "abort", callback));
    }

    private View makeTile(Context ctx, IconDrawable.Type icon, String label, int fill, int stroke,
                          int contentColor, int heightDp, final String action, final Callback callback) {
        ActionTile tile = new ActionTile(ctx, icon, label, fill, stroke, contentColor, heightDp, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callback != null) {
                    callback.onActionClicked(action);
                }
            }
        });
        tile.view.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        tiles.put(action, tile);
        return tile.view;
    }

    /** The command is on its way to the home phone: spinner on its tile, the other tiles wait. */
    public void showSending(String action) {
        for (Map.Entry<String, ActionTile> e : tiles.entrySet()) {
            if (e.getKey().equals(action)) {
                e.getValue().busy(L10n.t("Sending...", "Đang gửi..."));
            } else {
                // A new command replaces whatever the last one was still showing.
                e.getValue().idle();
                e.getValue().setAvailable(false);
            }
        }
        setActionLine(null, 0, false);
    }

    /** The home phone took the command; the tile keeps spinning while the PC changes state. */
    public void showWaiting(String action, String line) {
        for (Map.Entry<String, ActionTile> e : tiles.entrySet()) {
            if (e.getKey().equals(action)) {
                e.getValue().busy(L10n.t("Waiting...", "Đang chờ..."));
            } else if (e.getValue().isIdle()) {
                e.getValue().setAvailable(true);
            }
        }
        setActionLine(line, UiKit.ACCENT, false);
    }

    /** How the command went: a tick or a cross on its tile for a moment, and a line under the status. */
    public void showOutcome(String action, boolean ok, String tileText, String line, int lineColor) {
        for (Map.Entry<String, ActionTile> e : tiles.entrySet()) {
            if (e.getKey().equals(action)) {
                e.getValue().result(ok, tileText);
            } else if (e.getValue().isIdle()) {
                e.getValue().setAvailable(true);
            }
        }
        setActionLine(line, lineColor, true);
    }

    /** Back to plain tiles (the command was dropped when the app left the screen). */
    public void resetActions() {
        for (ActionTile tile : tiles.values()) {
            tile.idle();
        }
        setActionLine(null, 0, false);
    }

    private void setActionLine(String text, int color, boolean autoHide) {
        actionLineView.removeCallbacks(hideActionLine);
        if (text == null || text.isEmpty()) {
            actionLineView.setVisibility(View.GONE);
            return;
        }
        actionLineView.setText(text);
        actionLineView.setTextColor(color);
        actionLineView.setVisibility(View.VISIBLE);
        if (autoHide) {
            actionLineView.postDelayed(hideActionLine, 10_000L);
        }
    }

    @Override
    public View view() {
        return scrollView;
    }

    @Override
    public void onShow() {}

    @Override
    public void onHide() {}

    public void updateStatus(RemoteApiClient.RemoteStatus status, Exception error) {
        if (status != null) {
            if (status.online) {
                pcStatusView.setText(L10n.t("PC is ON", "PC đang BẬT"));
                pcStatusView.setTextColor(Color.rgb(63, 185, 80));
                String detail = L10n.t("Port: ", "Cổng: ") + (status.port != null ? status.port : 8799)
                        + L10n.t("  ·  Latency: ", "  ·  Độ trễ: ") + status.rttMs + " ms";
                pcDetailView.setText(detail);
            } else {
                pcStatusView.setText(L10n.t("PC is OFF / not responding", "PC đang TẮT / không phản hồi"));
                pcStatusView.setTextColor(Color.rgb(248, 81, 73));
                pcDetailView.setText(L10n.t("Cannot reach the PC", "Không thể kết nối đến PC"));
            }
        } else if (error != null) {
            pcStatusView.setText(L10n.t("Cannot reach the home phone", "Không kết nối được tới máy ở nhà"));
            pcStatusView.setTextColor(Color.rgb(248, 81, 73));
            String shortMsg = (error.getMessage() != null && !error.getMessage().isEmpty())
                    ? error.getMessage() : L10n.t("Timed out", "Hết thời gian chờ (timeout)");
            pcDetailView.setText(shortMsg);
        }
    }

    public void updateUpdateCardUI(RemoteMainActivity.UpdateState updateState, UpdateInfo availableUpdate,
                                   int currentDownloadProgress, long currentDownloadBytes, String updateErrorMessage) {
        if (updateCardContainer == null) return;
        switch (updateState) {
            case IDLE:
                updateCardContainer.setVisibility(View.GONE);
                break;
            case AVAILABLE:
                if (availableUpdate == null) {
                    updateCardContainer.setVisibility(View.GONE);
                    return;
                }
                updateCardContainer.setVisibility(View.VISIBLE);
                String sizeMb = String.format(Locale.US, "%.1f", availableUpdate.size / (1024.0 * 1024.0));
                updateCardContainer.setBackground(GlassKit.tintedCard(updateCardContainer.getContext(),
                        Color.rgb(13, 28, 19), Color.rgb(27, 102, 42)));
                updateTitleView.setText(L10n.t("Update available: v", "Có bản cập nhật v") + availableUpdate.versionName + " (" + sizeMb + " MB)");
                updateTitleView.setTextColor(Color.rgb(230, 237, 243));
                updateSubView.setVisibility(View.GONE);
                permissionNoticeView.setVisibility(View.GONE);
                updateProgressBar.setVisibility(View.GONE);
                updateBtn.setVisibility(View.VISIBLE);
                cancelUpdateBtn.setVisibility(View.GONE);
                retryUpdateBtn.setVisibility(View.GONE);
                break;
            case DOWNLOADING:
                updateCardContainer.setVisibility(View.VISIBLE);
                updateCardContainer.setBackground(GlassKit.tintedCard(updateCardContainer.getContext(),
                        Color.rgb(13, 22, 36), Color.rgb(26, 84, 176)));
                updateTitleView.setText(L10n.t("Downloading update v", "Đang tải bản cập nhật v") + (availableUpdate != null ? availableUpdate.versionName : "") + "...");
                updateTitleView.setTextColor(Color.rgb(230, 237, 243));
                permissionNoticeView.setVisibility(View.GONE);
                updateProgressBar.setVisibility(View.VISIBLE);
                updateProgressBar.setProgress(currentDownloadProgress);
                double readMb = currentDownloadBytes / (1024.0 * 1024.0);
                double totalMb = (availableUpdate != null && availableUpdate.size > 0) ? availableUpdate.size / (1024.0 * 1024.0) : 0;
                updateSubView.setText(String.format(Locale.US, L10n.t("Downloading: %.1f / %.1f MB (%d%%)", "Đang tải: %.1f / %.1f MB (%d%%)"), readMb, totalMb, currentDownloadProgress));
                updateSubView.setTextColor(Color.rgb(139, 148, 158));
                updateSubView.setVisibility(View.VISIBLE);
                updateBtn.setVisibility(View.GONE);
                cancelUpdateBtn.setVisibility(View.VISIBLE);
                retryUpdateBtn.setVisibility(View.GONE);
                break;
            case INSTALL_PERMISSION:
                updateCardContainer.setVisibility(View.VISIBLE);
                updateCardContainer.setBackground(GlassKit.tintedCard(updateCardContainer.getContext(),
                        Color.rgb(32, 23, 12), Color.rgb(120, 70, 30)));
                updateTitleView.setText(L10n.t("Install permission needed", "Yêu cầu quyền cài đặt"));
                updateTitleView.setTextColor(Color.rgb(240, 136, 62));
                permissionNoticeView.setVisibility(View.VISIBLE);
                updateSubView.setVisibility(View.GONE);
                updateProgressBar.setVisibility(View.GONE);
                updateBtn.setVisibility(View.VISIBLE);
                cancelUpdateBtn.setVisibility(View.GONE);
                retryUpdateBtn.setVisibility(View.GONE);
                break;
            case ERROR:
                updateCardContainer.setVisibility(View.VISIBLE);
                updateCardContainer.setBackground(GlassKit.tintedCard(updateCardContainer.getContext(),
                        Color.rgb(34, 20, 22), Color.rgb(140, 50, 46)));
                updateTitleView.setText(L10n.t("Update failed", "Cập nhật không thành công"));
                updateTitleView.setTextColor(Color.rgb(248, 81, 73));
                permissionNoticeView.setVisibility(View.GONE);
                updateSubView.setText(updateErrorMessage != null ? updateErrorMessage : L10n.t("Something went wrong", "Đã xảy ra lỗi"));
                updateSubView.setTextColor(Color.rgb(248, 81, 73));
                updateSubView.setVisibility(View.VISIBLE);
                updateProgressBar.setVisibility(View.GONE);
                updateBtn.setVisibility(View.GONE);
                cancelUpdateBtn.setVisibility(View.GONE);
                retryUpdateBtn.setVisibility(View.VISIBLE);
                break;
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
