package net.impulsem.transport.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;


public class RefreshScheduleTest {

    private static final long NowMillis = 1_700_000_000_000L;
    private static final long ExpSeconds = NowMillis / 1000 + 600;
    private static final int Draws = 10_000;
    private static final long ResolutionMillis = 1_000L;


    @Test
    public void everyDrawLandsInsideTheFiveToTwoMinuteWindow() {
        RefreshSchedule schedule = new RefreshSchedule(new Random(1L));
        long startMillis = (ExpSeconds - 300) * 1000L;
        long endMillis = (ExpSeconds - 120) * 1000L;
        for (int draw = 0; draw < Draws; draw++) {
            long point = schedule.drawRefreshPointMillis(ExpSeconds, NowMillis);
            assertTrue("draw " + draw + " at " + point, point >= startMillis && point <= endMillis);
        }
    }


    @Test
    public void drawsReachBothEndsOfTheWindow() {
        RefreshSchedule schedule = new RefreshSchedule(new Random(2L));
        long startMillis = (ExpSeconds - 300) * 1000L;
        long endMillis = (ExpSeconds - 120) * 1000L;
        long lowest = Long.MAX_VALUE;
        long highest = Long.MIN_VALUE;
        for (int draw = 0; draw < Draws; draw++) {
            long point = schedule.drawRefreshPointMillis(ExpSeconds, NowMillis);
            lowest = Math.min(lowest, point);
            highest = Math.max(highest, point);
        }
        assertTrue("lowest draw " + lowest, lowest - startMillis < ResolutionMillis);
        assertTrue("highest draw " + highest, endMillis - highest < ResolutionMillis);
    }


    @Test
    public void shortTokenWithLessThanTwoMinutesOfWindowRefreshesAtOnce() {
        RefreshSchedule schedule = new RefreshSchedule(new Random(3L));
        long expSeconds = NowMillis / 1000 + 100;
        assertEquals(NowMillis, schedule.drawRefreshPointMillis(expSeconds, NowMillis));
    }


    @Test
    public void shortTokenWindowStartsAtStorageTime() {
        RefreshSchedule schedule = new RefreshSchedule(new Random(4L));
        long expSeconds = NowMillis / 1000 + 200;
        long endMillis = (expSeconds - 120) * 1000L;
        for (int draw = 0; draw < Draws; draw++) {
            long point = schedule.drawRefreshPointMillis(expSeconds, NowMillis);
            assertTrue("draw " + draw + " at " + point, point >= NowMillis && point <= endMillis);
        }
    }
}
