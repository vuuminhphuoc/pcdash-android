package vn.vuumi.pcdash.remote;

public class AtomicCancelFlag implements CancelFlag {

    private volatile boolean cancelled = false;

    public void cancel() {
        this.cancelled = true;
    }

    public void reset() {
        this.cancelled = false;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
