package net.impulsem.transport.rpc;

import static net.impulsem.transport.GrpcWebTestSupport.error;
import static net.impulsem.transport.GrpcWebTestSupport.ok;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.impulsem.transport.auth.Clock;
import net.impulsem.transport.auth.ConnectionDeclaration;
import net.impulsem.transport.auth.ImpulseRpcPaths;
import net.impulsem.transport.auth.SessionStore;
import net.impulsem.transport.auth.SessionTokens;
import net.impulsem.transport.auth.TokenManager;
import net.impulsem.transport.codec.Transcoder;
import net.impulsem.transport.grpcweb.GrpcWebClient;
import net.impulsem.transport.schema.TlProtoSchema;
import net.impulsem.transport.wire.ProtoReader;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
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

    private MockWebServer server;
    private MemoryStore store;
    private TokenManager tokens;
    private RpcClient rpc;
    private ExecutorService executor;
    private ConnectionDeclarer declarer;


    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        store = new MemoryStore();
        store.tokens = new SessionTokens("old", "r1", 5L);
        executor = Executors.newSingleThreadExecutor();
        build();
    }


    @After
    public void tearDown() throws IOException {
        executor.shutdownNow();
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
        rpc = new RpcClient(new Transcoder(TlProtoSchema.load()), grpc, tokens);
        declarer = new ConnectionDeclarer(
            rpc,
            tokens,
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


    private RecordedRequest nextRequest() throws Exception {
        RecordedRequest request = server.takeRequest(WaitSeconds, TimeUnit.SECONDS);
        assertNotNull("no request arrived", request);
        return request;
    }


    private static String declarationField(
        RecordedRequest request,
        int field
    ) {
        byte[] frame = request.getBody().readByteArray();
        // gRPC-Web frame: 1 flag byte, 4 length bytes, then the protobuf message.
        ProtoReader outer = new ProtoReader(frame, 5, frame.length - 5);
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
        server.enqueue(ok(new byte[0]));

        declarer.setDeclaration(declaration);

        RecordedRequest request = nextRequest();
        assertEquals(ImpulseRpcPaths.InitConnection, request.getPath());
        assertEquals("Bearer old", request.getHeader("authorization"));
        assertEquals("HUAWEI MNA-LX9", declarationField(request, 2));
        assertEquals("ok", nextResult().outcome);

        declarer.sessionChanged();
        declarer.setDeclaration(declaration);
        drain();
        assertEquals("an unchanged bearer and declaration write nothing", 1, server.getRequestCount());
    }


    @Test
    public void notSentWithoutABearer() throws Exception {
        store.tokens = null;
        build();

        declarer.setDeclaration(declaration);
        declarer.sessionChanged();
        drain();

        assertEquals(0, server.getRequestCount());
        assertNull(results.poll());
    }


    @Test
    public void sentOnceLoginStoresTheFirstSession() throws Exception {
        store.tokens = null;
        build();
        declarer.setDeclaration(declaration);
        drain();
        assertEquals(0, server.getRequestCount());
        server.enqueue(ok(new byte[0]));

        tokens.onLoginTokens("fresh", "r9", 7L);

        RecordedRequest request = nextRequest();
        assertEquals(ImpulseRpcPaths.InitConnection, request.getPath());
        assertEquals("Bearer fresh", request.getHeader("authorization"));
        assertEquals("ok", nextResult().outcome);
    }


    @Test
    public void sentAgainWhenTheBearerChanges() throws Exception {
        server.enqueue(ok(new byte[0]));
        server.enqueue(ok(new byte[0]));
        declarer.setDeclaration(declaration);
        assertEquals("Bearer old", nextRequest().getHeader("authorization"));
        assertEquals("ok", nextResult().outcome);

        tokens.onLoginTokens("rotated", "r2", 5L);

        assertEquals("Bearer rotated", nextRequest().getHeader("authorization"));
        assertEquals("ok", nextResult().outcome);
        drain();
        assertEquals(2, server.getRequestCount());
    }


    @Test
    public void languageChangeResendsWithTheNewLangCode() throws Exception {
        server.enqueue(ok(new byte[0]));
        server.enqueue(ok(new byte[0]));
        declarer.setDeclaration(declaration);
        assertEquals("en", declarationField(nextRequest(), 7));
        assertEquals("ok", nextResult().outcome);

        declarer.setLangCode("ru");

        RecordedRequest request = nextRequest();
        assertEquals("ru", declarationField(request, 7));
        assertEquals("ok", nextResult().outcome);
        declarer.setLangCode("ru");
        drain();
        assertEquals("the same language again writes nothing", 2, server.getRequestCount());
    }


    @Test
    public void systemLanguageChangeResendsToo() throws Exception {
        server.enqueue(ok(new byte[0]));
        server.enqueue(ok(new byte[0]));
        declarer.setDeclaration(declaration);
        nextRequest();
        nextResult();

        declarer.setSystemLangCode("de");

        assertEquals("de", declarationField(nextRequest(), 5));
    }


    @Test
    public void aFailureDoesNotBlockAConcurrentRpc() throws Exception {
        final CountDownLatch initArrived = new CountDownLatch(1);
        final CountDownLatch releaseInit = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (ImpulseRpcPaths.InitConnection.equals(request.getPath())) {
                    initArrived.countDown();
                    releaseInit.await(WaitSeconds, TimeUnit.SECONDS);
                    return error(13, "INTERNAL_BROKEN");
                }
                return ok("payload".getBytes(StandardCharsets.UTF_8));
            }
        });

        declarer.setDeclaration(declaration);
        assertTrue(initArrived.await(WaitSeconds, TimeUnit.SECONDS));

        byte[] body = rpc.callImpulse(TokenPath, new byte[0]);
        assertEquals("payload", new String(body, StandardCharsets.UTF_8));
        assertNull("the declaration is still in flight", results.poll());

        releaseInit.countDown();
        assertTrue(nextResult().outcome.length() > 0);
    }


    @Test
    public void aTransientFailureIsRetriedAndThenGivenUpQuietly() throws Exception {
        for (int a = 0; a < 10; a++) {
            server.enqueue(error(14, "UNAVAILABLE"));
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

        assertTrue("it must stop after a few attempts", server.getRequestCount() <= 4);
        assertTrue(server.getRequestCount() >= 2);
        drain();
        assertEquals(0, delayer.takeAll().size());
        int requests = server.getRequestCount();
        drain();
        assertEquals("no request after giving up", requests, server.getRequestCount());
    }


    @Test
    public void aPermanentErrorIsNotRetried() throws Exception {
        server.enqueue(error(3, "API_ID_INVALID"));

        declarer.setDeclaration(declaration);

        Result result = nextResult();
        assertEquals("gave_up", result.outcome);
        assertEquals(400, result.code);
        drain();
        assertEquals(1, server.getRequestCount());
        assertEquals(0, delayer.takeAll().size());
    }


    @Test
    public void aPendingRetryIsDroppedWhenANewTriggerSupersedesIt() throws Exception {
        server.enqueue(error(14, "UNAVAILABLE"));
        server.enqueue(ok(new byte[0]));
        declarer.setDeclaration(declaration);
        assertEquals("retry", nextResult().outcome);
        List<Runnable> stale = delayer.takeAll();
        assertEquals(1, stale.size());

        tokens.onLoginTokens("rotated", "r2", 5L);
        assertEquals("ok", nextResult().outcome);
        stale.get(0).run();
        drain();

        assertEquals("the stale retry sends nothing", 2, server.getRequestCount());
    }
}
