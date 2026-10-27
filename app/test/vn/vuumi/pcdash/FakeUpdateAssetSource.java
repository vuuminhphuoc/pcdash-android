package vn.vuumi.pcdash;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class FakeUpdateAssetSource implements UpdateAssetSource {
    private String infoJson;
    private long apkSize;
    private boolean pauseOnRead = false;
    private CountDownLatch readStartedLatch = new CountDownLatch(1);
    private CountDownLatch pauseLatch = new CountDownLatch(1);
    private final AtomicBoolean streamClosed = new AtomicBoolean(false);

    public FakeUpdateAssetSource(String infoJson, long apkSize) {
        this.infoJson = infoJson;
        this.apkSize = apkSize;
    }

    public void setInfoJson(String infoJson) {
        this.infoJson = infoJson;
    }

    public void setApkSize(long apkSize) {
        this.apkSize = apkSize;
    }

    public void enablePauseOnRead() {
        this.pauseOnRead = true;
        this.readStartedLatch = new CountDownLatch(1);
        this.pauseLatch = new CountDownLatch(1);
    }

    public boolean waitForReadStarted(long timeoutMs) throws InterruptedException {
        return readStartedLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public void resumeRead() {
        pauseLatch.countDown();
    }

    public boolean isStreamClosed() {
        return streamClosed.get();
    }

    @Override
    public InputStream openInfo() throws IOException {
        if (infoJson == null) {
            return null;
        }
        return new ByteArrayInputStream(infoJson.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public InputStream openApk() throws IOException {
        if (apkSize <= 0) {
            return null;
        }
        streamClosed.set(false);
        return new PatternInputStream(apkSize, pauseOnRead, readStartedLatch, pauseLatch, streamClosed);
    }

    @Override
    public long apkSize() {
        return apkSize;
    }

    public static class PatternInputStream extends InputStream {
        private final long total;
        private final boolean pauseOnRead;
        private final CountDownLatch readStartedLatch;
        private final CountDownLatch pauseLatch;
        private final AtomicBoolean streamClosed;
        private long pos = 0;
        private boolean signaled = false;

        public PatternInputStream(long total, boolean pauseOnRead,
                                  CountDownLatch readStartedLatch, CountDownLatch pauseLatch,
                                  AtomicBoolean streamClosed) {
            this.total = total;
            this.pauseOnRead = pauseOnRead;
            this.readStartedLatch = readStartedLatch;
            this.pauseLatch = pauseLatch;
            this.streamClosed = streamClosed;
        }

        @Override
        public int read() throws IOException {
            if (pos >= total) return -1;
            checkPause();
            return (int) ((pos++) & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos >= total) return -1;
            checkPause();
            int count = (int) Math.min(len, total - pos);
            for (int i = 0; i < count; i++) {
                b[off + i] = (byte) ((pos + i) & 0xFF);
            }
            pos += count;
            return count;
        }

        private void checkPause() throws IOException {
            if (pauseOnRead && !signaled) {
                signaled = true;
                readStartedLatch.countDown();
                try {
                    if (!pauseLatch.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("FakeUpdateAssetSource pause timeout");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during fake stream pause", e);
                }
            }
        }

        @Override
        public void close() throws IOException {
            streamClosed.set(true);
            super.close();
        }
    }
}
