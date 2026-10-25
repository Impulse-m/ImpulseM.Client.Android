package net.impulsem.transport.realtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;


public class SyncPlannerTest {

    @Test
    public void loggedInOnlineConnectsAndSubscribesUserLane() {
        SyncPlanner.Plan plan = new SyncPlanner().plan(5L, true, true);
        assertTrue(plan.connect);
        assertFalse(plan.disconnect);
        assertEquals(5L, plan.subscribeUserLane);
        assertEquals(0L, plan.dropUserLane);
    }


    @Test
    public void logoutDropsTheUserLaneOnceAndReconnectsWithoutALane() {
        SyncPlanner planner = new SyncPlanner();
        planner.plan(5L, true, true);
        SyncPlanner.Plan out = planner.plan(0L, false, true);
        assertTrue(out.stopLanes);
        assertTrue(out.connect);
        assertEquals(5L, out.dropUserLane);
        assertEquals(0L, out.subscribeUserLane);
        assertEquals(0L, planner.plan(0L, false, true).dropUserLane);
    }


    /** Without a session the socket still connects, as identity; the user id alone does not make a lane. */
    @Test
    public void missingSessionCountsAsLoggedOut() {
        SyncPlanner.Plan plan = new SyncPlanner().plan(5L, false, true);
        assertTrue(plan.connect);
        assertFalse(plan.disconnect);
        assertTrue(plan.stopLanes);
        assertEquals(0L, plan.subscribeUserLane);
    }


    @Test
    public void signedOutOnlineConnectsWithoutALane() {
        SyncPlanner.Plan plan = new SyncPlanner().plan(0L, false, true);
        assertTrue(plan.connect);
        assertFalse(plan.disconnect);
        assertEquals(0L, plan.subscribeUserLane);
        assertEquals(0L, plan.dropUserLane);
    }


    @Test
    public void signInRekeysOnce() {
        SyncPlanner planner = new SyncPlanner();
        planner.plan(0L, false, true);
        SyncPlanner.Plan in = planner.plan(5L, true, true);
        assertTrue(in.disconnect);
        assertTrue(in.connect);
        assertEquals(5L, in.subscribeUserLane);
        SyncPlanner.Plan steady = planner.plan(5L, true, true);
        assertFalse(steady.disconnect);
        assertTrue(steady.connect);
    }


    @Test
    public void signOutRekeys() {
        SyncPlanner planner = new SyncPlanner();
        planner.plan(5L, true, true);
        SyncPlanner.Plan out = planner.plan(0L, false, true);
        assertTrue(out.disconnect);
        assertTrue(out.connect);
        SyncPlanner.Plan steady = planner.plan(0L, false, true);
        assertFalse(steady.disconnect);
        assertTrue(steady.connect);
    }


    @Test
    public void firstConnectIsNotARekey() {
        SyncPlanner.Plan plan = new SyncPlanner().plan(5L, true, true);
        assertFalse(plan.disconnect);
        assertTrue(plan.connect);
    }


    @Test
    public void offlineDisconnectsInEitherKind() {
        SyncPlanner identity = new SyncPlanner();
        identity.plan(0L, false, true);
        SyncPlanner.Plan offlineIdentity = identity.plan(0L, false, false);
        assertTrue(offlineIdentity.disconnect);
        assertFalse(offlineIdentity.connect);

        SyncPlanner session = new SyncPlanner();
        session.plan(5L, true, true);
        SyncPlanner.Plan offlineSession = session.plan(5L, true, false);
        assertTrue(offlineSession.disconnect);
        assertFalse(offlineSession.connect);
    }


    @Test
    public void offlineDisconnectsButKeepsLanes() {
        SyncPlanner planner = new SyncPlanner();
        planner.plan(5L, true, true);
        SyncPlanner.Plan plan = planner.plan(5L, true, false);
        assertTrue(plan.disconnect);
        assertFalse(plan.connect);
        assertFalse(plan.stopLanes);
        assertEquals(0L, plan.dropUserLane);
    }


    @Test
    public void networkComingBackReconnectsOnce() {
        SyncPlanner planner = new SyncPlanner();
        planner.plan(5L, true, true);
        planner.plan(5L, true, false);
        SyncPlanner.Plan back = planner.plan(5L, true, true);
        assertTrue(back.disconnect);
        assertTrue(back.connect);
        SyncPlanner.Plan steady = planner.plan(5L, true, true);
        assertFalse(steady.disconnect);
        assertTrue(steady.connect);
    }


    @Test
    public void userSwitchDropsThePreviousLane() {
        SyncPlanner planner = new SyncPlanner();
        planner.plan(5L, true, true);
        SyncPlanner.Plan plan = planner.plan(6L, true, true);
        assertEquals(5L, plan.dropUserLane);
        assertTrue(plan.stopLanes);
        assertEquals(6L, plan.subscribeUserLane);
    }


    @Test
    public void userSwitchDisconnectsSoTheSocketDropsTheOldToken() {
        SyncPlanner planner = new SyncPlanner();
        planner.plan(5L, true, true);
        SyncPlanner.Plan plan = planner.plan(6L, true, true);
        assertTrue(plan.disconnect);
        assertTrue(plan.connect);
    }


    @Test
    public void concurrentTriggersDropEachLaneExactlyOnce() throws Exception {
        for (int round = 0; round < 50; round++) {
            final SyncPlanner planner = new SyncPlanner();
            planner.plan(5L, true, true);
            final AtomicInteger drops = new AtomicInteger();
            final CountDownLatch start = new CountDownLatch(1);
            Thread[] threads = new Thread[8];
            for (int a = 0; a < threads.length; a++) {
                threads[a] = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            return;
                        }
                        for (int b = 0; b < 100; b++) {
                            if (planner.plan(0L, false, true).dropUserLane == 5L) {
                                drops.incrementAndGet();
                            }
                        }
                    }
                });
                threads[a].start();
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join();
            }
            assertEquals(1, drops.get());
        }
    }
}
