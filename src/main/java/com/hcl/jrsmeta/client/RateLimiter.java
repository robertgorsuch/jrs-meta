package com.hcl.jrsmeta.client;

import java.util.concurrent.TimeUnit;

/** Minimal fixed-interval limiter; 0 permits/second disables limiting. */
public final class RateLimiter {
    private final long intervalNanos;
    private long nextAllowed;

    public RateLimiter(double permitsPerSecond) {
        intervalNanos = permitsPerSecond <= 0 ? 0 : (long) (1_000_000_000L / permitsPerSecond);
    }

    public synchronized void acquire() throws InterruptedException {
        if (intervalNanos == 0) return;
        long now = System.nanoTime();
        if (now < nextAllowed) { TimeUnit.NANOSECONDS.sleep(nextAllowed - now); now = nextAllowed; }
        nextAllowed = now + intervalNanos;
    }
}
