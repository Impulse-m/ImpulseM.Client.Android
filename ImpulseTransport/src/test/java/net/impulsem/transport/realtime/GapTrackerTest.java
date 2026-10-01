package net.impulsem.transport.realtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;


public class GapTrackerTest {

    @Test
    public void userLaneAlwaysNeedsDifference() {
        GapTracker tracker = new GapTracker();
        assertTrue(tracker.onSubscribed("user:5", false, false));
        assertTrue(tracker.onSubscribed("user:5", true, true));
    }


    @Test
    public void firstChannelSubscribeIsNoGap() {
        GapTracker tracker = new GapTracker();
        assertFalse(tracker.onSubscribed("channel:9", false, false));
    }


    @Test
    public void recoveredChannelResubscribeIsNoGap() {
        GapTracker tracker = new GapTracker();
        assertFalse(tracker.onSubscribed("channel:9", true, true));
    }


    @Test
    public void failedRecoveryIsAGap() {
        GapTracker tracker = new GapTracker();
        assertTrue(tracker.onSubscribed("channel:9", false, true));
    }


    @Test
    public void code2502ThenSubscribeIsAGapOnce() {
        GapTracker tracker = new GapTracker();
        tracker.onUnsubscribed("channel:9", 2502);
        assertTrue(tracker.onSubscribed("channel:9", false, false));
        assertFalse(tracker.onSubscribed("channel:9", false, false));
    }


    @Test
    public void otherTemporaryCodesAreNotAGap() {
        GapTracker tracker = new GapTracker();
        tracker.onUnsubscribed("channel:9", 2500);
        assertFalse(tracker.onSubscribed("channel:9", true, true));
    }


    @Test
    public void terminalUnsubscribeForgetsTheMark() {
        GapTracker tracker = new GapTracker();
        tracker.onUnsubscribed("channel:9", 2502);
        tracker.onUnsubscribed("channel:9", 2000);
        assertFalse(tracker.onSubscribed("channel:9", false, false));
    }


    @Test
    public void markIsPerChannel() {
        GapTracker tracker = new GapTracker();
        tracker.onUnsubscribed("channel:9", 2502);
        assertFalse(tracker.onSubscribed("channel:10", false, false));
        assertTrue(tracker.onSubscribed("channel:9", false, false));
    }
}
