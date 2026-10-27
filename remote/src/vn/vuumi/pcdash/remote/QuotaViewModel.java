package vn.vuumi.pcdash.remote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class QuotaViewModel {

    private QuotaViewModel() {}

    public static List<ProviderCard> build(QuotaSnapshot snapshot, long nowMs) {
        if (snapshot == null || snapshot.providers == null || snapshot.providers.isEmpty()) {
            return Collections.emptyList();
        }

        List<ProviderCard> cards = new ArrayList<ProviderCard>(snapshot.providers.size());
        for (QuotaSnapshot.Provider p : snapshot.providers) {
            String name = (p.name != null && !p.name.isEmpty()) ? p.name : p.id;

            List<SummaryRow> summary = new ArrayList<SummaryRow>();
            List<DetailBlock> details = new ArrayList<DetailBlock>();

            if (p.groups != null) {
                for (QuotaSnapshot.Group g : p.groups) {
                    String groupName = (g.name != null && !g.name.isEmpty()) ? g.name : "";

                    QuotaSnapshot.Window w5h = null;
                    QuotaSnapshot.Window wWeekly = null;
                    QuotaSnapshot.Window wLong = null;

                    if (g.windows != null) {
                        for (QuotaSnapshot.Window w : g.windows) {
                            if (w == null) continue;
                            String wid = w.id != null ? w.id.trim() : "";
                            if ("5h".equalsIgnoreCase(wid)) {
                                if (w5h == null) w5h = w;
                            } else if ("weekly".equalsIgnoreCase(wid) || "1w".equalsIgnoreCase(wid)) {
                                if (wWeekly == null) wWeekly = w;
                            } else {
                                if (wLong == null) wLong = w;
                            }
                        }
                    }

                    Bar fiveHourBar = null;
                    Bar weeklyBar = null;
                    Bar longBar = null;

                    if (w5h != null || wWeekly != null) {
                        if (w5h != null) {
                            fiveHourBar = new Bar(w5h.pooledRemainingPct, QuotaFormatter.tier(w5h.pooledRemainingPct),
                                    QuotaFormatter.resetText(w5h.resetsAt, nowMs), w5h.label);
                        }
                        if (wWeekly != null) {
                            weeklyBar = new Bar(wWeekly.pooledRemainingPct, QuotaFormatter.tier(wWeekly.pooledRemainingPct),
                                    QuotaFormatter.resetText(wWeekly.resetsAt, nowMs), wWeekly.label);
                        }
                    } else if (wLong != null) {
                        longBar = new Bar(wLong.pooledRemainingPct, QuotaFormatter.tier(wLong.pooledRemainingPct),
                                QuotaFormatter.resetText(wLong.resetsAt, nowMs), wLong.label);
                    }

                    summary.add(new SummaryRow(groupName, fiveHourBar, weeklyBar, longBar));

                    List<WindowDetail> windowDetails = new ArrayList<WindowDetail>();
                    if (g.windows != null) {
                        for (QuotaSnapshot.Window w : g.windows) {
                            if (w == null) continue;
                            String wLabel = (w.label != null && !w.label.isEmpty()) ? w.label : w.id;
                            Bar wBar = new Bar(w.pooledRemainingPct, QuotaFormatter.tier(w.pooledRemainingPct),
                                    QuotaFormatter.resetText(w.resetsAt, nowMs), wLabel);

                            List<AccountBar> accountBars = new ArrayList<AccountBar>();
                            if (w.accounts != null) {
                                for (QuotaSnapshot.Account a : w.accounts) {
                                    if (a == null) continue;
                                    Bar aBar = new Bar(a.remainingPct, QuotaFormatter.tier(a.remainingPct),
                                            QuotaFormatter.resetText(a.resetsAt, nowMs), a.label);
                                    accountBars.add(new AccountBar(a.label, aBar));
                                }
                            }
                            windowDetails.add(new WindowDetail(wLabel, wBar, accountBars));
                        }
                    }
                    details.add(new DetailBlock(groupName, windowDetails));
                }
            }

            cards.add(new ProviderCard(p.id, name, summary, details));
        }

        return Collections.unmodifiableList(cards);
    }

    public static boolean hasData(List<ProviderCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return false;
        }
        for (ProviderCard card : cards) {
            if (!card.summary.isEmpty() || !card.details.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static final class ProviderCard {
        public final String id;
        public final String name;
        public final List<SummaryRow> summary;
        public final List<DetailBlock> details;

        public ProviderCard(String id, String name, List<SummaryRow> summary, List<DetailBlock> details) {
            this.id = id != null ? id : "";
            this.name = name != null ? name : "";
            this.summary = summary != null ? Collections.unmodifiableList(summary) : Collections.<SummaryRow>emptyList();
            this.details = details != null ? Collections.unmodifiableList(details) : Collections.<DetailBlock>emptyList();
        }

        public ProviderCard(String name, List<SummaryRow> summary, List<DetailBlock> details) {
            this(name, name, summary, details);
        }
    }

    public static final class SummaryRow {
        public final String group;
        public final Bar fiveHour;
        public final Bar weekly;
        public final Bar longWindow;

        public SummaryRow(String group, Bar fiveHour, Bar weekly, Bar longWindow) {
            this.group = group != null ? group : "";
            this.fiveHour = fiveHour;
            this.weekly = weekly;
            this.longWindow = longWindow;
        }
    }

    public static final class Bar {
        public final int pct;
        public final QuotaFormatter.Tier tier;
        public final String resetText;
        public final String label;

        public Bar(int pct, QuotaFormatter.Tier tier, String resetText, String label) {
            this.pct = pct;
            this.tier = tier;
            this.resetText = resetText != null ? resetText : "";
            this.label = label != null ? label : "";
        }

        public Bar(int pct, QuotaFormatter.Tier tier, String resetText) {
            this(pct, tier, resetText, "");
        }
    }

    public static final class DetailBlock {
        public final String group;
        public final List<WindowDetail> windows;

        public DetailBlock(String group, List<WindowDetail> windows) {
            this.group = group != null ? group : "";
            this.windows = windows != null ? Collections.unmodifiableList(windows) : Collections.<WindowDetail>emptyList();
        }
    }

    public static final class WindowDetail {
        public final String label;
        public final Bar bar;
        public final List<AccountBar> accounts;

        public WindowDetail(String label, Bar bar, List<AccountBar> accounts) {
            this.label = label != null ? label : "";
            this.bar = bar;
            this.accounts = accounts != null ? Collections.unmodifiableList(accounts) : Collections.<AccountBar>emptyList();
        }
    }

    public static final class AccountBar {
        public final String label;
        public final Bar bar;

        public AccountBar(String label, Bar bar) {
            this.label = label != null ? label : "";
            this.bar = bar;
        }
    }
}
