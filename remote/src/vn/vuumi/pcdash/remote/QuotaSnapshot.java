package vn.vuumi.pcdash.remote;

import java.util.Collections;
import java.util.List;

public final class QuotaSnapshot {

    public static final QuotaSnapshot EMPTY = new QuotaSnapshot(0L, false, false, null, Collections.<Provider>emptyList());

    public final long fetchedAt;
    public final boolean stale;
    public final boolean loading;
    public final String error;
    public final List<Provider> providers;

    public QuotaSnapshot(long fetchedAt, boolean stale, boolean loading, String error, List<Provider> providers) {
        this.fetchedAt = fetchedAt;
        this.stale = stale;
        this.loading = loading;
        this.error = error;
        this.providers = providers != null ? Collections.unmodifiableList(providers) : Collections.<Provider>emptyList();
    }

    public static final class Provider {
        public final String id;
        public final String name;
        public final List<Group> groups;

        public Provider(String id, String name, List<Group> groups) {
            this.id = id != null ? id : "";
            this.name = name != null ? name : "";
            this.groups = groups != null ? Collections.unmodifiableList(groups) : Collections.<Group>emptyList();
        }
    }

    public static final class Group {
        public final String name;
        public final List<Window> windows;

        public Group(String name, List<Window> windows) {
            this.name = name != null ? name : "";
            this.windows = windows != null ? Collections.unmodifiableList(windows) : Collections.<Window>emptyList();
        }
    }

    public static final class Window {
        public final String id;
        public final String label;
        public final int pooledRemainingPct;
        public final int accountsWithQuota;
        public final int accountsTotal;
        public final Long resetsAt;
        public final List<Account> accounts;

        public Window(String id, String label, int pooledRemainingPct, int accountsWithQuota, int accountsTotal,
                      Long resetsAt, List<Account> accounts) {
            this.id = id != null ? id : "";
            this.label = label != null ? label : "";
            this.pooledRemainingPct = pooledRemainingPct;
            this.accountsWithQuota = accountsWithQuota;
            this.accountsTotal = accountsTotal;
            this.resetsAt = resetsAt;
            this.accounts = accounts != null ? Collections.unmodifiableList(accounts) : Collections.<Account>emptyList();
        }
    }

    public static final class Account {
        public final String label;
        public final int remainingPct;
        public final Long resetsAt;

        public Account(String label, int remainingPct, Long resetsAt) {
            this.label = label != null ? label : "";
            this.remainingPct = remainingPct;
            this.resetsAt = resetsAt;
        }
    }
}
