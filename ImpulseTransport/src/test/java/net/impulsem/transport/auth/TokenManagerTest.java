package net.impulsem.transport.auth;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.centrifugal.centrifuge.ReplyError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.impulsem.transport.rpc.CarrierCall;
import net.impulsem.transport.rpc.CarrierCallback;
import net.impulsem.transport.rpc.FakeCarrier;
import net.impulsem.transport.rpc.RpcCarrier;
import net.impulsem.transport.wire.ProtoWriter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class TokenManagerTest {

    static class MemoryStore implements SessionStore {

        volatile SessionTokens tokens;
        volatile int saves;
        volatile int clears;


        @Override
        public SessionTokens load() {
            return tokens;
        }


        @Override
        public synchronized void save(SessionTokens value) {
            tokens = value;
            saves++;
        }


        @Override
        public synchronized void clear() {
            tokens = null;
            clears++;
        }
    }


    static final class FakeClock implements Clock {

        volatile long now = 1_700_000_000_000L;


        @Override
        public long nowMillis() {
            return now;
        }
    }


    private FakeCarrier socket;
    private FakeCarrier pool;
    private MemoryStore store;
    private FakeClock clock;
    private TokenManager manager;


    @Before
    public void setUp() throws IOException {
        socket = new FakeCarrier();
        pool = new FakeCarrier();
        store = new MemoryStore();
        store.tokens = new SessionTokens("access-1", "refresh-1", 42L);
        clock = new FakeClock();
        manager = newManager();
    }


    @After
    public void tearDown() throws IOException {
        socket.shutdown();
        pool.shutdown();
    }


    private TokenManager newManager() {
        return newManager(new Random());
    }


    private TokenManager newManager(Random random) {
        TokenManager built = new TokenManager(store, clock, random);
        built.setRefreshCarriers(socket, pool);
        return built;
    }


    /** impulse.auth.RefreshSessionResponse { user_id = 1; session_token = 2; refresh_token = 3; grace = 4; } */
    static byte[] authorizationProto(
        long userId,
        String access,
        String refresh
    ) {
        ProtoWriter writer = new ProtoWriter();
        writer.writeVarintField(1, userId);
        writer.writeBytesField(2, access.getBytes(StandardCharsets.UTF_8));
        writer.writeBytesField(3, refresh.getBytes(StandardCharsets.UTF_8));
        writer.writeVarintField(4, 30);
        return writer.toByteArray();
    }


    static String jwt(long expSeconds) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(
            ("{\"sub\":\"1\",\"exp\":" + expSeconds + "}").getBytes(StandardCharsets.UTF_8)
        );
        return header + "." + payload + ".sig";
    }


    /** An app error as the server reports it: the Telegram code and text ride in the app fields. */
    static ReplyError appError(
        int appCode,
        String appType
    ) {
        return new ReplyError(100, "", false, appCode, appType, Collections.<String, String>emptyMap());
    }


    /** Holds each call for a while before it reaches the inner carrier, so concurrent callers pile up behind it. */
    private static RpcCarrier delayed(
        final RpcCarrier inner,
        final long delayMillis
    ) {
        return new RpcCarrier() {
            @Override
            public CarrierCall call(
                String method,
                byte[] data,
                long timeoutMillis,
                CarrierCallback callback
            ) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return inner.call(method, data, timeoutMillis, callback);
            }
        };
    }


    /** Signals arrival, then holds the call until the test releases it. */
    private static RpcCarrier gated(
        final RpcCarrier inner,
        final CountDownLatch arrived,
        final CountDownLatch release
    ) {
        return new RpcCarrier() {
            @Override
            public CarrierCall call(
                String method,
                byte[] data,
                long timeoutMillis,
                CarrierCallback callback
            ) {
                arrived.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return inner.call(method, data, timeoutMillis, callback);
            }
        };
    }


    @Test
    public void concurrentRefreshHitsServerOnce() throws Exception {
        socket.reply(authorizationProto(42L, "access-2", "refresh-2"));
        manager.setRefreshCarriers(delayed(socket, 500L), pool);
        int threads = 8;
        ExecutorService threadPool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<Future<Boolean>>();
        for (int i = 0; i < threads; i++) {
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
        FakeCarrier.Sent sent = socket.takeSent();
        assertEquals(ImpulseRpcPaths.RefreshSessionMethod, sent.method);
        assertEquals("access-2", store.tokens.accessToken);
        assertEquals("refresh-2", store.tokens.refreshToken);
        assertEquals(1, store.saves);
        assertEquals("access-2", manager.bearer());
    }


    @Test
    public void refreshRequestCarriesRefreshToken() throws Exception {
        socket.reply(authorizationProto(42L, "a2", "r2"));
        assertTrue(manager.refreshBlocking());
        byte[] data = socket.takeSent().data;
        byte[] expected = new byte[] {0x0A, 9, 'r', 'e', 'f', 'r', 'e', 's', 'h', '-', '1'};
        assertArrayEquals(expected, data);
    }


    @Test
    public void refreshTokenExpiredClearsStore() throws Exception {
        socket.fail(appError(400, "AUTH_TOKEN_EXPIRED"));
        assertFalse(manager.refreshBlocking());
        assertNull(store.tokens);
        assertEquals(1, store.clears);
        assertFalse(manager.hasSession());
        assertNull(manager.bearer());
    }


    @Test
    public void refreshTokenInvalidAndSessionExpiredClearStore() throws Exception {
        socket.fail(appError(400, "AUTH_TOKEN_INVALID"));
        assertFalse(manager.refreshBlocking());
        assertNull(store.tokens);

        store.tokens = new SessionTokens("a", "r", 1L);
        manager = newManager();
        socket.fail(appError(401, "SESSION_EXPIRED"));
        assertFalse(manager.refreshBlocking());
        assertNull(store.tokens);
    }


    @Test
    public void transientRefreshFailureThrowsAndKeepsSession() throws Exception {
        socket.fail(new IOException("lost"));
        try {
            manager.refreshBlocking();
            org.junit.Assert.fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(manager.hasSession());
            assertEquals("refresh-1", store.tokens.refreshToken);
        }
    }


    @Test
    public void refreshWithoutSessionReturnsFalse() throws Exception {
        store.tokens = null;
        manager = newManager();
        assertFalse(manager.refreshBlocking());
        assertEquals(0, socket.callCount());
        assertEquals(0, pool.callCount());
    }


    @Test
    public void refreshWithoutCarriersFails() throws Exception {
        manager = new TokenManager(store, clock, new Random());
        try {
            manager.refreshBlocking();
            org.junit.Assert.fail("expected IOException");
        } catch (IOException expected) {
            assertEquals("no refresh carrier", expected.getMessage());
        }
        assertTrue(manager.hasSession());
    }


    @Test
    public void loginTokensPersist() {
        manager.onLoginTokens("a9", "r9", 9L);
        assertEquals("a9", manager.bearer());
        assertEquals("a9", store.tokens.accessToken);
        assertEquals("r9", store.tokens.refreshToken);
        assertEquals(9L, store.tokens.userId);
    }


    @Test
    public void proactiveRefreshDrawsPointInFiveToTwoMinuteWindow() {
        long startMillis = clock.now;
        long expSeconds = startMillis / 1000 + 600;
        for (int draw = 0; draw < 200; draw++) {
            clock.now = startMillis;
            store.tokens = new SessionTokens(jwt(expSeconds), "r", 1L);
            manager = newManager();
            assertFalse("draw " + draw + ": refresh before exp-300 s", manager.needsProactiveRefresh());
            clock.now = (expSeconds - 301) * 1000L;
            assertFalse("draw " + draw + ": refresh 301 s before exp", manager.needsProactiveRefresh());
            clock.now = (expSeconds - 119) * 1000L;
            assertTrue("draw " + draw + ": no refresh 119 s before exp", manager.needsProactiveRefresh());
        }
    }


    @Test
    public void tokenStoredWithTwoHundredSecondsLeftRefreshesBeforeExpiryMinus120() {
        long startMillis = clock.now;
        long expSeconds = startMillis / 1000 + 200;
        for (int draw = 0; draw < 200; draw++) {
            clock.now = startMillis;
            store.tokens = new SessionTokens(jwt(expSeconds), "r", 1L);
            manager = newManager();
            clock.now = (expSeconds - 120) * 1000L;
            assertTrue("draw " + draw + ": no refresh by exp-120 s", manager.needsProactiveRefresh());
        }
    }


    @Test
    public void proactiveRefreshTurnsTrueAtTheDrawnPoint() {
        long startMillis = clock.now;
        long expSeconds = startMillis / 1000 + 600;
        for (long seed = 0; seed < 50; seed++) {
            clock.now = startMillis;
            long point = new RefreshSchedule(new Random(seed)).drawRefreshPointMillis(expSeconds, startMillis);
            store.tokens = new SessionTokens(jwt(expSeconds), "r", 1L);
            manager = newManager(new Random(seed));
            clock.now = point - 1000L;
            assertFalse("seed " + seed + ": refresh one second before the point", manager.needsProactiveRefresh());
            clock.now = point;
            assertTrue("seed " + seed + ": no refresh at the point", manager.needsProactiveRefresh());
        }
    }


    @Test
    public void tokenStoredWithOneHundredSecondsLeftRefreshesAtOnce() {
        long nowSeconds = clock.now / 1000;
        store.tokens = new SessionTokens(jwt(nowSeconds + 100), "r", 1L);
        manager = newManager();
        assertTrue(manager.needsProactiveRefresh());
    }


    @Test
    public void noProactiveRefreshWhenFarFromExpiry() {
        long nowSeconds = clock.now / 1000;
        store.tokens = new SessionTokens(jwt(nowSeconds + 600), "r", 1L);
        manager = newManager();
        assertFalse(manager.needsProactiveRefresh());
        clock.now += 541_000L;
        assertTrue(manager.needsProactiveRefresh());
    }


    @Test
    public void noProactiveRefreshForOpaqueTokenOrNoSession() {
        assertFalse(manager.needsProactiveRefresh());
        manager.clear();
        assertFalse(manager.needsProactiveRefresh());
        assertFalse(manager.hasSession());
    }


    @Test
    public void onLoginTokensUpdatesMemoryEvenWhenSaveThrows() {
        store = new MemoryStore() {
            @Override
            public synchronized void save(SessionTokens value) {
                throw new IllegalStateException("disk full");
            }
        };
        manager = newManager();
        try {
            manager.onLoginTokens("a9", "r9", 9L);
            org.junit.Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals("a9", manager.bearer());
            assertTrue(manager.hasSession());
        }
    }


    @Test
    public void nonPositiveExpIsUnreadable() {
        store.tokens = new SessionTokens(jwt(0), "r", 1L);
        manager = newManager();
        assertFalse(manager.needsProactiveRefresh());
        store.tokens = new SessionTokens(jwt(-5), "r", 1L);
        manager = newManager();
        assertFalse(manager.needsProactiveRefresh());
    }


    @Test
    public void refreshCompletingAfterClearDoesNotRestoreTheSession() throws Exception {
        final CountDownLatch requestSeen = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        manager.setRefreshCarriers(gated(socket, requestSeen, release), pool);
        socket.reply(authorizationProto(42L, "access-2", "refresh-2"));
        ExecutorService threadPool = Executors.newFixedThreadPool(1);
        Future<Boolean> refresh = threadPool.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                return manager.refreshBlocking();
            }
        });
        assertTrue(requestSeen.await(5, TimeUnit.SECONDS));

        manager.clear();
        release.countDown();

        assertFalse(refresh.get(10, TimeUnit.SECONDS));
        threadPool.shutdown();
        assertNull(store.tokens);
        assertFalse(manager.hasSession());
        assertNull(manager.bearer());
    }


    @Test
    public void refreshGoesOnTheUpdateSocketWhileTheAccessTokenIsValid() throws Exception {
        store.tokens = new SessionTokens(jwt(clock.now / 1000 + 600), "refresh-1", 42L);
        manager = newManager();
        socket.reply(authorizationProto(42L, "a2", "r2"));

        assertTrue(manager.refreshBlocking());

        assertEquals(1, socket.callCount());
        assertEquals(0, pool.callCount());
        assertEquals(ImpulseRpcPaths.RefreshSessionMethod, socket.takeSent().method);
    }


    @Test
    public void expiredAccessTokenRefreshesThroughThePool() throws Exception {
        store.tokens = new SessionTokens(jwt(clock.now / 1000 - 10), "refresh-1", 42L);
        manager = newManager();
        pool.reply(authorizationProto(42L, "a2", "r2"));

        assertTrue(manager.refreshBlocking());

        assertEquals(0, socket.callCount());
        assertEquals(1, pool.callCount());
        assertEquals(ImpulseRpcPaths.RefreshSessionMethod, pool.takeSent().method);
        assertEquals("a2", store.tokens.accessToken);
    }


    @Test
    public void refusedAccessTokenRefreshesThroughThePool() throws Exception {
        manager.markRefused(manager.bearer());
        pool.reply(authorizationProto(42L, "a2", "r2"));

        assertTrue(manager.refreshBlocking());

        assertEquals(0, socket.callCount());
        assertEquals(1, pool.callCount());
        assertEquals("a2", store.tokens.accessToken);
    }


    @Test
    public void socketTokenRefusalRetriesOnThePool() throws Exception {
        socket.fail(new ReplyError(109, "token expired", true));
        pool.reply(authorizationProto(42L, "a2", "r2"));

        assertTrue(manager.refreshBlocking());

        assertEquals("a2", store.tokens.accessToken);
        assertEquals(1, socket.callCount());
        assertEquals(1, pool.callCount());
    }


    @Test
    public void connectionTokenBlockingRefreshesAnExpiredTokenFirst() throws Exception {
        store.tokens = new SessionTokens(jwt(clock.now / 1000 - 10), "refresh-1", 42L);
        manager = newManager();
        pool.reply(authorizationProto(42L, "a2", "r2"));

        assertEquals("a2", manager.connectionTokenBlocking());

        assertEquals(1, pool.callCount());
        assertEquals(0, socket.callCount());
    }


    @Test
    public void connectionTokenBlockingKeepsAValidTokenPastItsRefreshPoint() throws Exception {
        String valid = jwt(clock.now / 1000 + 100);
        store.tokens = new SessionTokens(valid, "refresh-1", 42L);
        manager = newManager();
        assertTrue(manager.needsProactiveRefresh());

        // The socket is connecting when the SDK asks: a refresh on it would wait for itself.
        assertEquals(valid, manager.connectionTokenBlocking());

        assertEquals(0, socket.callCount());
        assertEquals(0, pool.callCount());
    }


    @Test
    public void connectionTokenBlockingIsNullWithoutASession() throws Exception {
        store.tokens = null;
        manager = newManager();

        assertNull(manager.connectionTokenBlocking());

        assertEquals(0, socket.callCount());
        assertEquals(0, pool.callCount());
    }


    @Test
    public void everySessionListenerRunsEvenIfOneThrows() {
        final AtomicInteger ran = new AtomicInteger();
        manager.addSessionListener(new Runnable() {
            @Override
            public void run() {
                throw new IllegalStateException("listener broke");
            }
        });
        manager.addSessionListener(new Runnable() {
            @Override
            public void run() {
                ran.incrementAndGet();
            }
        });

        manager.onLoginTokens("a9", "r9", 9L);

        assertEquals(1, ran.get());
        assertEquals("a9", manager.bearer());
    }
}
