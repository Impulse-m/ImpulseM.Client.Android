package net.impulsem.transport.rpc;

import static net.impulsem.transport.GrpcWebTestSupport.error;
import static net.impulsem.transport.GrpcWebTestSupport.ok;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.impulsem.transport.auth.Clock;
import net.impulsem.transport.auth.SessionStore;
import net.impulsem.transport.auth.SessionTokens;
import net.impulsem.transport.auth.TokenManager;
import net.impulsem.transport.codec.Transcoder;
import net.impulsem.transport.grpcweb.GrpcWebClient;
import net.impulsem.transport.schema.TlProtoSchema;
import net.impulsem.transport.wire.ProtoWriter;
import net.impulsem.transport.wire.TlReader;
import net.impulsem.transport.wire.TlWriter;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class RpcClientTest {

    private static final int BoolTrue = 0x997275b5;
    private static final int ResetAuthorizationsId = -1616179942;
    private static final int AuthSignInId = -1923962543;
    private static final int InvokeWithTakeoutId = -1398145746;
    private static final int AuthorizationId = 782418132;

    private static final class MemoryStore implements SessionStore {

        volatile SessionTokens tokens;


        @Override
        public SessionTokens load() {
            return tokens;
        }


        @Override
        public synchronized void save(SessionTokens value) {
            tokens = value;
        }


        @Override
        public synchronized void clear() {
            tokens = null;
        }
    }


    private MockWebServer server;
    private MemoryStore store;
    private TokenManager tokens;
    private RpcClient client;


    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        store = new MemoryStore();
        store.tokens = new SessionTokens("old", "r1", 5L);
        build();
    }


    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }


    private void build() {
        GrpcWebClient grpc = new GrpcWebClient(new OkHttpClient(), server.url("/"));
        tokens = new TokenManager(
            store,
            grpc,
            new Clock() {
                @Override
                public long nowMillis() {
                    return 1_700_000_000_000L;
                }
            }
        );
        client = new RpcClient(new Transcoder(TlProtoSchema.load()), grpc, tokens);
    }


    private static byte[] resetAuthorizations() {
        TlWriter writer = new TlWriter();
        writer.writeInt32(ResetAuthorizationsId);
        return writer.toByteArray();
    }


    private static byte[] signIn() {
        TlWriter writer = new TlWriter();
        writer.writeInt32(AuthSignInId);
        writer.writeInt32(1);
        writer.writeString("+99966");
        writer.writeString("hash");
        writer.writeString("11111");
        return writer.toByteArray();
    }


    private static byte[] bool() {
        TlWriter writer = new TlWriter();
        writer.writeInt32(BoolTrue);
        return writer.toByteArray();
    }


    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }


    /** auth.authorization whose user is the UserValue arm (2); its id is field 28. */
    private static byte[] authorization(
        String access,
        String refresh,
        long userId
    ) {
        ProtoWriter userValue = new ProtoWriter();
        userValue.writeVarintField(28, userId);
        ProtoWriter user = new ProtoWriter();
        user.writeBytesField(2, userValue.toByteArray());
        ProtoWriter value = new ProtoWriter();
        value.writeBytesField(5, user.toByteArray());
        value.writeBytesField(6, utf8(access));
        value.writeBytesField(7, utf8(refresh));
        ProtoWriter outer = new ProtoWriter();
        outer.writeBytesField(1, value.toByteArray());
        return outer.toByteArray();
    }


    /** impulse.auth.Authorization, the RefreshSession response. */
    private static byte[] refreshResponse(
        String access,
        String refresh,
        long userId
    ) {
        ProtoWriter writer = new ProtoWriter();
        writer.writeVarintField(1, userId);
        writer.writeBytesField(5, utf8(access));
        writer.writeBytesField(6, utf8(refresh));
        return writer.toByteArray();
    }


    @Test
    public void refreshAndRetryOnAuthKeyUnregistered() throws Exception {
        server.enqueue(error(16, "AUTH_KEY_UNREGISTERED"));
        server.enqueue(ok(refreshResponse("new", "r2", 5L)));
        server.enqueue(ok(new byte[0]));

        RpcOutcome outcome = client.callBlocking(resetAuthorizations());

        assertNull(outcome.error);
        assertFalse(outcome.forceLogout);
        assertArrayEquals(bool(), outcome.tlResult);
        assertEquals(3, server.getRequestCount());
        RecordedRequest first = server.takeRequest();
        assertEquals("/v1.auth.Auth/ResetAuthorizations", first.getPath());
        assertEquals("Bearer old", first.getHeader("authorization"));
        RecordedRequest refresh = server.takeRequest();
        assertEquals("/impulse.auth.AuthService/RefreshSession", refresh.getPath());
        assertNull(refresh.getHeader("authorization"));
        RecordedRequest retry = server.takeRequest();
        assertEquals("/v1.auth.Auth/ResetAuthorizations", retry.getPath());
        assertEquals("Bearer new", retry.getHeader("authorization"));
        assertEquals("new", store.tokens.accessToken);
        assertEquals("r2", store.tokens.refreshToken);
    }


    @Test
    public void secondAuthKeyUnregisteredIsReturnedWithoutLoop() throws Exception {
        server.enqueue(error(16, "AUTH_KEY_UNREGISTERED"));
        server.enqueue(ok(refreshResponse("new", "r2", 5L)));
        server.enqueue(error(16, "AUTH_KEY_UNREGISTERED"));

        RpcOutcome outcome = client.callBlocking(resetAuthorizations());

        assertNotNull(outcome.error);
        assertEquals("AUTH_KEY_UNREGISTERED", outcome.error.text);
        assertEquals(3, server.getRequestCount());
    }


    @Test
    public void failedRefreshForcesLogout() throws Exception {
        server.enqueue(error(16, "AUTH_KEY_UNREGISTERED"));
        server.enqueue(error(3, "AUTH_TOKEN_INVALID"));

        RpcOutcome outcome = client.callBlocking(resetAuthorizations());

        assertTrue(outcome.forceLogout);
        assertNull(outcome.tlResult);
        assertNull(store.tokens);
    }


    @Test
    public void sessionRevokedForcesLogout() throws Exception {
        server.enqueue(error(16, "SESSION_REVOKED"));

        RpcOutcome outcome = client.callBlocking(resetAuthorizations());

        assertTrue(outcome.forceLogout);
        assertEquals("SESSION_REVOKED", outcome.error.text);
        assertEquals(401, outcome.error.code);
        assertEquals(1, server.getRequestCount());
    }


    @Test
    public void passwordNeededSetsPendingTokenForNextCall() throws Exception {
        server.enqueue(error(16, "SESSION_PASSWORD_NEEDED", "pending-token", "pend-jwt"));
        server.enqueue(ok(new byte[0]));

        RpcOutcome outcome = client.callBlocking(resetAuthorizations());

        assertEquals("SESSION_PASSWORD_NEEDED", outcome.error.text);
        assertFalse(outcome.forceLogout);
        assertEquals("pend-jwt", tokens.bearer());
        assertEquals("Bearer old", server.takeRequest().getHeader("authorization"));

        client.callBlocking(resetAuthorizations());
        assertEquals("Bearer pend-jwt", server.takeRequest().getHeader("authorization"));
    }


    @Test
    public void signInResponsePersistsTokensAndUserId() throws Exception {
        store.tokens = null;
        build();
        tokens.setPendingToken("pend");
        server.enqueue(ok(authorization("acc", "ref", 777L)));

        RpcOutcome outcome = client.callBlocking(signIn());

        assertNull(outcome.error);
        assertNotNull(outcome.tlResult);
        assertEquals(AuthorizationId, new TlReader(outcome.tlResult, 0, outcome.tlResult.length).readInt32());
        assertEquals("acc", store.tokens.accessToken);
        assertEquals("ref", store.tokens.refreshToken);
        assertEquals(777L, store.tokens.userId);
        assertEquals("acc", tokens.bearer());
        assertEquals("Bearer pend", server.takeRequest().getHeader("authorization"));
    }


    @Test
    public void unmappedMethodReturnsMethodInvalidWithoutNetwork() throws Exception {
        TlWriter writer = new TlWriter();
        writer.writeInt32(0x12345678);

        RpcOutcome outcome = client.callBlocking(writer.toByteArray());

        assertNull(outcome.tlResult);
        assertEquals(400, outcome.error.code);
        assertEquals("METHOD_INVALID", outcome.error.text);
        assertEquals(0, server.getRequestCount());
    }


    @Test
    public void http404IsFinalMethodInvalid() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));

        RpcOutcome outcome = client.callBlocking(resetAuthorizations());

        assertNull(outcome.tlResult);
        assertFalse(outcome.forceLogout);
        assertEquals(400, outcome.error.code);
        assertEquals("METHOD_INVALID", outcome.error.text);
        assertEquals(1, server.getRequestCount());
    }


    @Test
    public void qrTicketIsCapturedAndEchoed() throws Exception {
        server.enqueue(error(16, "SESSION_REVOKED", "x-impulse-qr-exporter-ticket", "tk1"));
        server.enqueue(ok(new byte[0]));
        client.callBlocking(resetAuthorizations());
        assertEquals("tk1", tokens.qrExporterTicket());
        assertNull(server.takeRequest().getHeader("x-impulse-qr-exporter-ticket"));
        client.callBlocking(resetAuthorizations());
        assertEquals("tk1", server.takeRequest().getHeader("x-impulse-qr-exporter-ticket"));
    }


    @Test
    public void takeoutIdBecomesHeader() throws Exception {
        TlWriter writer = new TlWriter();
        writer.writeInt32(InvokeWithTakeoutId);
        writer.writeInt64(555L);
        writer.writeRaw(resetAuthorizations());
        server.enqueue(ok(new byte[0]));
        RpcOutcome outcome = client.callBlocking(writer.toByteArray());
        assertNull(outcome.error);
        assertEquals("555", server.takeRequest().getHeader("x-takeout-id"));
    }


    @Test
    public void callImpulseReturnsBodyAndSendsBearer() throws Exception {
        server.enqueue(ok(utf8("payload")));
        byte[] body = client.callImpulse("/impulse.sync.SyncService/GetCentrifugoToken", new byte[0]);
        assertArrayEquals(utf8("payload"), body);
        RecordedRequest request = server.takeRequest();
        assertEquals("/impulse.sync.SyncService/GetCentrifugoToken", request.getPath());
        assertEquals("Bearer old", request.getHeader("authorization"));
    }


    @Test
    public void callImpulseRefreshesAndRetries() throws Exception {
        server.enqueue(error(16, "AUTH_KEY_UNREGISTERED"));
        server.enqueue(ok(refreshResponse("new", "r2", 5L)));
        server.enqueue(ok(utf8("x")));
        assertArrayEquals(utf8("x"), client.callImpulse("/impulse.sync.SyncService/GetCentrifugoToken", new byte[0]));
        server.takeRequest();
        server.takeRequest();
        assertEquals("Bearer new", server.takeRequest().getHeader("authorization"));
    }


    @Test(expected = IOException.class)
    public void callImpulseThrowsOnErrorStatus() throws Exception {
        server.enqueue(error(8, "FLOOD_WAIT_3"));
        client.callImpulse("/impulse.sync.SyncService/GetCentrifugoToken", new byte[0]);
    }


    @Test
    public void transportFailureThrows() throws Exception {
        server.shutdown();
        try {
            client.callBlocking(resetAuthorizations());
            fail("expected IOException");
        } catch (IOException expected) {
            assertNotNull(expected);
        }
    }


    private static final String RefreshPath = "/impulse.auth.AuthService/RefreshSession";
    private static final String TokenPath = "/impulse.sync.SyncService/GetCentrifugoToken";


    private void awaitRequests(int count) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (server.getRequestCount() < count) {
            if (System.currentTimeMillis() > deadline) {
                fail("server never saw request " + count);
            }
            Thread.sleep(10);
        }
    }


    private Future<RpcOutcome> executeInBackground(
        ExecutorService pool,
        final Call call
    ) {
        return pool.submit(new Callable<RpcOutcome>() {
            @Override
            public RpcOutcome call() throws Exception {
                return client.execute(call);
            }
        });
    }


    private Dispatcher lateFailureDispatcher(
        final AtomicInteger oldCalls,
        final AtomicInteger refreshes,
        final AtomicInteger newCalls
    ) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (RefreshPath.equals(request.getPath())) {
                    refreshes.incrementAndGet();
                    return ok(refreshResponse("new", "r2", 5L));
                }
                String auth = request.getHeader("authorization");
                if ("Bearer old".equals(auth)) {
                    MockResponse response = error(16, "AUTH_KEY_UNREGISTERED");
                    if (oldCalls.incrementAndGet() == 1) {
                        response.setHeadersDelay(800, TimeUnit.MILLISECONDS);
                    }
                    return response;
                }
                if ("Bearer new".equals(auth)) {
                    newCalls.incrementAndGet();
                    return ok(utf8("t"));
                }
                return new MockResponse().setResponseCode(500);
            }
        };
    }


    @Test
    public void lateFailureRetriesWithNewBearerWithoutSecondRefresh() throws Exception {
        AtomicInteger oldCalls = new AtomicInteger();
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger newCalls = new AtomicInteger();
        server.setDispatcher(lateFailureDispatcher(oldCalls, refreshes, newCalls));
        ExecutorService pool = Executors.newFixedThreadPool(1);
        Future<RpcOutcome> late = executeInBackground(pool, client.prepare(resetAuthorizations()));
        awaitRequests(1);

        RpcOutcome early = client.callBlocking(resetAuthorizations());
        RpcOutcome lateOutcome = late.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertNull(early.error);
        assertNull(lateOutcome.error);
        assertEquals(1, refreshes.get());
        assertEquals(2, newCalls.get());
        assertEquals(2, oldCalls.get());
    }


    @Test
    public void callImpulseLateFailureSkipsSecondRefresh() throws Exception {
        AtomicInteger oldCalls = new AtomicInteger();
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger newCalls = new AtomicInteger();
        server.setDispatcher(lateFailureDispatcher(oldCalls, refreshes, newCalls));
        ExecutorService pool = Executors.newFixedThreadPool(1);
        Future<byte[]> late = pool.submit(new Callable<byte[]>() {
            @Override
            public byte[] call() throws Exception {
                return client.callImpulse(TokenPath, new byte[0]);
            }
        });
        awaitRequests(1);

        assertArrayEquals(utf8("t"), client.callImpulse(TokenPath, new byte[0]));
        assertArrayEquals(utf8("t"), late.get(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(1, refreshes.get());
    }


    @Test
    public void cancelBeforeExecuteSendsNothing() throws Exception {
        Call call = client.prepare(resetAuthorizations());
        call.cancel();
        try {
            client.execute(call);
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals("Canceled", expected.getMessage());
        }
        assertEquals(0, server.getRequestCount());
    }


    @Test
    public void cancelDuringFirstAttemptAborts() throws Exception {
        server.enqueue(ok(new byte[0]).setHeadersDelay(3, TimeUnit.SECONDS));
        ExecutorService pool = Executors.newFixedThreadPool(1);
        Call call = client.prepare(resetAuthorizations());
        Future<RpcOutcome> running = executeInBackground(pool, call);
        awaitRequests(1);

        call.cancel();

        try {
            running.get(2, TimeUnit.SECONDS);
            fail("expected cancellation");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IOException);
            assertEquals("Canceled", e.getCause().getMessage());
        }
        pool.shutdown();
    }


    @Test
    public void cancelDuringRefreshNeverSendsRetry() throws Exception {
        server.enqueue(error(16, "AUTH_KEY_UNREGISTERED"));
        server.enqueue(ok(refreshResponse("new", "r2", 5L)).setHeadersDelay(800, TimeUnit.MILLISECONDS));
        ExecutorService pool = Executors.newFixedThreadPool(1);
        Call call = client.prepare(resetAuthorizations());
        Future<RpcOutcome> running = executeInBackground(pool, call);
        awaitRequests(2);

        call.cancel();

        try {
            running.get(5, TimeUnit.SECONDS);
            fail("expected cancellation");
        } catch (ExecutionException e) {
            assertEquals("Canceled", e.getCause().getMessage());
        }
        pool.shutdown();
        assertEquals(2, server.getRequestCount());
    }


    @Test
    public void cancelReachesTheRetryAttempt() throws Exception {
        server.enqueue(error(16, "AUTH_KEY_UNREGISTERED"));
        server.enqueue(ok(refreshResponse("new", "r2", 5L)));
        server.enqueue(ok(new byte[0]).setHeadersDelay(3, TimeUnit.SECONDS));
        ExecutorService pool = Executors.newFixedThreadPool(1);
        Call call = client.prepare(resetAuthorizations());
        Future<RpcOutcome> running = executeInBackground(pool, call);
        awaitRequests(3);

        call.cancel();

        try {
            running.get(2, TimeUnit.SECONDS);
            fail("expected cancellation");
        } catch (ExecutionException e) {
            assertEquals("Canceled", e.getCause().getMessage());
        }
        pool.shutdown();
    }


    @Test
    public void callImpulseForceLogoutThrowsSessionLost() throws Exception {
        server.enqueue(error(16, "SESSION_REVOKED"));
        try {
            client.callImpulse(TokenPath, new byte[0]);
            fail("expected SessionLostException");
        } catch (SessionLostException expected) {
            assertEquals("SESSION_REVOKED", expected.error.text);
        }
    }


    @Test
    public void callImpulseFailedRefreshThrowsSessionLost() throws Exception {
        server.enqueue(error(16, "AUTH_KEY_UNREGISTERED"));
        server.enqueue(error(3, "AUTH_TOKEN_INVALID"));
        try {
            client.callImpulse(TokenPath, new byte[0]);
            fail("expected SessionLostException");
        } catch (SessionLostException expected) {
            assertNull(store.tokens);
        }
    }


    @Test
    public void refreshKeepsActivePendingToken() throws Exception {
        server.enqueue(error(16, "AUTH_KEY_UNREGISTERED"));
        server.enqueue(ok(refreshResponse("new", "r2", 5L)));
        server.enqueue(ok(new byte[0]));
        tokens.setPendingToken("pend");

        RpcOutcome outcome = client.callBlocking(resetAuthorizations());

        assertNull(outcome.error);
        assertEquals("pend", tokens.bearer());
        assertEquals("new", store.tokens.accessToken);
    }


    @Test
    public void callImpulseRefreshesAnExpiredTokenBeforeSending() throws Exception {
        store.tokens = new SessionTokens("h.eyJleHAiOjE2OTk5OTAwMDB9.s", "r1", 5L);
        build();
        server.enqueue(ok(refreshResponse("new", "r2", 5L)));
        server.enqueue(ok(utf8("x")));

        assertArrayEquals(utf8("x"), client.callImpulse(TokenPath, new byte[0]));

        assertEquals("/impulse.auth.AuthService/RefreshSession", server.takeRequest().getPath());
        RecordedRequest request = server.takeRequest();
        assertEquals(TokenPath, request.getPath());
        assertEquals("Bearer new", request.getHeader("authorization"));
    }


    @Test
    public void callImpulseSessionLostWhenExpiredTokenRefreshIsRejected() throws Exception {
        store.tokens = new SessionTokens("h.eyJleHAiOjE2OTk5OTAwMDB9.s", "r1", 5L);
        build();
        server.enqueue(error(16, "SESSION_EXPIRED"));
        try {
            client.callImpulse(TokenPath, new byte[0]);
            fail("expected SessionLostException");
        } catch (SessionLostException expected) {
            assertEquals(1, server.getRequestCount());
        }
    }
}
