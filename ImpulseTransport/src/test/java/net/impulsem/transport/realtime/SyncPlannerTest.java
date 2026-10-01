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
    public void logoutDropsTheUserLaneOnceAndDisconnects() {
        SyncPlanner planner = new SyncPlanner();
        planner.plan(5L, true, true);
        SyncPlanner.Plan out = planner.plan(0L, false, true);
        assertTrue(out.disconnect);
        assertTrue(out.stopLanes);
        assertFalse(out.connect);
        assertEquals(5L, out.dropUserLane);
        assertEquals(0L, planner.plan(0L, false, true).dropUserLane);
    }


    @Test
    public void missingSessionCountsAsLoggedOut() {
        SyncPlanner.Plan plan = new SyncPlanner().plan(5L, false, true);
        assertTrue(plan.disconnect);
        assertFalse(plan.connect);
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
