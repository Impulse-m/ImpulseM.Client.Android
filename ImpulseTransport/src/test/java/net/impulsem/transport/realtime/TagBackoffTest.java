package net.impulsem.transport.realtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;


public class TagBackoffTest {

    private static final long Minute = 60L * 1000L;


    @Test
    public void unknownChannelIsDue() {
        assertTrue(new TagBackoff().isDue(1L, 0L));
    }


    @Test
    public void delaysDoubleFromOneMinuteAndCapAtThirtyMinutes() {
        TagBackoff backoff = new TagBackoff();
        long now = 1000L;
        long[] expected = {1, 2, 4, 8, 16, 30, 30};
        for (int a = 0; a < expected.length; a++) {
            backoff.failed(7L, now);
            long delay = expected[a] * Minute;
            assertFalse(backoff.isDue(7L, now + delay - 1L));
            assertTrue(backoff.isDue(7L, now + delay));
            now += delay;
        }
    }


    @Test
    public void leavingTheDesiredSetResetsTheSteps() {
        TagBackoff backoff = new TagBackoff();
        backoff.failed(7L, 0L);
        backoff.failed(7L, Minute);
        backoff.retainOnly(new HashSet<Long>(Arrays.asList(8L)));
        assertTrue(backoff.isDue(7L, 0L));
        backoff.failed(7L, 0L);
        assertTrue(backoff.isDue(7L, Minute));
    }


    @Test
    public void retainKeepsDesiredEntries() {
        TagBackoff backoff = new TagBackoff();
        backoff.failed(7L, 0L);
        backoff.retainOnly(new HashSet<Long>(Arrays.asList(7L)));
        assertFalse(backoff.isDue(7L, 1L));
    }


    @Test
    public void successClearsTheEntry() {
        TagBackoff backoff = new TagBackoff();
        backoff.failed(7L, 0L);
        backoff.succeeded(7L);
        assertTrue(backoff.isDue(7L, 1L));
    }


    @Test
    public void nextDueIsTheShortestWait() {
        TagBackoff backoff = new TagBackoff();
        backoff.failed(1L, 0L);
        backoff.failed(2L, 0L);
        backoff.failed(2L, Minute);
        Set<Long> ids = new HashSet<Long>(Arrays.asList(1L, 2L, 3L));
        assertEquals(Minute, backoff.nextDueInMillis(ids, 0L));
        assertEquals(-1L, backoff.nextDueInMillis(ids, 10L * Minute));
        assertEquals(-1L, new TagBackoff().nextDueInMillis(ids, 0L));
    }
}
