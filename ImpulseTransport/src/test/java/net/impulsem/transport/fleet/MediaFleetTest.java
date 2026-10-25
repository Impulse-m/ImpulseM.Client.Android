package net.impulsem.transport.fleet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.github.centrifugal.centrifuge.internal.protocol.Protocol;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.impulsem.transport.auth.Clock;
import net.impulsem.transport.auth.SessionStore;
import net.impulsem.transport.auth.SessionTokens;
import net.impulsem.transport.auth.TokenManager;
import net.impulsem.transport.realtime.ProtoCentrifugoServer;
import net.impulsem.transport.realtime.ProtoCentrifugoServer.CommandKind;
import net.impulsem.transport.realtime.ProtoCentrifugoServer.RpcResponder;
import net.impulsem.transport.realtime.ProtoCentrifugoServer.Session;
import net.impulsem.transport.rpc.CarrierCallback;
import net.impulsem.transport.rpc.CarrierErrors;
import net.impulsem.transport.rpc.CarrierFailure;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class MediaFleetTest {

    private static final long WaitMillis = 5000L;
    private static final long SilenceMillis = 500L;
    private static final long ShortTimeoutMillis = 200L;
    private static final String FileMethod = "upload.getFile";


    /** The session store the token manager reads; the tokens are opaque, so they never need a refresh. */
    private static final class MemorySessionStore implements SessionStore {

        private SessionTokens stored;


        @Override
        public SessionTokens load() {
            return stored;
        }


        @Override
        public void save(SessionTokens tokens) {
            stored = tokens;
        }


        @Override
        public void clear() {
            stored = null;
        }
    }


    /** What one carrier call reported: how often its callback ran, and the reply or the failure. */
    private static final class Outcome implements CarrierCallback {

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        volatile byte[] reply;
        volatile CarrierFailure failure;


        @Override
        public void onReply(byte[] data) {
            calls.incrementAndGet();
            reply = data;
            done.countDown();
        }


        @Override
        public void onFailure(CarrierFailure failure) {
            calls.incrementAndGet();
            this.failure = failure;
            done.countDown();
        }


        void awaitDone() throws InterruptedException {
            assertTrue("callback not run", done.await(WaitMillis, TimeUnit.MILLISECONDS));
        }
    }


    private final List<Session> sessions = new ArrayList<Session>();
    private final List<Session> otherSessions = new ArrayList<Session>();
    private MockWebServer server;
    private MockWebServer otherServer;
    private OkHttpClient http;
    private ExecutorService executor;
    private TokenManager tokens;
    private MediaFleet fleet;


    @Before
    public void setUp() throws IOException {
        // Two sessions on the first server: a sign-out test reconnects once.
        sessions.add(new Session());
        sessions.add(new Session());
        for (Session session : sessions) {
            answerRpc(session);
        }
        server = ProtoCentrifugoServer.start(sessions);
        http = new OkHttpClient.Builder().build();
        executor = Executors.newCachedThreadPool();
        tokens = new TokenManager(new MemorySessionStore(), Clock.SYSTEM);
    }


    @After
    public void tearDown() throws IOException {
        if (fleet != null) {
            fleet.close();
        }
        executor.shutdownNow();
        http.dispatcher().executorService().shutdown();
        shutDown(server);
        shutDown(otherServer);
    }


    @Test
    public void callAfterSetEndpointConnectsWithTheAccessToken() throws Exception {
        signIn("access-1");
        fleet = new MediaFleet(http, tokens, executor);
        fleet.setEndpoint(endpoint(server));
        Outcome outcome = new Outcome();

        fleet.call(FileMethod, new byte[] {9, 9}, WaitMillis, outcome);

        Protocol.ConnectRequest connect = sessions.get(0).nextCommandOf(CommandKind.CONNECT).getConnect();
        assertEquals("access-1", connect.getToken());
        Protocol.RPCRequest request = sessions.get(0).nextCommandOf(CommandKind.RPC).getRpc();
        assertEquals(FileMethod, request.getMethod());
        outcome.awaitDone();
        assertArrayEquals(new byte[] {4, 2}, outcome.reply);
        assertEquals(1, outcome.calls.get());
    }


    @Test
    public void callBeforeTheConfigIsSentWhenSetEndpointArrives() throws Exception {
        signIn("access-1");
        fleet = new MediaFleet(http, tokens, executor);
        Outcome outcome = new Outcome();

        fleet.call(FileMethod, new byte[0], WaitMillis, outcome);

        assertNull("nothing may connect before the config", sessions.get(0).commands.poll(SilenceMillis, TimeUnit.MILLISECONDS));
        fleet.setEndpoint(endpoint(server));
        assertNotNull(sessions.get(0).nextCommandOf(CommandKind.RPC));
        outcome.awaitDone();
        assertEquals(1, outcome.calls.get());
        assertArrayEquals(new byte[] {4, 2}, outcome.reply);
    }


    @Test
    public void queuedCallThatTimesOutFailsAsACarrierFault() throws Exception {
        signIn("access-1");
        fleet = new MediaFleet(http, tokens, executor);
        Outcome outcome = new Outcome();

        fleet.call(FileMethod, new byte[0], ShortTimeoutMillis, outcome);

        outcome.awaitDone();
        assertEquals(1, outcome.calls.get());
        assertNotNull(outcome.failure);
        assertNull(CarrierErrors.toRpcError(outcome.failure));
        assertEquals("no config yet for " + FileMethod, outcome.failure.cause.getMessage());
        assertNull("nothing may be sent", sessions.get(0).commands.poll(SilenceMillis, TimeUnit.MILLISECONDS));
    }


    @Test
    public void callWithoutMediaOptionFailsAtOnce() throws Exception {
        signIn("access-1");
        fleet = new MediaFleet(http, tokens, executor);
        fleet.setEndpoint(null);
        Outcome outcome = new Outcome();

        fleet.call(FileMethod, new byte[0], WaitMillis, outcome);

        assertEquals("the failure is delivered before call returns", 1, outcome.calls.get());
        assertNull(CarrierErrors.toRpcError(outcome.failure));
        assertEquals("no media_only dc option for " + FileMethod, outcome.failure.cause.getMessage());
        assertNull("nothing may connect", sessions.get(0).commands.poll(SilenceMillis, TimeUnit.MILLISECONDS));
    }


    @Test
    public void newEndpointMakesTheNextCallConnectToIt() throws Exception {
        signIn("access-1");
        otherSessions.add(new Session());
        for (Session session : otherSessions) {
            answerRpc(session);
        }
        otherServer = ProtoCentrifugoServer.start(otherSessions);
        fleet = new MediaFleet(http, tokens, executor);
        fleet.setEndpoint(endpoint(server));
        fleet.call(FileMethod, new byte[0], WaitMillis, new Outcome());
        sessions.get(0).nextCommandOf(CommandKind.RPC);

        fleet.setEndpoint(endpoint(otherServer));
        Outcome outcome = new Outcome();
        fleet.call(FileMethod, new byte[0], WaitMillis, outcome);

        Protocol.ConnectRequest connect = otherSessions.get(0).nextCommandOf(CommandKind.CONNECT).getConnect();
        assertEquals("access-1", connect.getToken());
        outcome.awaitDone();
        assertEquals(1, outcome.calls.get());
    }


    @Test
    public void sessionChangedMakesTheNextCallConnectAgainWithTheNewToken() throws Exception {
        signIn("access-1");
        fleet = new MediaFleet(http, tokens, executor);
        fleet.setEndpoint(endpoint(server));
        Outcome first = new Outcome();
        fleet.call(FileMethod, new byte[0], WaitMillis, first);
        assertEquals("access-1", sessions.get(0).nextCommandOf(CommandKind.CONNECT).getConnect().getToken());
        first.awaitDone();

        signIn("access-2");
        fleet.sessionChanged();
        Outcome second = new Outcome();
        fleet.call(FileMethod, new byte[0], WaitMillis, second);

        assertEquals("access-2", sessions.get(1).nextCommandOf(CommandKind.CONNECT).getConnect().getToken());
        second.awaitDone();
        assertEquals(1, second.calls.get());
    }


    private void signIn(String accessToken) {
        tokens.onLoginTokens(accessToken, "refresh-1", 7L);
    }


    private static void answerRpc(Session session) {
        session.rpcResponder = new RpcResponder() {
            @Override
            public Protocol.Reply.Builder answer(Protocol.RPCRequest request) {
                return Protocol.Reply.newBuilder()
                    .setRpc(Protocol.RPCResult.newBuilder().setData(ByteString.copyFrom(new byte[] {4, 2})));
            }
        };
    }


    private static String endpoint(MockWebServer webServer) {
        return webServer.url("/connection/websocket").toString().replace("http", "ws");
    }


    private static void shutDown(MockWebServer webServer) throws IOException {
        if (webServer == null) {
            return;
        }
        try {
            webServer.shutdown();
        } catch (IOException slowCloseHandshake) {
            // MockWebServer waits for close-handshake tasks on sockets the pool already dropped; the result does not depend on it.
        }
    }
}
