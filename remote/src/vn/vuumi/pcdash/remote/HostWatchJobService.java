package vn.vuumi.pcdash.remote;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Runs one {@link HostWatch} check on a worker thread; skipped while the app itself is on screen. */
public class HostWatchJobService extends JobService {
    private volatile Thread worker;

    @Override
    public boolean onStartJob(final JobParameters params) {
        if (!HostWatch.isEnabled(this) || RemoteSession.isActivityVisible()) {
            return false;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HostWatch.runCheck(getApplicationContext());
                } finally {
                    jobFinished(params, false);
                }
            }
        }, "pcdash-host-watch");
        worker = t;
        t.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
        // Periodic: the next run comes anyway.
        return false;
    }
}
