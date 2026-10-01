package net.impulsem.transport.realtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.Test;


public class LaneSelectorTest {

    private static List<LaneSelector.Candidate> candidates(long... idsAndDates) {
        List<LaneSelector.Candidate> list = new ArrayList<LaneSelector.Candidate>();
        for (int a = 0; a < idsAndDates.length; a += 2) {
            list.add(new LaneSelector.Candidate(idsAndDates[a], (int) idsAndDates[a + 1]));
        }
        return list;
    }


    @Test
    public void picksNewestFirstUpToCap() {
        Set<Long> chosen = LaneSelector.select(candidates(1, 10, 2, 30, 3, 20, 4, 5), 0L, 2);
        assertEquals(Arrays.asList(2L, 3L), new ArrayList<Long>(chosen));
    }


    @Test
    public void openedChannelIsAlwaysIncludedAndCountsAgainstCap() {
        Set<Long> chosen = LaneSelector.select(candidates(1, 10, 2, 30, 3, 20), 1L, 2);
        assertEquals(2, chosen.size());
        assertTrue(chosen.contains(1L));
        assertTrue(chosen.contains(2L));
        assertFalse(chosen.contains(3L));
    }


    @Test
    public void openedChannelOutsideTheDialogListIsStillIncluded() {
        Set<Long> chosen = LaneSelector.select(candidates(2, 30), 9L, 32);
        assertEquals(Arrays.asList(9L, 2L), new ArrayList<Long>(chosen));
    }


    @Test
    public void openedChannelIsNotDuplicated() {
        Set<Long> chosen = LaneSelector.select(candidates(2, 30, 3, 20), 2L, 32);
        assertEquals(Arrays.asList(2L, 3L), new ArrayList<Long>(chosen));
    }


    @Test
    public void defaultCapIs32() {
        long[] data = new long[100 * 2];
        for (int a = 0; a < 100; a++) {
            data[a * 2] = a + 1;
            data[a * 2 + 1] = 1000 + a;
        }
        Set<Long> chosen = LaneSelector.select(candidates(data), 500L, LaneSelector.MaxLanes);
        assertEquals(32, chosen.size());
        assertTrue(chosen.contains(500L));
    }


    @Test
    public void nonPositiveIdsAreIgnored() {
        Set<Long> chosen = LaneSelector.select(candidates(0, 50, -5, 40, 7, 1), 0L, 32);
        assertEquals(Arrays.asList(7L), new ArrayList<Long>(chosen));
    }


    @Test
    public void emptyInputGivesEmptySet() {
        assertTrue(LaneSelector.select(new ArrayList<LaneSelector.Candidate>(), 0L, 32).isEmpty());
    }
}
