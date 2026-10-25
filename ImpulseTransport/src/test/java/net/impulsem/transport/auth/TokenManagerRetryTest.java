package net.impulsem.transport.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.impulsem.transport.rpc.ConnectionDeclarer;
import net.impulsem.transport.rpc.FakeCarrier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class TokenManagerRetryTest {

    private static final long NowMillis = 1_700_000_000_000L;
    private static final long NowSeconds = NowMillis / 1000;
    private static final int ConcurrentCallers = 8;

    static final class FakeDelayer implements ConnectionDeclarer.Delayer {

        private final List<Runnable> tasks = new ArrayList<Runnable>();
        private final List<Long> delays = new ArrayList<Long>();


        @Override
        public synchronized void schedule(
            Runnable task,
            long delayMillis
        ) {
            tasks.add(task);
            delays.add(delayMillis);
        }


        synchronized int pending() {
            return tasks.size();
        }


        synchronized long lastDelayMillis() {
            return delays.get(delays.size() - 1);
        }


        void runNext() {
            Runnable task;
            synchronized (this) {
                task = tasks.remove(0);
            }
            task.run();
        }
    }


    private FakeCarrier socket;
    private FakeCarrier pool;
    private TokenManagerTest.MemoryStore store;
    private TokenManagerTest.FakeClock clock;
    private FakeDelayer delayer;
    private TokenManager manager;


    @Before
    public void setUp() throws IOException {
        socket = new FakeCarrier();
        pool = new FakeCarrier();
        store = new TokenManagerTest.MemoryStore();
        clock = new TokenManagerTest.FakeClock();
        clock.now = NowMillis;
        delayer = new FakeDelayer();
        storeAccessExpiringIn(600L);
    }


    @After
    public void tearDown() throws IOException {
        socket.shutdown();
        pool.shutdown();
    }


    private void storeAccessExpiringIn(long secondsLeft) {
        store.tokens = new SessionTokens(TokenManagerTest.jwt(NowSeconds + secondsLeft), "refresh-1", 42L);
        manager = new TokenManager(store, clock, new Random(1L));
        manager.setRefreshCarriers(socket, pool);
        manager.setDeferredRefreshScheduling(
            new java.util.concurrent.Executor() {
                @Override
                public void execute(Runnable command) {
                    command.run();
                }
            },
            delayer
        );
    }


    @Test
    public void floodWaitRefreshIsRetriedOnceAfterFiveSecondsAndSucceeds() throws Exception {
        String original = store.tokens.accessToken;
        socket.fail(TokenManagerTest.appError(420, "FLOOD_WAIT_5"));
        assertTrue(manager.refreshBlocking());
        assertEquals(1, socket.callCount());
        assertEquals(1, delayer.pending());
        assertEquals(5000L, delayer.lastDelayMillis());
        assertEquals(original, manager.bearer());

        socket.reply(TokenManagerTest.authorizationProto(42L, "access-2", "refresh-2"));
        clock.now += 5000L;
        delayer.runNext();

        assertEquals(2, socket.callCount());
        assertEquals(0, delayer.pending());
        assertEquals("access-2", store.tokens.accessToken);
        assertEquals("access-2", manager.bearer());
    }


    @Test
    public void floodWaitLandingPastExpiryMinus120IsNotRetried() throws Exception {
        storeAccessExpiringIn(400L);
        String original = store.tokens.accessToken;
        socket.fail(TokenManagerTest.appError(420, "FLOOD_WAIT_300"));
        try {
            manager.refreshBlocking();
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals(1, socket.callCount());
            assertEquals(0, delayer.pending());
            assertEquals(original, store.tokens.accessToken);
            assertTrue(manager.hasSession());
        }
    }


    @Test
    public void failedDeferredRetryDoesNotThrowFromItsTask() throws Exception {
        String original = store.tokens.accessToken;
        socket.fail(TokenManagerTest.appError(420, "FLOOD_WAIT_5"));
        assertTrue(manager.refreshBlocking());

        socket.fail(TokenManagerTest.appError(500, "INTERNAL"));
        clock.now += 5000L;
        delayer.runNext();

        assertEquals(2, socket.callCount());
        assertEquals(original, store.tokens.accessToken);
        assertTrue(manager.hasSession());
    }


    @Test
    public void concurrentCallersDuringPendingRetrySendOneRequest() throws Exception {
        socket.fail(TokenManagerTest.appError(420, "FLOOD_WAIT_5"));
        assertTrue(manager.refreshBlocking());

        ExecutorService threadPool = Executors.newFixedThreadPool(ConcurrentCallers);
        final CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<Future<Boolean>>();
        for (int i = 0; i < ConcurrentCallers; i++) {
            results.add(threadPool.submit(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    start.await();
                    return manager.refreshBlocking();
                }
            }));
        }
        start.countDown();
        for (Future<Boolean> result : results) {
            assertTrue(result.get(10, TimeUnit.SECONDS));
        }
        threadPool.shutdown();

        assertEquals(1, socket.callCount());
        assertEquals(1, delayer.pending());

        socket.reply(TokenManagerTest.authorizationProto(42L, "access-2", "refresh-2"));
        clock.now += 5000L;
        delayer.runNext();
        assertEquals(2, socket.callCount());
        assertEquals("access-2", store.tokens.accessToken);
    }
}
