package net.impulsem.transport.realtime;

import java.util.Random;


/** Exponential reconnect delay with symmetric jitter. Not thread-safe; the client calls it under its lock. */
public final class Backoff {

    private final long initialMillis;
    private final long maxMillis;
    private final double jitter;
    private final Random random;
    private int attempt;


    public Backoff() {
        this(1000L, 30000L, 0.2, new Random());
    }


    public Backoff(
        long initialMillis,
        long maxMillis,
        double jitter,
        Random random
    ) {
        this.initialMillis = initialMillis;
        this.maxMillis = maxMillis;
        this.jitter = jitter;
        this.random = random;
    }


    public long nextDelayMillis() {
        long base = initialMillis;
        for (int i = 0; i < attempt && base < maxMillis; i++) {
            base *= 2;
        }
        base = Math.min(base, maxMillis);
        if (attempt < 62) {
            attempt++;
        }
        double factor = 1.0 + jitter * (random.nextDouble() * 2.0 - 1.0);
        long delay = Math.round(base * factor);
        return Math.max(0L, Math.min(delay, maxMillis));
    }


    public void reset() {
        attempt = 0;
    }
}
