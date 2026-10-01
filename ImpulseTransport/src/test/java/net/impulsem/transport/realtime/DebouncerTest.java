package net.impulsem.transport.realtime;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;


public class DebouncerTest {

    @Test
    public void burstCollapsesToOneRun() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        try {
            final AtomicInteger runs = new AtomicInteger();
            Debouncer debouncer = new Debouncer(executor, 100L, new Runnable() {
                @Override
                public void run() {
                    runs.incrementAndGet();
                }
            });
            for (int a = 0; a < 10; a++) {
                debouncer.trigger();
                Thread.sleep(10L);
            }
            assertEquals(0, runs.get());
            Thread.sleep(300L);
            assertEquals(1, runs.get());
            debouncer.trigger();
            Thread.sleep(300L);
            assertEquals(2, runs.get());
        } finally {
            executor.shutdownNow();
        }
    }


    @Test
    public void cancelDropsPendingRun() throws Exception {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        try {
            final AtomicInteger runs = new AtomicInteger();
            Debouncer debouncer = new Debouncer(executor, 50L, new Runnable() {
                @Override
                public void run() {
                    runs.incrementAndGet();
                }
            });
            debouncer.trigger();
            debouncer.cancel();
            Thread.sleep(200L);
            assertEquals(0, runs.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
