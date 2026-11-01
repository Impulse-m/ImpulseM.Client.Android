package net.impulsem.transport.realtime;

import static net.impulsem.transport.realtime.GapTracker.ReasonNotRecovered;
import static net.impulsem.transport.realtime.GapTracker.ReasonPositionDropped;
import static net.impulsem.transport.realtime.GapTracker.ReasonSocketReconnected;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;


public class GapTrackerTest {

    @Test
    public void recoveredUserLaneWithoutDropNeedsNoDifference() {
        GapTracker tracker = new GapTracker();
        assertNull(tracker.onSubscribed("user:5", true, true));
    }


    @Test
    public void freshUserLaneSubscribeNeedsDifference() {
        GapTracker tracker = new GapTracker();
        assertEquals(ReasonNotRecovered, tracker.onSubscribed("user:5", false, false));
        assertEquals(ReasonNotRecovered, tracker.onSubscribed("user:5", false, true));
    }


    @Test
    public void droppedUserLanePositionNeedsDifferenceEvenIfRecovered() {
        GapTracker tracker = new GapTracker();
        tracker.onUnsubscribed("user:5", 2502);
        assertEquals(ReasonPositionDropped, tracker.onSubscribed("user:5", true, true));
    }


    @Test
    public void firstChannelSubscribeIsNoGap() {
        GapTracker tracker = new GapTracker();
        assertNull(tracker.onSubscribed("channel:9", false, false));
    }


    @Test
    public void recoveredChannelResubscribeIsNoGap() {
        GapTracker tracker = new GapTracker();
        assertNull(tracker.onSubscribed("channel:9", true, true));
    }


    @Test
    public void failedRecoveryIsAGap() {
        GapTracker tracker = new GapTracker();
        assertEquals(ReasonNotRecovered, tracker.onSubscribed("channel:9", false, true));
    }


    @Test
    public void code2502ThenSubscribeIsAGapOnce() {
        GapTracker tracker = new GapTracker();
        tracker.onUnsubscribed("channel:9", 2502);
        assertEquals(ReasonPositionDropped, tracker.onSubscribed("channel:9", false, false));
        assertNull(tracker.onSubscribed("channel:9", false, false));
    }


    @Test
    public void otherTemporaryCodesAreNotAGap() {
        GapTracker tracker = new GapTracker();
        tracker.onUnsubscribed("channel:9", 2500);
        assertNull(tracker.onSubscribed("channel:9", true, true));
    }


    @Test
    public void terminalUnsubscribeForgetsTheMark() {
        GapTracker tracker = new GapTracker();
        tracker.onUnsubscribed("channel:9", 2502);
        tracker.onUnsubscribed("channel:9", 2000);
        assertNull(tracker.onSubscribed("channel:9", false, false));
    }


    @Test
    public void firstConnectIsNotAReconnect() {
        GapTracker tracker = new GapTracker();
        tracker.onConnected();
        assertNull(tracker.onSubscribed("user:5", true, true));
    }


    @Test
    public void reconnectThenRecoveredUserSubscribeReportsSocketReconnectedOnce() {
        GapTracker tracker = new GapTracker();
        tracker.onConnected();
        assertNull(tracker.onSubscribed("user:5", true, true));
        tracker.onConnected();
        assertEquals(ReasonSocketReconnected, tracker.onSubscribed("user:5", true, true));
        assertNull(tracker.onSubscribed("user:5", true, true));
    }


    @Test
    public void reconnectThenUnrecoveredUserSubscribeReportsNotRecoveredOnce() {
        GapTracker tracker = new GapTracker();
        tracker.onConnected();
        tracker.onConnected();
        assertEquals(ReasonNotRecovered, tracker.onSubscribed("user:5", false, false));
        assertNull(tracker.onSubscribed("user:5", true, true));
    }


    @Test
    public void reconnectWithDroppedPositionReportsPositionDroppedOnce() {
        GapTracker tracker = new GapTracker();
        tracker.onConnected();
        tracker.onConnected();
        tracker.onUnsubscribed("user:5", 2502);
        assertEquals(ReasonPositionDropped, tracker.onSubscribed("user:5", true, true));
        assertNull(tracker.onSubscribed("user:5", true, true));
    }


    @Test
    public void reconnectIsNotSpentByAChannelLane() {
        GapTracker tracker = new GapTracker();
        tracker.onConnected();
        tracker.onConnected();
        assertNull(tracker.onSubscribed("channel:9", true, true));
        assertEquals(ReasonSocketReconnected, tracker.onSubscribed("user:5", true, true));
    }


    @Test
    public void markIsPerChannel() {
        GapTracker tracker = new GapTracker();
        tracker.onUnsubscribed("channel:9", 2502);
        assertNull(tracker.onSubscribed("channel:10", false, false));
        assertEquals(ReasonPositionDropped, tracker.onSubscribed("channel:9", false, false));
    }
}
