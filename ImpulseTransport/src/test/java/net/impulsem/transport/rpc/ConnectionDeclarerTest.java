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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.impulsem.transport.auth.Clock;
import net.impulsem.transport.auth.ConnectionDeclaration;
import net.impulsem.transport.auth.Credentials;
import net.impulsem.transport.auth.IdentityManager;
import net.impulsem.transport.auth.ImpulseRpcPaths;
import net.impulsem.transport.auth.SessionStore;
import net.impulsem.transport.auth.SessionTokens;
import net.impulsem.transport.auth.TestIdentity;
import net.impulsem.transport.auth.TokenManager;
import net.impulsem.transport.codec.Transcoder;
import net.impulsem.transport.schema.TlProtoSchema;
import net.impulsem.transport.wire.ProtoReader;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class ConnectionDeclarerTest {

    private static final String TokenPath = "/impulse.sync.SyncService/GetCentrifugoToken";
    private static final long WaitSeconds = 10L;

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


    private static final class ManualDelayer implements ConnectionDeclarer.Delayer {

        final List<Runnable> tasks = new ArrayList<Runnable>();
        final List<Long> delays = new ArrayList<Long>();


        @Override
        public synchronized void schedule(
            Runnable task,
            long delayMillis
        ) {
            tasks.add(task);
            delays.add(delayMillis);
        }


        synchronized List<Runnable> takeAll() {
            List<Runnable> copy = new ArrayList<Runnable>(tasks);
            tasks.clear();
            return copy;
        }
    }


    /**
     * Holds the initConnection call without answering it, so the test can settle it later from its own thread.
     * Every other method goes to the fake carrier. A shared script queue would hand a reply meant for one call to
     * whichever call polled first.
     */
    private static final class InitConnectionGate implements RpcCarrier {

        final CountDownLatch arrived = new CountDownLatch(1);
        final RpcCarrier fake;
        volatile CarrierCallback pendingInit;


        InitConnectionGate(RpcCarrier fake) {
            this.fake = fake;
        }


        @Override
        public CarrierCall call(
            String method,
            byte[] data,
            long timeoutMillis,
            CarrierCallback callback
        ) {
            if (!ImpulseRpcPaths.InitConnectionMethod.equals(method)) {
                return fake.call(method, data, timeoutMillis, callback);
            }
            pendingInit = callback;
            arrived.countDown();
            return new CarrierCall() {
                @Override
                public void cancel() {
                }
            };
        }
    }


    private static final class Result {

        final String outcome;
        final int attempt;
        final int code;


        Result(
            String outcome,
            int attempt,
            int code
        ) {
            this.outcome = outcome;
            this.attempt = attempt;
            this.code = code;
        }
    }


    private final BlockingQueue<Result> results = new LinkedBlockingQueue<Result>();
    private final ManualDelayer delayer = new ManualDelayer();
    private final ConnectionDeclaration declaration = new ConnectionDeclaration(
        3,
        "HUAWEI MNA-LX9",
        "SDK 35",
        "12.10.6-beta.7 (10007)",
        "en",
        "android",
        "en"
    );

    private FakeCarrier pool;
    private MemoryStore store;
    private TokenManager tokens;
    private IdentityManager identity;
    private FakeCarrier carrier;
    private RpcClient rpc;
    private ExecutorService executor;
    private ConnectionDeclarer declarer;


    @Before
    public void setUp() throws IOException {
        pool = new FakeCarrier();
        store = new MemoryStore();
        store.tokens = new SessionTokens("old", "r1", 5L);
        executor = Executors.newSingleThreadExecutor();
        build();
    }


    @After
    public void tearDown() throws IOException {
        executor.shutdownNow();
        carrier.shutdown();
        pool.shutdown();
    }


    private void build() {
        if (carrier != null) {
            carrier.shutdown();
        }
        carrier = new FakeCarrier();
        buildOn(carrier);
    }


    /** Builds the declarer on the given update-socket carrier; refreshes would go over it and the pool fleet. */
    private void buildOn(RpcCarrier updateSocket) {
        Clock clock = new Clock() {
            @Override
            public long nowMillis() {
                return 1_700_000_000_000L;
            }
        };
        tokens = new TokenManager(store, clock);
        tokens.setRefreshCarriers(updateSocket, pool);
        identity = TestIdentity.manager(pool, clock);
        Credentials credentials = new Credentials(tokens, identity);
        rpc = new RpcClient(
            new Transcoder(TlProtoSchema.load()),
            updateSocket,
            null,
            tokens,
            credentials
        );
        declarer = new ConnectionDeclarer(
            rpc,
            tokens,
            credentials,
            executor,
            delayer,
            new ConnectionDeclarer.Listener() {
                @Override
                public void onResult(
                    String outcome,
                    int attempt,
                    int code
                ) {
                    results.add(new Result(outcome, attempt, code));
                }
            }
        );
    }


    /** Runs every task already queued on the single worker, so "nothing was sent" can be asserted. */
    private void drain() throws Exception {
        executor.submit(new Runnable() {
            @Override
            public void run() {
            }
        }).get(WaitSeconds, TimeUnit.SECONDS);
    }


    private Result nextResult() throws Exception {
        Result result = results.poll(WaitSeconds, TimeUnit.SECONDS);
        assertNotNull("no INIT_CONNECTION outcome arrived", result);
        return result;
    }


    private FakeCarrier.Sent nextSent() throws Exception {
        return carrier.takeSent();
    }


    /** Reads one string field of the ConnectionDeclaration inside an InitConnectionRequest. */
    private static String declarationField(
        byte[] data,
        int field
    ) {
        ProtoReader outer = new ProtoReader(data, 0, data.length);
        assertTrue(outer.next());
        assertEquals(1, outer.field());
        byte[] inner = outer.readBytes();
        ProtoReader reader = new ProtoReader(inner, 0, inner.length);
        while (reader.next()) {
            if (reader.field() == field && reader.wireType() == 2) {
                return new String(reader.readBytes(), StandardCharsets.UTF_8);
            }
            reader.skip();
        }
        return null;
    }


    @Test
    public void sentOnceWhenTheSessionExists() throws Exception {
        carrier.reply(new byte[0]);

        declarer.setDeclaration(declaration);

        FakeCarrier.Sent sent = nextSent();
        assertEquals(ImpulseRpcPaths.InitConnectionMethod, sent.method);
        assertArrayEquals(ImpulseRpcPaths.encodeInitConnection(declaration), sent.data);
        assertEquals("HUAWEI MNA-LX9", declarationField(sent.data, 2));
        assertEquals("ok", nextResult().outcome);

        declarer.sessionChanged();
        declarer.setDeclaration(declaration);
        drain();
        assertEquals("an unchanged bearer and declaration write nothing", 1, carrier.callCount());
    }


    @Test
    public void notSentWithoutABearer() throws Exception {
        store.tokens = null;
        build();

        declarer.setDeclaration(declaration);
        declarer.sessionChanged();
        drain();

        assertEquals(0, carrier.callCount());
        assertNull(results.poll());
    }


    @Test
    public void sentOnceLoginStoresTheFirstSession() throws Exception {
        store.tokens = null;
        build();
        declarer.setDeclaration(declaration);
        drain();
        assertEquals(0, carrier.callCount());
        carrier.reply(new byte[0]);

        tokens.onLoginTokens("fresh", "r9", 7L);

        FakeCarrier.Sent sent = nextSent();
        assertEquals(ImpulseRpcPaths.InitConnectionMethod, sent.method);
        assertEquals("ok", nextResult().outcome);
    }


    @Test
    public void sentAgainWhenTheBearerChanges() throws Exception {
        carrier.reply(new byte[0]);
        carrier.reply(new byte[0]);
        declarer.setDeclaration(declaration);
        assertEquals(ImpulseRpcPaths.InitConnectionMethod, nextSent().method);
        assertEquals("ok", nextResult().outcome);

        tokens.onLoginTokens("rotated", "r2", 5L);

        assertEquals(ImpulseRpcPaths.InitConnectionMethod, nextSent().method);
        assertEquals("ok", nextResult().outcome);
        drain();
        assertEquals(2, carrier.callCount());
    }


    @Test
    public void languageChangeResendsWithTheNewLangCode() throws Exception {
        carrier.reply(new byte[0]);
        carrier.reply(new byte[0]);
        declarer.setDeclaration(declaration);
        assertEquals("en", declarationField(nextSent().data, 7));
        assertEquals("ok", nextResult().outcome);

        declarer.setLangCode("ru");

        FakeCarrier.Sent sent = nextSent();
        assertEquals("ru", declarationField(sent.data, 7));
        assertEquals("ok", nextResult().outcome);
        declarer.setLangCode("ru");
        drain();
        assertEquals("the same language again writes nothing", 2, carrier.callCount());
    }


    @Test
    public void languageChangeBeforeTheDeclarationIsNotLost() throws Exception {
        carrier.reply(new byte[0]);

        declarer.setLangCode("ru");
        declarer.setDeclaration(declaration);

        FakeCarrier.Sent sent = nextSent();
        assertEquals("ru", declarationField(sent.data, 7));
        assertEquals("ok", nextResult().outcome);
        drain();
        assertEquals(1, carrier.callCount());
    }


    @Test
    public void systemLanguageChangeResendsToo() throws Exception {
        carrier.reply(new byte[0]);
        carrier.reply(new byte[0]);
        declarer.setDeclaration(declaration);
        nextSent();
        nextResult();

        declarer.setSystemLangCode("de");

        assertEquals("de", declarationField(nextSent().data, 5));
    }


    @Test
    public void aFailureDoesNotBlockAConcurrentRpc() throws Exception {
        InitConnectionGate gate = new InitConnectionGate(carrier);
        buildOn(gate);
        carrier.reply("payload".getBytes(StandardCharsets.UTF_8));

        declarer.setDeclaration(declaration);
        assertTrue(gate.arrived.await(WaitSeconds, TimeUnit.SECONDS));

        byte[] body = rpc.callNamed(TokenPath, new byte[0]);
        assertEquals("payload", new String(body, StandardCharsets.UTF_8));
        assertNull("the declaration is still in flight", results.poll());

        gate.pendingInit.onFailure(CarrierFailure.of(
            new ReplyError(100, "", false, 500, "INTERNAL_BROKEN", Collections.<String, String>emptyMap())
        ));
        assertTrue(nextResult().outcome.length() > 0);
    }


    @Test
    public void aTransientFailureIsRetriedAndThenGivenUpQuietly() throws Exception {
        for (int a = 0; a < 10; a++) {
            carrier.fail(new IOException("lost"));
        }
        declarer.setDeclaration(declaration);

        int rounds = 0;
        while (rounds < 10) {
            Result result = nextResult();
            if ("gave_up".equals(result.outcome)) {
                break;
            }
            assertEquals("retry", result.outcome);
            List<Runnable> due = delayer.takeAll();
            assertEquals("exactly one retry is scheduled", 1, due.size());
            due.get(0).run();
            rounds++;
        }

        assertTrue("it must stop after a few attempts", carrier.callCount() <= 4);
        assertTrue(carrier.callCount() >= 2);
        drain();
        assertEquals(0, delayer.takeAll().size());
        int requests = carrier.callCount();
        drain();
        assertEquals("no request after giving up", requests, carrier.callCount());
    }


    @Test
    public void aPermanentErrorIsNotRetried() throws Exception {
        carrier.fail(new ReplyError(100, "", false, 400, "API_ID_INVALID", Collections.<String, String>emptyMap()));

        declarer.setDeclaration(declaration);

        Result result = nextResult();
        assertEquals("gave_up", result.outcome);
        assertEquals(400, result.code);
        drain();
        assertEquals(1, carrier.callCount());
        assertEquals(0, delayer.takeAll().size());
    }


    @Test
    public void aPendingRetryIsDroppedWhenANewTriggerSupersedesIt() throws Exception {
        carrier.fail(new IOException("lost"));
        carrier.reply(new byte[0]);
        declarer.setDeclaration(declaration);
        assertEquals("retry", nextResult().outcome);
        List<Runnable> stale = delayer.takeAll();
        assertEquals(1, stale.size());

        tokens.onLoginTokens("rotated", "r2", 5L);
        assertEquals("ok", nextResult().outcome);
        stale.get(0).run();
        drain();

        assertEquals("the stale retry sends nothing", 2, carrier.callCount());
    }


    @Test
    public void signedOutConnectionIsDeclaredWithTheIdentity() throws Exception {
        store.tokens = null;
        build();
        pool.reply(TestIdentity.identityReply(TestIdentity.jwt("identity-1", 1_700_003_600L)));
        identity.obtainBlocking();
        carrier.reply(new byte[0]);

        declarer.setDeclaration(declaration);

        FakeCarrier.Sent sent = nextSent();
        assertEquals(ImpulseRpcPaths.InitConnectionMethod, sent.method);
        assertArrayEquals(ImpulseRpcPaths.encodeInitConnection(declaration), sent.data);
        assertEquals("ok", nextResult().outcome);
        drain();
        assertEquals(1, carrier.callCount());
    }


    @Test
    public void aCredentialChangeDeclaresAgain() throws Exception {
        store.tokens = null;
        build();
        pool.reply(TestIdentity.identityReply(TestIdentity.jwt("identity-1", 1_700_003_600L)));
        identity.obtainBlocking();
        carrier.reply(new byte[0]);
        carrier.reply(new byte[0]);
        declarer.setDeclaration(declaration);
        nextSent();
        assertEquals("ok", nextResult().outcome);

        identity.discard();
        pool.reply(TestIdentity.identityReply(TestIdentity.jwt("identity-2", 1_700_003_600L)));
        identity.obtainBlocking();
        declarer.credentialChanged();

        FakeCarrier.Sent sent = nextSent();
        assertEquals(ImpulseRpcPaths.InitConnectionMethod, sent.method);
        assertEquals("ok", nextResult().outcome);
        drain();
        assertEquals(2, carrier.callCount());
    }


    @Test
    public void awaitDeclaredReturnsAtOnceWhenDeclared() throws Exception {
        carrier.reply(new byte[0]);
        declarer.setDeclaration(declaration);
        nextSent();
        assertEquals("ok", nextResult().outcome);

        assertTrue(declarer.awaitDeclared(0L));
        assertEquals(1, carrier.callCount());
    }


    @Test
    public void awaitDeclaredWaitsForTheRunningDeclaration() throws Exception {
        InitConnectionGate gate = new InitConnectionGate(carrier);
        buildOn(gate);
        declarer.setDeclaration(declaration);
        assertTrue(gate.arrived.await(WaitSeconds, TimeUnit.SECONDS));

        ExecutorService waiterThread = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> waiter = waiterThread.submit(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    return declarer.awaitDeclared(5_000L);
                }
            });
            try {
                waiter.get(200L, TimeUnit.MILLISECONDS);
                fail("awaitDeclared returned while the declaration was still in flight");
            } catch (TimeoutException stillWaiting) {
                // The declaration is held, so the waiter must still be waiting.
            }

            gate.pendingInit.onReply(new byte[0]);

            assertTrue(waiter.get(1L, TimeUnit.SECONDS));
        } finally {
            waiterThread.shutdownNow();
        }
        assertEquals("ok", nextResult().outcome);
    }


    @Test
    public void awaitDeclaredReturnsFalseWhenTheRunFails() throws Exception {
        // Three scripted refusals: the first run and the run the waiter triggers each give up at once, so the
        // waiter never reaches its timeout.
        for (int a = 0; a < 3; a++) {
            carrier.fail(new ReplyError(100, "", false, 400, "API_ID_INVALID", Collections.<String, String>emptyMap()));
        }
        declarer.setDeclaration(declaration);

        long startedNanos = System.nanoTime();
        assertFalse(declarer.awaitDeclared(5_000L));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

        assertTrue("the waiter must not run out its timeout, took " + elapsedMillis + " ms", elapsedMillis < 4_000L);
        assertEquals("gave_up", nextResult().outcome);
    }


    @Test
    public void invalidateDeclaresAgain() throws Exception {
        carrier.reply(new byte[0]);
        carrier.reply(new byte[0]);
        declarer.setDeclaration(declaration);
        nextSent();
        assertEquals("ok", nextResult().outcome);

        declarer.invalidate();

        FakeCarrier.Sent sent = nextSent();
        assertEquals(ImpulseRpcPaths.InitConnectionMethod, sent.method);
        assertArrayEquals(ImpulseRpcPaths.encodeInitConnection(declaration), sent.data);
        assertEquals("ok", nextResult().outcome);
        drain();
        assertEquals(2, carrier.callCount());
    }
}
