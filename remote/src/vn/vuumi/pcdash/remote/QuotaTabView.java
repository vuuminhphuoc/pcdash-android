package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.ui.Tap;
import vn.vuumi.pcdash.i18n.L10n;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class QuotaTabView implements TabContent {

    public interface Callback {
        void onLoadQuota(boolean refresh, QuotaResultCallback callback);
    }

    public interface QuotaResultCallback {
        void onResult(QuotaSnapshot snapshot, Exception error);
    }

    private static final long REFRESH_INTERVAL_MS = 2 * 60 * 1000L;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ScrollView scrollView;
    private final TextView updateTimeView;
    private final TextView staleBannerView;
    private final TextView errorBannerView;
    private final TextView statusView;
    private final ProgressBar progressBar;
    private final LinearLayout cardsContainer;
    private final Callback callback;

    private final Set<String> expandedCardIds = new HashSet<String>();
    private QuotaSnapshot lastSnapshot = null;
    private QuotaSnapshot lastRenderedSnapshot = null;
    private boolean isShown = false;

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!isShown) {
                return;
            }
            loadQuota(false);
            uiHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    public QuotaTabView(final Context context, final Callback callback) {
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
        title.setText("Quota");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(230, 237, 243));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        title.setLayoutParams(titleLp);
        headerRow.addView(title);

        Button refreshBtn = new Button(context);
        refreshBtn.setText(L10n.t("Refresh", "Làm mới"));
        refreshBtn.setTextColor(Color.rgb(88, 166, 255));
        refreshBtn.setBackground(UiKit.withRipple(UiKit.roundedFill(context, Color.rgb(33, 38, 45), 10)));
        refreshBtn.setTextSize(13);
        Tap.wire(refreshBtn, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uiHandler.removeCallbacks(pollTask);
                loadQuota(true);
                if (isShown) {
                    uiHandler.postDelayed(pollTask, REFRESH_INTERVAL_MS);
                }
            }
        });
        headerRow.addView(refreshBtn);
        container.addView(headerRow);

        TextView sub = new TextView(context);
        sub.setText(L10n.t("Remaining quota of your AI services", "Hạn mức còn lại của các dịch vụ AI"));
        sub.setTextSize(13);
        sub.setTextColor(Color.rgb(139, 148, 158));
        sub.setPadding(0, dpToPx(context, 4), 0, dpToPx(context, 10));
        container.addView(sub);

        updateTimeView = new TextView(context);
        updateTimeView.setTextSize(12);
        updateTimeView.setTextColor(Color.rgb(139, 148, 158));
        updateTimeView.setPadding(0, 0, 0, dpToPx(context, 10));
        updateTimeView.setVisibility(View.GONE);
        container.addView(updateTimeView);

        staleBannerView = new TextView(context);
        staleBannerView.setText(L10n.t("The PC is off, showing older numbers", "PC đang tắt, hiển thị số cũ"));
        staleBannerView.setTextSize(12);
        staleBannerView.setTypeface(Typeface.DEFAULT_BOLD);
        staleBannerView.setTextColor(Color.rgb(240, 136, 62));
        staleBannerView.setBackgroundColor(Color.rgb(45, 30, 15));
        staleBannerView.setPadding(dpToPx(context, 12), dpToPx(context, 8), dpToPx(context, 12), dpToPx(context, 8));
        LinearLayout.LayoutParams staleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        staleLp.setMargins(0, 0, 0, dpToPx(context, 12));
        staleBannerView.setLayoutParams(staleLp);
        staleBannerView.setVisibility(View.GONE);
        container.addView(staleBannerView);

        errorBannerView = new TextView(context);
        errorBannerView.setTextSize(12);
        errorBannerView.setTextColor(Color.rgb(248, 81, 73));
        errorBannerView.setBackgroundColor(Color.rgb(45, 18, 18));
        errorBannerView.setPadding(dpToPx(context, 12), dpToPx(context, 8), dpToPx(context, 12), dpToPx(context, 8));
        LinearLayout.LayoutParams errorLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        errorLp.setMargins(0, 0, 0, dpToPx(context, 12));
        errorBannerView.setLayoutParams(errorLp);
        errorBannerView.setVisibility(View.GONE);
        container.addView(errorBannerView);

        statusView = new TextView(context);
        statusView.setText(L10n.t("Fetching...", "Đang lấy dữ liệu..."));
        statusView.setTextSize(14);
        statusView.setTextColor(Color.rgb(139, 148, 158));
        statusView.setPadding(0, dpToPx(context, 16), 0, dpToPx(context, 16));
        statusView.setVisibility(View.GONE);
        container.addView(statusView);

        progressBar = new ProgressBar(context);
        progressBar.setPadding(0, dpToPx(context, 16), 0, dpToPx(context, 16));
        progressBar.setVisibility(View.GONE);
        container.addView(progressBar);

        cardsContainer = new LinearLayout(context);
        cardsContainer.setOrientation(LinearLayout.VERTICAL);
        container.addView(cardsContainer);
    }

    @Override
    public View view() {
        return scrollView;
    }

    @Override
    public void onShow() {
        isShown = true;
        uiHandler.removeCallbacks(pollTask);
        loadQuota(false);
        uiHandler.postDelayed(pollTask, REFRESH_INTERVAL_MS);
    }

    @Override
    public void onHide() {
        isShown = false;
        uiHandler.removeCallbacks(pollTask);
    }

    public void loadQuota(final boolean refresh) {
        if (callback == null) {
            return;
        }
        if (lastSnapshot == null) {
            statusView.setText(L10n.t("Fetching...", "Đang lấy dữ liệu..."));
            statusView.setTextColor(Color.rgb(139, 148, 158));
            statusView.setVisibility(View.VISIBLE);
        }
        progressBar.setVisibility(View.VISIBLE);
        errorBannerView.setVisibility(View.GONE);

        callback.onLoadQuota(refresh, new QuotaResultCallback() {
            @Override
            public void onResult(final QuotaSnapshot snapshot, final Exception error) {
                if (!isShown) {
                    if (snapshot != null) {
                        lastSnapshot = snapshot;
                    }
                    return;
                }
                progressBar.setVisibility(View.GONE);
                if (error != null) {
                    handleError(error);
                    return;
                }

                if (snapshot == null) {
                    if (lastSnapshot == null) {
                        statusView.setText(L10n.t("No quota data yet", "Chưa có dữ liệu quota"));
                        statusView.setTextColor(Color.rgb(139, 148, 158));
                        statusView.setVisibility(View.VISIBLE);
                        cardsContainer.removeAllViews();
                    }
                    return;
                }

                lastSnapshot = snapshot;
                renderSnapshot(snapshot);
            }
        });
    }

    private void handleError(Exception error) {
        String msg;
        if (error instanceof RemoteApiClient.UnsupportedRouteException
                || (error.getMessage() != null && error.getMessage().contains("404"))) {
            msg = RemoteApiClient.UnsupportedRouteException.hostNeedsUpdateMessage();
        } else {
            msg = L10n.t("Error: ", "Lỗi: ") + (error.getMessage() != null ? error.getMessage() : L10n.t("Cannot reach the PC", "Không thể kết nối tới PC"));
        }

        if (lastSnapshot != null) {
            errorBannerView.setText(msg);
            errorBannerView.setVisibility(View.VISIBLE);
        } else {
            statusView.setText(msg);
            statusView.setTextColor(Color.rgb(248, 81, 73));
            statusView.setVisibility(View.VISIBLE);
            cardsContainer.removeAllViews();
        }
    }

    private void renderSnapshot(QuotaSnapshot snapshot) {
        if (lastRenderedSnapshot != null && isSameSnapshot(lastRenderedSnapshot, snapshot)) {
            return;
        }
        lastRenderedSnapshot = snapshot;
        errorBannerView.setVisibility(View.GONE);
        if (snapshot.stale && !snapshot.loading && snapshot.providers != null && !snapshot.providers.isEmpty()) {
            staleBannerView.setText(L10n.t("The PC is off, showing older numbers", "PC đang tắt, hiển thị số cũ"));
            staleBannerView.setVisibility(View.VISIBLE);
        } else {
            staleBannerView.setVisibility(View.GONE);
        }

        if (snapshot.fetchedAt > 0) {
            updateTimeView.setText(QuotaFormatter.formatUpdatedTime(snapshot.fetchedAt));
            updateTimeView.setVisibility(View.VISIBLE);
        } else {
            updateTimeView.setVisibility(View.GONE);
        }

        if (snapshot.error != null && !snapshot.error.isEmpty()) {
            String errorMsg;
            if ("omp_unavailable".equals(snapshot.error)) {
                errorMsg = L10n.t("No data: omp is not available on the PC", "Không thể lấy dữ liệu: omp không khả dụng trên PC");
            } else {
                errorMsg = L10n.t("Error: ", "Lỗi: ") + snapshot.error;
            }
            if (snapshot.providers != null && !snapshot.providers.isEmpty()) {
                errorBannerView.setText(errorMsg);
                errorBannerView.setVisibility(View.VISIBLE);
            } else {
                statusView.setText(errorMsg);
                statusView.setTextColor(Color.rgb(248, 81, 73));
                statusView.setVisibility(View.VISIBLE);
                cardsContainer.removeAllViews();
                return;
            }
        }

        if (snapshot.loading && (snapshot.providers == null || snapshot.providers.isEmpty())) {
            statusView.setText(L10n.t("Fetching...", "Đang lấy dữ liệu..."));
            statusView.setTextColor(Color.rgb(139, 148, 158));
            statusView.setVisibility(View.VISIBLE);
            progressBar.setVisibility(View.VISIBLE);
            cardsContainer.removeAllViews();
            return;
        }

        long nowMs = System.currentTimeMillis();
        List<QuotaViewModel.ProviderCard> cards = QuotaViewModel.build(snapshot, nowMs);

        if (!QuotaViewModel.hasData(cards)) {
            statusView.setText(L10n.t("No quota data yet", "Chưa có dữ liệu quota"));
            statusView.setTextColor(Color.rgb(139, 148, 158));
            statusView.setVisibility(View.VISIBLE);
            cardsContainer.removeAllViews();
            return;
        }

        statusView.setVisibility(View.GONE);
        renderCards(cards);
    }

    private void renderCards(List<QuotaViewModel.ProviderCard> cards) {
        cardsContainer.removeAllViews();
        Context context = cardsContainer.getContext();

        for (final QuotaViewModel.ProviderCard card : cards) {
            final String cardKey = (card.id != null && !card.id.isEmpty()) ? card.id : card.name;

            LinearLayout cardLayout = new LinearLayout(context);
            cardLayout.setOrientation(LinearLayout.VERTICAL);
            UiKit.styleCard(cardLayout);
            cardLayout.setPadding(dpToPx(context, 16), dpToPx(context, 14), dpToPx(context, 16), dpToPx(context, 14));
            cardLayout.setClickable(true);

            LinearLayout cardHeader = new LinearLayout(context);
            cardHeader.setOrientation(LinearLayout.HORIZONTAL);
            cardHeader.setGravity(Gravity.CENTER_VERTICAL);

            TextView nameView = new TextView(context);
            nameView.setText(card.name);
            nameView.setTextSize(17);
            nameView.setTypeface(Typeface.DEFAULT_BOLD);
            nameView.setTextColor(Color.rgb(230, 237, 243));
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            nameView.setLayoutParams(nameLp);
            cardHeader.addView(nameView);

            final TextView indicatorView = new TextView(context);
            indicatorView.setTextSize(12);
            indicatorView.setTextColor(Color.rgb(88, 166, 255));
            cardHeader.addView(indicatorView);
            cardLayout.addView(cardHeader);

            LinearLayout summaryContainer = new LinearLayout(context);
            summaryContainer.setOrientation(LinearLayout.VERTICAL);
            summaryContainer.setPadding(0, dpToPx(context, 8), 0, 0);

            for (QuotaViewModel.SummaryRow row : card.summary) {
                if (row.group != null && !row.group.isEmpty() && card.summary.size() > 1) {
                    TextView groupTitle = new TextView(context);
                    groupTitle.setText(row.group);
                    groupTitle.setTextSize(13);
                    groupTitle.setTypeface(Typeface.DEFAULT_BOLD);
                    groupTitle.setTextColor(Color.rgb(201, 209, 217));
                    groupTitle.setPadding(0, dpToPx(context, 6), 0, dpToPx(context, 2));
                    summaryContainer.addView(groupTitle);
                }
                if (row.fiveHour != null || row.weekly != null) {
                    if (row.fiveHour != null) {
                        summaryContainer.addView(createBarView(context, row.fiveHour, false));
                    }
                    if (row.weekly != null) {
                        summaryContainer.addView(createBarView(context, row.weekly, false));
                    }
                } else if (row.longWindow != null) {
                    summaryContainer.addView(createBarView(context, row.longWindow, false));
                }
            }
            cardLayout.addView(summaryContainer);

            final LinearLayout detailsContainer = new LinearLayout(context);
            detailsContainer.setOrientation(LinearLayout.VERTICAL);
            detailsContainer.setPadding(0, dpToPx(context, 12), 0, 0);

            View divider = new View(context);
            divider.setBackgroundColor(Color.rgb(48, 54, 61));
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
            divLp.setMargins(0, 0, 0, dpToPx(context, 10));
            divider.setLayoutParams(divLp);
            detailsContainer.addView(divider);

            TextView detailsHeader = new TextView(context);
            detailsHeader.setText(L10n.t("Windows & accounts", "Chi tiết các cửa sổ & tài khoản"));
            detailsHeader.setTextSize(13);
            detailsHeader.setTypeface(Typeface.DEFAULT_BOLD);
            detailsHeader.setTextColor(Color.rgb(139, 148, 158));
            detailsHeader.setPadding(0, 0, 0, dpToPx(context, 8));
            detailsContainer.addView(detailsHeader);

            for (QuotaViewModel.DetailBlock block : card.details) {
                if (block.group != null && !block.group.isEmpty() && card.details.size() > 1) {
                    TextView blockTitle = new TextView(context);
                    blockTitle.setText(L10n.t("Group: ", "Nhóm: ") + block.group);
                    blockTitle.setTextSize(13);
                    blockTitle.setTypeface(Typeface.DEFAULT_BOLD);
                    blockTitle.setTextColor(Color.rgb(201, 209, 217));
                    blockTitle.setPadding(0, dpToPx(context, 6), 0, dpToPx(context, 2));
                    detailsContainer.addView(blockTitle);
                }
                for (QuotaViewModel.WindowDetail win : block.windows) {
                    String winLabel = (win.label != null && !win.label.isEmpty()) ? win.label : L10n.t("Window", "Cửa sổ");
                    QuotaViewModel.Bar pooledBar = new QuotaViewModel.Bar(win.bar.pct, win.bar.tier, win.bar.resetText, winLabel + L10n.t(" (pooled)", " (gộp)"));
                    detailsContainer.addView(createBarView(context, pooledBar, false));

                    if (win.accounts != null && !win.accounts.isEmpty()) {
                        int fullCount = 0;
                        for (QuotaViewModel.AccountBar acc : win.accounts) {
                            if (acc.bar.pct >= 100) {
                                fullCount++;
                                continue;
                            }
                            LinearLayout accLayout = new LinearLayout(context);
                            accLayout.setOrientation(LinearLayout.VERTICAL);
                            accLayout.setPadding(dpToPx(context, 16), 0, 0, 0);
                            QuotaViewModel.Bar accBar = new QuotaViewModel.Bar(acc.bar.pct, acc.bar.tier, acc.bar.resetText, acc.label);
                            accLayout.addView(createBarView(context, accBar, true));
                            detailsContainer.addView(accLayout);
                        }
                        if (fullCount > 0) {
                            TextView fullView = new TextView(context);
                            fullView.setText(fullCount + L10n.t(" accounts still at 100%", " tài khoản còn đầy 100%"));
                            fullView.setTextSize(11);
                            fullView.setTextColor(Color.rgb(139, 148, 158));
                            fullView.setPadding(dpToPx(context, 16), dpToPx(context, 2), 0, dpToPx(context, 8));
                            detailsContainer.addView(fullView);
                        }
                    }
                }
            }
            cardLayout.addView(detailsContainer);

            boolean expanded = expandedCardIds.contains(cardKey);
            detailsContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
            indicatorView.setText(expanded ? L10n.t("Less ▲", "Thu gọn ▲") : L10n.t("Details ▼", "Chi tiết ▼"));

            Tap.wire(cardLayout, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean wasExpanded = detailsContainer.getVisibility() == View.VISIBLE;
                    if (wasExpanded) {
                        detailsContainer.setVisibility(View.GONE);
                        indicatorView.setText(L10n.t("Details ▼", "Chi tiết ▼"));
                        expandedCardIds.remove(cardKey);
                    } else {
                        detailsContainer.setVisibility(View.VISIBLE);
                        indicatorView.setText(L10n.t("Less ▲", "Thu gọn ▲"));
                        expandedCardIds.add(cardKey);
                    }
                }
            });

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(0, 0, 0, dpToPx(context, 16));
            cardLayout.setLayoutParams(cardLp);

            cardsContainer.addView(cardLayout);
        }
    }

    private View createBarView(Context context, QuotaViewModel.Bar bar, boolean small) {
        LinearLayout barLayout = new LinearLayout(context);
        barLayout.setOrientation(LinearLayout.VERTICAL);
        barLayout.setPadding(0, dpToPx(context, small ? 2 : 4), 0, dpToPx(context, small ? 4 : 8));

        LinearLayout textRow = new LinearLayout(context);
        textRow.setOrientation(LinearLayout.HORIZONTAL);
        textRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView labelView = new TextView(context);
        labelView.setText(bar.label != null ? bar.label : "");
        labelView.setTextSize(small ? 11 : 13);
        labelView.setTextColor(small ? Color.rgb(139, 148, 158) : Color.rgb(230, 237, 243));
        if (!small) {
            labelView.setTypeface(Typeface.DEFAULT_BOLD);
        }
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        labelView.setLayoutParams(labelLp);
        textRow.addView(labelView);

        TextView pctView = new TextView(context);
        pctView.setText(small ? bar.pct + "%" : L10n.t(bar.pct + "% left", "còn " + bar.pct + "%"));
        pctView.setTextSize(small ? 11 : 13);
        pctView.setTypeface(Typeface.DEFAULT_BOLD);
        pctView.setTextColor(getTierColor(bar.tier));
        textRow.addView(pctView);

        barLayout.addView(textRow);

        LinearLayout track = new LinearLayout(context);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackgroundColor(Color.rgb(33, 38, 45));
        track.setWeightSum(100.0f);

        int trackHeight = dpToPx(context, small ? 4 : 8);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, trackHeight);
        trackLp.setMargins(0, dpToPx(context, 4), 0, dpToPx(context, 2));
        track.setLayoutParams(trackLp);

        int clampedPct = Math.max(0, Math.min(100, bar.pct));

        if (clampedPct > 0) {
            View fill = new View(context);
            fill.setBackgroundColor(getTierColor(bar.tier));
            LinearLayout.LayoutParams fillLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, (float) clampedPct);
            fill.setLayoutParams(fillLp);
            track.addView(fill);
        }

        if (clampedPct < 100) {
            View empty = new View(context);
            empty.setBackgroundColor(Color.TRANSPARENT);
            LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, (float) (100 - clampedPct));
            empty.setLayoutParams(emptyLp);
            track.addView(empty);
        }

        barLayout.addView(track);

        if (bar.resetText != null && !bar.resetText.isEmpty()) {
            TextView resetView = new TextView(context);
            resetView.setText(bar.resetText);
            resetView.setTextSize(small ? 10 : 11);
            resetView.setTextColor(Color.rgb(139, 148, 158));
            barLayout.addView(resetView);
        }

        return barLayout;
    }

    private static boolean isSameSnapshot(QuotaSnapshot a, QuotaSnapshot b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.fetchedAt != b.fetchedAt || a.stale != b.stale || a.loading != b.loading) return false;
        if (a.error == null ? b.error != null : !a.error.equals(b.error)) return false;
        if (a.providers.size() != b.providers.size()) return false;
        for (int i = 0; i < a.providers.size(); i++) {
            QuotaSnapshot.Provider pa = a.providers.get(i);
            QuotaSnapshot.Provider pb = b.providers.get(i);
            if (!pa.id.equals(pb.id) || !pa.name.equals(pb.name)) return false;
            if (pa.groups.size() != pb.groups.size()) return false;
            for (int j = 0; j < pa.groups.size(); j++) {
                QuotaSnapshot.Group ga = pa.groups.get(j);
                QuotaSnapshot.Group gb = pb.groups.get(j);
                if (!ga.name.equals(gb.name)) return false;
                if (ga.windows.size() != gb.windows.size()) return false;
                for (int k = 0; k < ga.windows.size(); k++) {
                    QuotaSnapshot.Window wa = ga.windows.get(k);
                    QuotaSnapshot.Window wb = gb.windows.get(k);
                    if (!wa.id.equals(wb.id) || wa.pooledRemainingPct != wb.pooledRemainingPct) return false;
                    if (wa.accountsWithQuota != wb.accountsWithQuota || wa.accountsTotal != wb.accountsTotal) return false;
                    if (wa.resetsAt == null ? wb.resetsAt != null : !wa.resetsAt.equals(wb.resetsAt)) return false;
                    if (wa.accounts.size() != wb.accounts.size()) return false;
                    for (int m = 0; m < wa.accounts.size(); m++) {
                        QuotaSnapshot.Account aa = wa.accounts.get(m);
                        QuotaSnapshot.Account ab = wb.accounts.get(m);
                        if (!aa.label.equals(ab.label) || aa.remainingPct != ab.remainingPct) return false;
                        if (aa.resetsAt == null ? ab.resetsAt != null : !aa.resetsAt.equals(ab.resetsAt)) return false;
                    }
                }
            }
        }
        return true;
    }

    public static int getTierColor(QuotaFormatter.Tier tier) {
        if (tier == QuotaFormatter.Tier.HIGH) {
            return Color.rgb(63, 185, 80);
        } else if (tier == QuotaFormatter.Tier.MID) {
            return Color.rgb(240, 136, 62);
        } else {
            return Color.rgb(248, 81, 73);
        }
    }

    private static int dpToPx(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
