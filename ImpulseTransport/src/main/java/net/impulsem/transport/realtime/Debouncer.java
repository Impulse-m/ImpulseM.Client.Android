package net.impulsem.transport.realtime;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


/** Runs a task once, a fixed delay after the last trigger. */
public final class Debouncer {

    private final ScheduledExecutorService executor;
    private final long delayMillis;
    private final Runnable task;
    private ScheduledFuture<?> pending;


    public Debouncer(
        ScheduledExecutorService executor,
        long delayMillis,
        Runnable task
    ) {
        this.executor = executor;
        this.delayMillis = delayMillis;
        this.task = task;
    }


    public synchronized void trigger() {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
    }


    public synchronized void cancel() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }
}
