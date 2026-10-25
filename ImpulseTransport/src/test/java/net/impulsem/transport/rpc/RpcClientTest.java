package net.impulsem.transport.rpc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.centrifugal.centrifuge.ReplyError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.impulsem.transport.auth.Clock;
import net.impulsem.transport.auth.Credentials;
import net.impulsem.transport.auth.IdentityManager;
import net.impulsem.transport.auth.ImpulseRpcPaths;
import net.impulsem.transport.auth.SessionStore;
import net.impulsem.transport.auth.SessionTokens;
import net.impulsem.transport.auth.TestIdentity;
import net.impulsem.transport.auth.TokenManager;
import net.impulsem.transport.codec.Transcoder;
import net.impulsem.transport.live.TlBuilder;
import net.impulsem.transport.schema.TlProtoSchema;
import net.impulsem.transport.wire.ProtoWriter;
import net.impulsem.transport.wire.TlReader;
import net.impulsem.transport.wire.TlWriter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class RpcClientTest {

    private static final int BoolTrue = 0x997275b5;
    private static final int ResetAuthorizationsId = -1616179942;
    private static final int AuthSignInId = -1923962543;
    private static final int InvokeWithTakeoutId = -1398145746;
    private static final int AuthorizationId = 782418132;
    private static final int GetContactIdsId = 2061264541;
    private static final int ServerCode = 100;
    private static final long CallTimeoutMillis = 30_000L;
    private static final long MediaCallTimeoutMillis = 10_000L;
    private static final long SettleDelayMillis = 800L;
    private static final long IdentityExpSeconds = 1_700_003_600L;

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


    /** Records each wait and each invalidation; a wait notes how many carrier calls had gone out before it. */
    private static final class RecordingGate implements RpcClient.DeclarationGate {

        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        private final FakeCarrier carrier;


        RecordingGate(FakeCarrier carrier) {
            this.carrier = carrier;
        }


        @Override
        public boolean awaitDeclared(long timeoutMillis) {
            events.add("await@" + carrier.callCount());
            return true;
        }


        @Override
        public void invalidate() {
            events.add("invalidate");
        }
    }


    private MemoryStore store;
    private TokenManager tokens;
    private IdentityManager identity;
    private FakeCarrier update;
    private FakeCarrier media;
    private FakeCarrier pool;
    private RpcClient client;


    @Before
    public void setUp() throws IOException {
        store = new MemoryStore();
        store.tokens = new SessionTokens("old", "r1", 5L);
        update = new FakeCarrier();
        media = new FakeCarrier();
        pool = new FakeCarrier();
        build();
    }


    @After
    public void tearDown() throws IOException {
        update.shutdown();
        media.shutdown();
        pool.shutdown();
    }


    private void build() {
        build(media);
    }


    private void build(RpcCarrier mediaFleet) {
        Clock clock = new Clock() {
            @Override
            public long nowMillis() {
                return 1_700_000_000_000L;
            }
        };
        tokens = new TokenManager(store, clock);
        tokens.setRefreshCarriers(update, pool);
        identity = TestIdentity.manager(pool, clock);
        client = new RpcClient(
            new Transcoder(TlProtoSchema.load()),
            update,
            mediaFleet,
            tokens,
            new Credentials(tokens, identity)
        );
    }


    private static byte[] resetAuthorizations() {
        TlWriter writer = new TlWriter();
        writer.writeInt32(ResetAuthorizationsId);
        return writer.toByteArray();
    }


    /** auth.cancelCode, a login method the identity socket may send; its answer is a Bool like resetAuthorizations. */
    private static byte[] cancelCode() {
        return TlBuilder.method("auth.cancelCode")
            .put("phone_number", "+99966")
            .put("phone_code_hash", "hash")
            .toBytes();
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


    private static byte[] getFile() {
        return TlBuilder.method("upload.getFile")
            .put("location", TlBuilder.object("inputDocumentFileLocation")
                .put("id", 1L)
                .put("access_hash", 2L)
                .put("file_reference", new byte[0])
                .put("thumb_size", ""))
            .put("offset", 0L)
            .put("limit", 1024)
            .toBytes();
    }


    private static byte[] bool() {
        TlWriter writer = new TlWriter();
        writer.writeInt32(BoolTrue);
        return writer.toByteArray();
    }


    /** BoolResponse{value = true}. */
    private static byte[] boolValue() {
        ProtoWriter writer = new ProtoWriter();
        writer.writeVarintField(1, 1);
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


    /** impulse.auth.RefreshSessionResponse, the RefreshSession answer. */
    private static byte[] refreshResponse(
        String access,
        String refresh,
        long userId
    ) {
        ProtoWriter writer = new ProtoWriter();
        writer.writeVarintField(1, userId);
        writer.writeBytesField(2, utf8(access));
        writer.writeBytesField(3, utf8(refresh));
        return writer.toByteArray();
    }


    /** An app error as the server reports it: the Telegram code and text ride in the app fields. */
    private static ReplyError appError(
        int appCode,
        String appType
    ) {
        return new ReplyError(ServerCode, appType, false, appCode, appType, Collections.<String, String>emptyMap());
    }


    private static ReplyError sdkError(int sdkCode) {
        return new ReplyError(sdkCode, "sdk", false);
    }


    private static void awaitCalls(
        FakeCarrier carrier,
        int count
    ) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (carrier.callCount() < count) {
            if (System.currentTimeMillis() > deadline) {
                fail("carrier never saw call " + count);
            }
            Thread.sleep(10);
        }
    }


    /** The methods of the next count rpcs the carrier was asked to send, in order. */
    private static List<String> methodsSent(
        FakeCarrier carrier,
        int count
    ) throws InterruptedException {
        List<String> methods = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            methods.add(carrier.takeSent().method);
        }
        return methods;
    }


    /** Answers each call of the inner carrier only after a delay, so a test can act while the call is in flight. */
    private static RpcCarrier settledLater(
        final RpcCarrier inner,
        final long delayMillis
    ) {
        return new RpcCarrier() {
            @Override
            public CarrierCall call(
                String method,
                byte[] data,
                long timeoutMillis,
                final CarrierCallback callback
            ) {
                return inner.call(method, data, timeoutMillis, new CarrierCallback() {
                    @Override
                    public void onReply(byte[] reply) {
                        pause(delayMillis);
                        callback.onReply(reply);
                    }


                    @Override
                    public void onFailure(CarrierFailure failure) {
                        pause(delayMillis);
                        callback.onFailure(failure);
                    }
                });
            }
        };
    }


    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    private Future<RpcOutcome> executeInBackground(
        ExecutorService pool,
        final PreparedCall call
    ) {
        return pool.submit(new Callable<RpcOutcome>() {
            @Override
            public RpcOutcome call() throws Exception {
                return client.execute(call);
            }
        });
    }


    // ---------------------------------------------------------------- TL calls

    @Test
    public void boolReplyDecodesOnUpdateSocket() throws Exception {
        update.reply(boolValue());

        RpcOutcome outcome = client.callBlocking(resetAuthorizations());

        assertNull(outcome.error);
        assertFalse(outcome.forceLogout);
        assertArrayEquals(bool(), outcome.tlResult);
        FakeCarrier.Sent sent = update.takeSent();
        assertEquals("auth.resetAuthorizations", sent.method);
        assertEquals(CallTimeoutMillis, sent.timeoutMillis);
    }


    @Test
    public void mediaMethodGoesToTheMediaFleet() throws Exception {
        media.fail(appError(400, "LOCATION_INVALID"));

        RpcOutcome outcome = client.callBlocking(getFile());

        assertEquals(400, outcome.error.code);
        assertEquals("LOCATION_INVALID", outcome.error.text);
        FakeCarrier.Sent sent = media.takeSent();
        assertEquals("upload.getFile", sent.method);
        assertEquals(MediaCallTimeoutMillis, sent.timeoutMillis);
        assertEquals(0, update.callCount());
    }


    @Test
    public void mediaMethodWithoutMediaFleetThrows() throws Exception {
        build(null);
        try {
            client.callBlocking(getFile());
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("upload.getFile"));
        }
        assertEquals(0, update.callCount());
    }


    @Test
    public void appErrorReturnsItsCodeAndText() throws Exception {
        update.fail(appError(420, "FLOOD_WAIT_7"));

        RpcOutcome outcome = client.callBlocking(resetAuthorizations());

        assertNull(outcome.tlResult);
        assertEquals(420, outcome.error.code);
        assertEquals("FLOOD_WAIT_7", outcome.error.text);
        assertFalse(outcome.forceLogout);
    }


    @Test
    public void carrierIOExceptionThrows() throws Exception {
        update.fail(new IOException("connection lost"));
        try {
            client.callBlocking(resetAuthorizations());
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals("connection lost", expected.getCause().getMessage());
        }
    }


    @Test
    public void carrierTimeoutThrows() throws Exception {
        update.fail(new TimeoutException("timed out"));
        try {
            client.callBlocking(resetAuthorizations());
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getCause() instanceof TimeoutException);
        }
    }


    @Test
    public void authKeyUnregisteredRefreshesAndRetries() throws Exception {
        update.fail(appError(401, "AUTH_KEY_UNREGISTERED"));
        update.reply(refreshResponse("new", "r2", 5L));
        update.reply(boolValue());

        RpcOutcome outcome = client.callBlocking(resetAuthorizations());

        assertNull(outcome.error);
        assertFalse(outcome.forceLogout);
        assertArrayEquals(bool(), outcome.tlResult);
        assertEquals(3, update.callCount());
        assertEquals(
            Arrays.asList("auth.resetAuthorizations", ImpulseRpcPaths.RefreshSessionMethod, "auth.resetAuthorizations"),
            methodsSent(update, 3)
        );
        assertEquals("new", store.tokens.accessToken);
        assertEquals("r2", store.tokens.refreshToken);
    }


    @Test
    public void persistentAuthKeyUnregisteredAfterRefreshForcesLogout() throws Exception {
        update.fail(appError(401, "AUTH_KEY_UNREGISTERED"));
        update.reply(refreshResponse("new", "r2", 5L));
        update.fail(appError(401, "AUTH_KEY_UNREGISTERED"));

        RpcOutcome outcome = client.callBlocking(resetAuthorizations());

        assertTrue(outcome.forceLogout);
        assertEquals("AUTH_KEY_UNREGISTERED", outcome.error.text);
        assertEquals(3, update.callCount());
        assertEquals(
            Arrays.asList("auth.resetAuthorizations", ImpulseRpcPaths.RefreshSessionMethod, "auth.resetAuthorizations"),
            methodsSent(update, 3)
        );
    }


    /** The socket refused the access token: the refresh goes through the pool fleet, not the socket. */
    @Test
    public void signedInTokenRefusalMarksTheTokenAndRefreshesThroughThePool() throws Exception {
        update.fail(sdkError(109));
        pool.reply(refreshResponse("new", "r2", 5L));
        update.reply(boolValue());

        RpcOutcome outcome = client.callBlocking(resetAuthorizations());

        assertNull(outcome.error);
        assertArrayEquals(bool(), outcome.tlResult);
        assertEquals(2, update.callCount());
        assertEquals(Arrays.asList("auth.resetAuthorizations", "auth.resetAuthorizations"), methodsSent(update, 2));
        assertEquals(1, pool.callCount());
        assertEquals(ImpulseRpcPaths.RefreshSessionMethod, pool.takeSent().method);
        assertEquals("new", tokens.bearer());
    }


    @Test
    public void tokenRefusal103TwiceForcesLogout() throws Exception {
        update.fail(sdkError(103));
        pool.reply(refreshResponse("new", "r2", 5L));
        update.fail(sdkError(103));

        RpcOutcome outcome = client.callBlocking(resetAuthorizations());

        assertTrue(outcome.forceLogout);
        assertNull(outcome.tlResult);
        assertEquals(2, update.callCount());
        assertEquals(Arrays.asList("auth.resetAuthorizations", "auth.resetAuthorizations"), methodsSent(update, 2));
        assertEquals(1, pool.callCount());
        assertEquals(ImpulseRpcPaths.RefreshSessionMethod, pool.takeSent().method);
    }


    @Test
    public void failedRefreshForcesLogout() throws Exception {
        update.fail(appError(401, "AUTH_KEY_UNREGISTERED"));
        update.fail(appError(400, "AUTH_TOKEN_INVALID"));

        RpcOutcome outcome = client.callBlocking(resetAuthorizations());

        assertTrue(outcome.forceLogout);
        assertNull(outcome.tlResult);
        assertNull(store.tokens);
        assertEquals(2, update.callCount());
    }


    @Test
    public void sessionRevokedForcesLogout() throws Exception {
        update.fail(appError(401, "SESSION_REVOKED"));

        RpcOutcome outcome = client.callBlocking(resetAuthorizations());

        assertTrue(outcome.forceLogout);
        assertEquals("SESSION_REVOKED", outcome.error.text);
        assertEquals(401, outcome.error.code);
        assertEquals(0, pool.callCount());
        assertEquals(1, update.callCount());
    }


    @Test
    public void refreshWithoutUserIdFailsAndKeepsTheSession() throws Exception {
        update.fail(appError(401, "AUTH_KEY_UNREGISTERED"));
        update.reply(refreshResponse("new", "r2", 0L));

        try {
            client.callBlocking(resetAuthorizations());
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals("old", store.tokens.accessToken);
            assertEquals(5L, store.tokens.userId);
        }
    }


    @Test
    public void signInResponsePersistsTokensAndUserId() throws Exception {
        store.tokens = null;
        build();
        update.reply(authorization("acc", "ref", 777L));

        RpcOutcome outcome = client.callBlocking(signIn());

        assertNull(outcome.error);
        assertNotNull(outcome.tlResult);
        assertEquals(AuthorizationId, new TlReader(outcome.tlResult, 0, outcome.tlResult.length).readInt32());
        assertEquals("acc", store.tokens.accessToken);
        assertEquals("ref", store.tokens.refreshToken);
        assertEquals(777L, store.tokens.userId);
        assertEquals("acc", tokens.bearer());
    }


    @Test
    public void signInResponseWithoutUserFailsAndStoresNoTokens() throws Exception {
        store.tokens = null;
        build();
        update.reply(authorization("acc", "ref", 0L));

        RpcOutcome outcome = client.callBlocking(signIn());

        assertNull(outcome.tlResult);
        assertEquals("TRANSCODE_FAILED", outcome.error.text);
        assertNull(store.tokens);
    }


    @Test
    public void undecodableReplyIsTranscodeFailedNamingTheMethod() throws Exception {
        update.reply(new byte[] {(byte) 0x0A, (byte) 0x7F, 1, 2});
        TlWriter request = new TlWriter();
        request.writeInt32(GetContactIdsId);
        request.writeInt64(0L);

        RpcOutcome outcome = client.callBlocking(request.toByteArray());

        assertNull(outcome.tlResult);
        assertFalse(outcome.forceLogout);
        assertEquals(400, outcome.error.code);
        assertEquals("TRANSCODE_FAILED", outcome.error.text);
        assertTrue(outcome.transcodeDetail, outcome.transcodeDetail.startsWith("contacts.getContactIDs"));
        assertEquals(1, update.callCount());
    }


    @Test
    public void unmappedMethodReturnsMethodInvalidWithoutCarrierCall() throws Exception {
        TlWriter writer = new TlWriter();
        writer.writeInt32(0x12345678);

        RpcOutcome outcome = client.callBlocking(writer.toByteArray());

        assertNull(outcome.tlResult);
        assertEquals(400, outcome.error.code);
        assertEquals("METHOD_INVALID", outcome.error.text);
        assertEquals(0, update.callCount());
    }


    @Test
    public void takeoutCallReturnsTakeoutInvalidWithoutCarrierCall() throws Exception {
        TlWriter writer = new TlWriter();
        writer.writeInt32(InvokeWithTakeoutId);
        writer.writeInt64(555L);
        writer.writeRaw(resetAuthorizations());

        RpcOutcome outcome = client.callBlocking(writer.toByteArray());

        assertNull(outcome.tlResult);
        assertEquals(400, outcome.error.code);
        assertEquals("TAKEOUT_INVALID", outcome.error.text);
        assertEquals(0, update.callCount());
        assertEquals(0, pool.callCount());
    }


    // ---------------------------------------------------------------- cancellation

    @Test
    public void cancelWhileHeldThrowsCanceled() throws Exception {
        PreparedCall call = client.prepare(resetAuthorizations());
        ExecutorService threadPool = Executors.newFixedThreadPool(1);
        Future<RpcOutcome> running = executeInBackground(threadPool, call);
        awaitCalls(update, 1);

        call.cancel();

        try {
            running.get(5, TimeUnit.SECONDS);
            fail("expected cancellation");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IOException);
            assertEquals("Canceled", e.getCause().getMessage());
        } finally {
            threadPool.shutdown();
        }
    }


    @Test
    public void cancelBeforeExecuteSendsNothing() throws Exception {
        PreparedCall call = client.prepare(resetAuthorizations());
        call.cancel();
        try {
            client.execute(call);
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals("Canceled", expected.getMessage());
        }
        assertEquals(0, update.callCount());
    }


    @Test
    public void cancelDuringRefreshNeverSendsRetry() throws Exception {
        update.fail(appError(401, "AUTH_KEY_UNREGISTERED"));
        update.reply(refreshResponse("new", "r2", 5L));
        tokens.setRefreshCarriers(settledLater(update, SettleDelayMillis), pool);
        ExecutorService threadPool = Executors.newFixedThreadPool(1);
        PreparedCall call = client.prepare(resetAuthorizations());
        Future<RpcOutcome> running = executeInBackground(threadPool, call);
        awaitCalls(update, 2);

        call.cancel();

        try {
            running.get(5, TimeUnit.SECONDS);
            fail("expected cancellation");
        } catch (ExecutionException e) {
            assertEquals("Canceled", e.getCause().getMessage());
        } finally {
            threadPool.shutdown();
        }
        assertEquals(2, update.callCount());
        assertEquals(
            Arrays.asList("auth.resetAuthorizations", ImpulseRpcPaths.RefreshSessionMethod),
            methodsSent(update, 2)
        );
    }


    @Test
    public void cancelReachesTheRetryAttempt() throws Exception {
        update.fail(appError(401, "AUTH_KEY_UNREGISTERED"));
        update.reply(refreshResponse("new", "r2", 5L));
        update.hold();
        ExecutorService threadPool = Executors.newFixedThreadPool(1);
        PreparedCall call = client.prepare(resetAuthorizations());
        Future<RpcOutcome> running = executeInBackground(threadPool, call);
        awaitCalls(update, 3);

        call.cancel();

        try {
            running.get(5, TimeUnit.SECONDS);
            fail("expected cancellation");
        } catch (ExecutionException e) {
            assertEquals("Canceled", e.getCause().getMessage());
        } finally {
            threadPool.shutdown();
        }
    }


    // ---------------------------------------------------------------- named impulse calls

    @Test
    public void callNamedReturnsTheReplyBytes() throws Exception {
        update.reply(utf8("payload"));

        byte[] body = client.callNamed("initConnection", new byte[] {7});

        assertArrayEquals(utf8("payload"), body);
        FakeCarrier.Sent sent = update.takeSent();
        assertEquals("initConnection", sent.method);
        assertArrayEquals(new byte[] {7}, sent.data);
        assertEquals(CallTimeoutMillis, sent.timeoutMillis);
    }


    @Test
    public void callNamedSessionRevokedThrowsSessionLost() throws Exception {
        update.fail(appError(401, "SESSION_REVOKED"));
        try {
            client.callNamed("initConnection", new byte[0]);
            fail("expected SessionLostException");
        } catch (SessionLostException expected) {
            assertEquals("SESSION_REVOKED", expected.error.text);
        }
    }


    @Test
    public void callNamedFloodWaitThrowsRpcStatus() throws Exception {
        update.fail(appError(420, "FLOOD_WAIT_3"));
        try {
            client.callNamed("initConnection", new byte[0]);
            fail("expected RpcStatusException");
        } catch (RpcStatusException expected) {
            assertEquals("FLOOD_WAIT_3", expected.error.text);
            assertEquals(420, expected.error.code);
        }
    }


    @Test
    public void callNamedRefreshesAndRetries() throws Exception {
        update.fail(appError(401, "AUTH_KEY_UNREGISTERED"));
        update.reply(refreshResponse("new", "r2", 5L));
        update.reply(utf8("x"));

        assertArrayEquals(utf8("x"), client.callNamed("initConnection", new byte[0]));

        assertEquals(3, update.callCount());
        assertEquals(
            Arrays.asList("initConnection", ImpulseRpcPaths.RefreshSessionMethod, "initConnection"),
            methodsSent(update, 3)
        );
    }


    @Test
    public void callNamedPersistentAuthKeyUnregisteredThrowsSessionLost() throws Exception {
        update.fail(appError(401, "AUTH_KEY_UNREGISTERED"));
        update.reply(refreshResponse("new", "r2", 5L));
        update.fail(appError(401, "AUTH_KEY_UNREGISTERED"));
        try {
            client.callNamed("initConnection", new byte[0]);
            fail("expected SessionLostException");
        } catch (SessionLostException expected) {
            assertEquals("AUTH_KEY_UNREGISTERED", expected.error.text);
        }
    }


    @Test
    public void callNamedFailedRefreshThrowsSessionLost() throws Exception {
        update.fail(appError(401, "AUTH_KEY_UNREGISTERED"));
        update.fail(appError(400, "AUTH_TOKEN_INVALID"));
        try {
            client.callNamed("initConnection", new byte[0]);
            fail("expected SessionLostException");
        } catch (SessionLostException expected) {
            assertNull(store.tokens);
        }
    }


    @Test
    public void callNamedRefreshesAnExpiredTokenBeforeSending() throws Exception {
        store.tokens = new SessionTokens("h.eyJleHAiOjE2OTk5OTAwMDB9.s", "r1", 5L);
        build();
        pool.reply(refreshResponse("new", "r2", 5L));
        update.reply(utf8("x"));

        assertArrayEquals(utf8("x"), client.callNamed("initConnection", new byte[0]));

        assertEquals(ImpulseRpcPaths.RefreshSessionMethod, pool.takeSent().method);
        assertEquals(1, pool.callCount());
        assertEquals("initConnection", update.takeSent().method);
        assertEquals(1, update.callCount());
    }


    @Test
    public void callNamedSessionLostWhenExpiredTokenRefreshIsRejected() throws Exception {
        store.tokens = new SessionTokens("h.eyJleHAiOjE2OTk5OTAwMDB9.s", "r1", 5L);
        build();
        pool.fail(appError(401, "SESSION_EXPIRED"));
        try {
            client.callNamed("initConnection", new byte[0]);
            fail("expected SessionLostException");
        } catch (SessionLostException expected) {
            assertEquals(1, pool.callCount());
            assertEquals(0, update.callCount());
        }
    }


    // ---------------------------------------------------------------- method guard, declaration gate, identity

    @Test
    public void identitySocketRefusesAUserMethodLocally() throws Exception {
        store.tokens = null;
        build();
        TlWriter writer = new TlWriter();
        writer.writeInt32(GetContactIdsId);
        writer.writeInt64(0L);

        RpcOutcome outcome = client.callBlocking(writer.toByteArray());

        assertNull(outcome.tlResult);
        assertEquals(400, outcome.error.code);
        assertEquals("METHOD_INVALID", outcome.error.text);
        assertFalse(outcome.forceLogout);
        assertEquals(0, update.callCount());
    }


    @Test
    public void sessionSocketRefusesALoginOnlyMethodLocally() throws Exception {
        RpcOutcome outcome = client.callBlocking(signIn());

        assertNull(outcome.tlResult);
        assertEquals("METHOD_INVALID", outcome.error.text);
        assertEquals(0, update.callCount());
    }


    @Test
    public void callsWaitForTheDeclarationGate() throws Exception {
        RecordingGate gate = new RecordingGate(update);
        client.setDeclarationGate(gate);
        update.reply(boolValue());

        RpcOutcome outcome = client.callBlocking(resetAuthorizations());

        assertNull(outcome.error);
        assertEquals(Collections.singletonList("await@0"), gate.events);
        assertEquals(1, update.callCount());
    }


    @Test
    public void connectionNotInitedRedeclaresAndRetriesOnce() throws Exception {
        RecordingGate gate = new RecordingGate(update);
        client.setDeclarationGate(gate);
        update.fail(appError(400, "CONNECTION_NOT_INITED"));
        update.reply(boolValue());

        RpcOutcome outcome = client.callBlocking(resetAuthorizations());

        assertNull(outcome.error);
        assertArrayEquals(bool(), outcome.tlResult);
        assertEquals(Arrays.asList("await@0", "invalidate", "await@1"), gate.events);
        assertEquals(2, update.callCount());
    }


    @Test
    public void secondConnectionNotInitedIsReturnedAsTheError() throws Exception {
        RecordingGate gate = new RecordingGate(update);
        client.setDeclarationGate(gate);
        update.fail(appError(400, "CONNECTION_NOT_INITED"));
        update.fail(appError(400, "CONNECTION_NOT_INITED"));

        RpcOutcome outcome = client.callBlocking(resetAuthorizations());

        assertNull(outcome.tlResult);
        assertEquals("CONNECTION_NOT_INITED", outcome.error.text);
        assertFalse(outcome.forceLogout);
        assertEquals(Arrays.asList("await@0", "invalidate", "await@1"), gate.events);
        assertEquals(2, update.callCount());
    }


    @Test
    public void signedOutRefusalReidentifiesAndRetries() throws Exception {
        store.tokens = null;
        build();
        final List<String> events = new ArrayList<String>();
        identity.setListener(new IdentityManager.Listener() {
            @Override
            public void onIdentityChanged() {
                events.add("changed");
            }


            @Override
            public void onIdentityRenewed(String identityToken) {
                events.add("renewed");
            }
        });
        pool.reply(TestIdentity.identityReply(TestIdentity.jwt("7", IdentityExpSeconds)));
        identity.obtainBlocking();
        update.fail(sdkError(103));
        update.reply(boolValue());

        RpcOutcome outcome = client.callBlocking(cancelCode());

        assertNull(outcome.error);
        assertArrayEquals(bool(), outcome.tlResult);
        assertEquals(Collections.singletonList("changed"), events);
        assertEquals(2, update.callCount());
    }


    @Test
    public void signedOutSecondRefusalIsReturnedWithoutForceLogout() throws Exception {
        store.tokens = null;
        build();
        pool.reply(TestIdentity.identityReply(TestIdentity.jwt("7", IdentityExpSeconds)));
        identity.obtainBlocking();
        update.fail(sdkError(103));
        update.fail(sdkError(103));

        RpcOutcome outcome = client.callBlocking(cancelCode());

        assertNull(outcome.tlResult);
        assertEquals("AUTH_TOKEN_INVALID", outcome.error.text);
        assertFalse(outcome.forceLogout);
        assertEquals(2, update.callCount());
    }
}
