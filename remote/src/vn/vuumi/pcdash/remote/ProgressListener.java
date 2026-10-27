package vn.vuumi.pcdash.remote;

public interface ProgressListener {
    void onProgress(long bytesRead, long totalBytes);
}
