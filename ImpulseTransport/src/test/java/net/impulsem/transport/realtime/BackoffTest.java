package net.impulsem.transport.realtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;


public class BackoffTest {

    @Test
    public void doublesFromOneSecondWithJitterAndCapsAtThirty() {
        Backoff backoff = new Backoff(1000L, 30000L, 0.2, new Random(7L));
        long base = 1000L;
        for (int attempt = 0; attempt < 10; attempt++) {
            long delay = backoff.nextDelayMillis();
            long expected = Math.min(base, 30000L);
            assertTrue("attempt " + attempt + " delay " + delay, delay >= (long) (expected * 0.8) - 1);
            assertTrue("attempt " + attempt + " delay " + delay, delay <= 30000L);
            assertTrue("attempt " + attempt + " delay " + delay, delay <= (long) (expected * 1.2) + 1);
            base *= 2;
        }
    }


    @Test
    public void resetStartsOver() {
        Backoff backoff = new Backoff(1000L, 30000L, 0.0, new Random(1L));
        assertEquals(1000L, backoff.nextDelayMillis());
        assertEquals(2000L, backoff.nextDelayMillis());
        assertEquals(4000L, backoff.nextDelayMillis());
        backoff.reset();
        assertEquals(1000L, backoff.nextDelayMillis());
    }
}
