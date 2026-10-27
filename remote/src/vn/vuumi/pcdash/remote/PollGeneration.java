package vn.vuumi.pcdash.remote;

public class PollGeneration {
    private int currentGen = 0;

    public synchronized int next() {
        return ++currentGen;
    }

    public synchronized int current() {
        return currentGen;
    }

    public synchronized boolean isValid(int generation) {
        return generation == currentGen;
    }

    public synchronized void invalidate() {
        currentGen++;
    }
}
