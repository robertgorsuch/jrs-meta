package com.hcl.jrsmeta.client;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {
    @Test void acquire_spacesCallsByInterval() throws Exception {
        RateLimiter l = new RateLimiter(50);           // 20 ms apart
        long t0 = System.nanoTime();
        for (int i = 0; i < 4; i++) l.acquire();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).isGreaterThanOrEqualTo(55);     // 3 gaps * 20ms, tolerant
    }
    @Test void zeroRate_meansUnlimited() throws Exception {
        RateLimiter l = new RateLimiter(0);
        long t0 = System.nanoTime();
        for (int i = 0; i < 100; i++) l.acquire();
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(50);
    }
}
