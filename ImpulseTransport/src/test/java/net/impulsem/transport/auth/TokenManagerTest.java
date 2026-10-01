package net.impulsem.transport.auth;

import static net.impulsem.transport.GrpcWebTestSupport.error;
import static net.impulsem.transport.GrpcWebTestSupport.ok;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.impulsem.transport.grpcweb.GrpcWebClient;
import net.impulsem.transport.wire.ProtoWriter;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
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


    private MockWebServer server;
    private MemoryStore store;
    private FakeClock clock;
    private TokenManager manager;


    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        store = new MemoryStore();
        store.tokens = new SessionTokens("access-1", "refresh-1", 42L);
        clock = new FakeClock();
        manager = newManager();
    }


    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }


    private TokenManager newManager() {
        return new TokenManager(store, new GrpcWebClient(new OkHttpClient(), server.url("/")), clock);
    }


    static byte[] authorizationProto(
        long userId,
        String access,
        String refresh
    ) {
        ProtoWriter writer = new ProtoWriter();
        writer.writeVarintField(1, userId);
        writer.writeBytesField(5, access.getBytes(StandardCharsets.UTF_8));
        writer.writeBytesField(6, refresh.getBytes(StandardCharsets.UTF_8));
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


    @Test
    public void concurrentRefreshHitsServerOnce() throws Exception {
        server.enqueue(ok(authorizationProto(42L, "access-2", "refresh-2")).setBodyDelay(500, TimeUnit.MILLISECONDS));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<Future<Boolean>>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(new Callable<Boolean>() {
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
        pool.shutdown();

        assertEquals(1, server.getRequestCount());
        RecordedRequest request = server.takeRequest();
        assertEquals("/impulse.auth.AuthService/RefreshSession", request.getPath());
        assertNull(request.getHeader("authorization"));
        assertEquals("access-2", store.tokens.accessToken);
        assertEquals("refresh-2", store.tokens.refreshToken);
        assertEquals(1, store.saves);
        assertEquals("access-2", manager.bearer());
    }


    @Test
    public void refreshRequestCarriesRefreshToken() throws Exception {
        server.enqueue(ok(authorizationProto(42L, "a2", "r2")));
        assertTrue(manager.refreshBlocking());
        byte[] body = server.takeRequest().getBody().readByteArray();
        byte[] expected = new byte[] {0, 0, 0, 0, 11, 0x0A, 9, 'r', 'e', 'f', 'r', 'e', 's', 'h', '-', '1'};
        assertArrayEquals(expected, body);
    }


    @Test
    public void refreshTokenExpiredClearsStore() throws Exception {
        server.enqueue(error(3, "AUTH_TOKEN_EXPIRED"));
        assertFalse(manager.refreshBlocking());
        assertNull(store.tokens);
        assertEquals(1, store.clears);
        assertFalse(manager.hasSession());
        assertNull(manager.bearer());
    }


    @Test
    public void refreshTokenInvalidAndSessionExpiredClearStore() throws Exception {
        server.enqueue(error(3, "AUTH_TOKEN_INVALID"));
        assertFalse(manager.refreshBlocking());
        assertNull(store.tokens);

        store.tokens = new SessionTokens("a", "r", 1L);
        manager = newManager();
        server.enqueue(error(16, "SESSION_EXPIRED"));
        assertFalse(manager.refreshBlocking());
        assertNull(store.tokens);
    }


    @Test
    public void transientRefreshFailureThrowsAndKeepsSession() throws Exception {
        server.enqueue(error(14, "UNAVAILABLE"));
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
        assertEquals(0, server.getRequestCount());
    }


    @Test
    public void pendingTokenExpiresAfterFiveMinutes() {
        manager.setPendingToken("pending");
        assertEquals("pending", manager.bearer());
        clock.now += 5 * 60 * 1000L - 1;
        assertEquals("pending", manager.bearer());
        clock.now += 1;
        assertEquals("access-1", manager.bearer());
    }


    @Test
    public void loginTokensClearPendingAndPersist() {
        manager.setPendingToken("pending");
        manager.onLoginTokens("a9", "r9", 9L);
        assertEquals("a9", manager.bearer());
        assertEquals("a9", store.tokens.accessToken);
        assertEquals("r9", store.tokens.refreshToken);
        assertEquals(9L, store.tokens.userId);
    }


    @Test
    public void qrTicketRoundTrip() {
        assertNull(manager.qrExporterTicket());
        manager.setQrExporterTicket("ticket");
        assertEquals("ticket", manager.qrExporterTicket());
    }


    @Test
    public void needsProactiveRefreshWhenExpiryWithin60Seconds() {
        long nowSeconds = clock.now / 1000;
        store.tokens = new SessionTokens(jwt(nowSeconds + 30), "r", 1L);
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
    public void refreshDoesNotClearActivePendingToken() throws Exception {
        manager.setPendingToken("pending");
        server.enqueue(ok(authorizationProto(42L, "a2", "r2")));
        assertTrue(manager.refreshBlocking());
        assertEquals("pending", manager.bearer());
        assertEquals("a2", store.tokens.accessToken);
        clock.now += 5 * 60 * 1000L;
        assertEquals("a2", manager.bearer());
    }


    @Test
    public void noProactiveRefreshWhilePendingTokenActive() {
        long nowSeconds = clock.now / 1000;
        store.tokens = new SessionTokens(jwt(nowSeconds + 10), "r", 1L);
        manager = newManager();
        assertTrue(manager.needsProactiveRefresh());
        manager.setPendingToken("pending");
        assertFalse(manager.needsProactiveRefresh());
        clock.now += 5 * 60 * 1000L;
        assertTrue(manager.needsProactiveRefresh());
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
}
