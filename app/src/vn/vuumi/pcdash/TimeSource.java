package vn.vuumi.pcdash;

public interface TimeSource {
    long nowEpochMs();

    TimeSource SYSTEM = new TimeSource() {
        @Override
        public long nowEpochMs() {
            return System.currentTimeMillis();
        }
    };
}
