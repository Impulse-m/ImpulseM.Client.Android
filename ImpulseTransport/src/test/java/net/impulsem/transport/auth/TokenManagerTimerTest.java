package net.impulsem.transport.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Executor;
import net.impulsem.transport.rpc.FakeCarrier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class TokenManagerTimerTest {

    private static final long NowMillis = 1_700_000_000_000L;
    private static final long NowSeconds = NowMillis / 1000;
    private static final long MinPointDelayMillis = 300_000L;
    private static final long MaxPointDelayMillis = 480_000L;
    private static final long TokenLifetimeSeconds = 600L;

    private static final Executor InlineExecutor = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };


    private FakeCarrier socket;
    private FakeCarrier pool;
    private TokenManagerTest.MemoryStore store;
    private TokenManagerTest.FakeClock clock;
    private TokenManagerRetryTest.FakeDelayer delayer;
    private TokenManager manager;


    @Before
    public void setUp() throws IOException {
        socket = new FakeCarrier();
        pool = new FakeCarrier();
        store = new TokenManagerTest.MemoryStore();
        store.tokens = new SessionTokens(
            TokenManagerTest.jwt(NowSeconds + TokenLifetimeSeconds),
            "refresh-1",
            42L
        );
        clock = new TokenManagerTest.FakeClock();
        clock.now = NowMillis;
        delayer = new TokenManagerRetryTest.FakeDelayer();
        manager = new TokenManager(store, clock, new Random(1L));
        manager.setRefreshCarriers(socket, pool);
        manager.setDeferredRefreshScheduling(InlineExecutor, delayer);
    }


    @After
    public void tearDown() throws IOException {
        socket.shutdown();
        pool.shutdown();
    }


    private long pointMillisOfLastTask() {
        return NowMillis + delayer.lastDelayMillis();
    }


    @Test
    public void storedPairSchedulesItsRefreshPoint() {
        assertEquals(1, delayer.pending());
        long delay = delayer.lastDelayMillis();
        assertTrue("delay " + delay, delay >= MinPointDelayMillis && delay <= MaxPointDelayMillis);

        clock.now = NowMillis + delay - 1000L;
        assertFalse(manager.needsProactiveRefresh());
        clock.now = NowMillis + delay;
        assertTrue(manager.needsProactiveRefresh());
    }


    @Test
    public void reachingThePointRefreshesOnTheSocket() throws Exception {
        long pointMillis = pointMillisOfLastTask();
        socket.reply(
            TokenManagerTest.authorizationProto(
                42L,
                TokenManagerTest.jwt(pointMillis / 1000 + TokenLifetimeSeconds),
                "refresh-2"
            )
        );
        clock.now = pointMillis;

        delayer.runNext();

        assertEquals(1, socket.callCount());
        assertEquals(0, pool.callCount());
        assertEquals(ImpulseRpcPaths.RefreshSessionMethod, socket.takeSent().method);
        assertEquals("refresh-2", store.tokens.refreshToken);
        assertEquals(1, delayer.pending());
        long nextDelay = delayer.lastDelayMillis();
        assertTrue("next delay " + nextDelay, nextDelay >= MinPointDelayMillis && nextDelay <= MaxPointDelayMillis);
    }


    @Test
    public void aReplacedPairMakesTheOldTaskANoOp() throws Exception {
        long oldPointMillis = pointMillisOfLastTask();
        manager.onLoginTokens(TokenManagerTest.jwt(NowSeconds + TokenLifetimeSeconds), "refresh-9", 9L);
        assertEquals(2, delayer.pending());

        clock.now = oldPointMillis;
        delayer.runNext();

        assertEquals(0, socket.callCount());
        assertEquals(0, pool.callCount());
        assertEquals("refresh-9", store.tokens.refreshToken);
    }


    @Test
    public void aClearedSessionMakesThePendingTaskANoOp() throws Exception {
        long pointMillis = pointMillisOfLastTask();
        manager.clear();

        clock.now = pointMillis;
        delayer.runNext();

        assertEquals(0, socket.callCount());
        assertEquals(0, pool.callCount());
        assertNull(store.tokens);
        assertFalse(manager.hasSession());
    }


    @Test
    public void aFailedTimerRefreshDoesNotThrow() throws Exception {
        socket.fail(new IOException("lost"));
        clock.now = pointMillisOfLastTask();

        delayer.runNext();

        assertEquals(1, socket.callCount());
        assertTrue(manager.hasSession());
        assertEquals("refresh-1", store.tokens.refreshToken);
        assertEquals(0, store.clears);
    }


    @Test
    public void opaqueAccessTokenSchedulesNothing() {
        store.tokens = new SessionTokens("opaque-access", "refresh-1", 42L);
        TokenManagerRetryTest.FakeDelayer opaqueDelayer = new TokenManagerRetryTest.FakeDelayer();
        manager = new TokenManager(store, clock, new Random(1L));
        manager.setRefreshCarriers(socket, pool);

        manager.setDeferredRefreshScheduling(InlineExecutor, opaqueDelayer);

        assertEquals(0, opaqueDelayer.pending());
    }


    @Test
    public void aFailedPointRefreshRetriesWithBackoff() throws Exception {
        long pointMillis = pointMillisOfLastTask();
        clock.now = pointMillis;
        socket.fail(new IOException("lost"));

        delayer.runNext();

        assertEquals(1, delayer.pending());
        assertEquals(1000L, delayer.lastDelayMillis());

        clock.now = pointMillis + 1000L;
        socket.fail(new IOException("lost"));

        delayer.runNext();

        assertEquals(2, socket.callCount());
        assertEquals(1, delayer.pending());
        assertEquals(2000L, delayer.lastDelayMillis());
        assertTrue(manager.hasSession());
    }


    @Test
    public void pointRetryStopsBeforeExpiryMinus120() throws Exception {
        long expSeconds = NowSeconds + 200L;
        store.tokens = new SessionTokens(TokenManagerTest.jwt(expSeconds), "refresh-1", 42L);
        delayer = new TokenManagerRetryTest.FakeDelayer();
        manager = new TokenManager(store, clock, new Random(1L));
        manager.setRefreshCarriers(socket, pool);
        manager.setDeferredRefreshScheduling(InlineExecutor, delayer);
        assertEquals(1, delayer.pending());

        // A 1 s retry from here would land past exp - 120 s, so the failed refresh is not retried.
        clock.now = (expSeconds - 120L) * 1000L - 500L;
        socket.fail(new IOException("lost"));

        delayer.runNext();

        assertEquals(1, socket.callCount());
        assertEquals(0, delayer.pending());
        assertTrue(manager.hasSession());
    }
}
