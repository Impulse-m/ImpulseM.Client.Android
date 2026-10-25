package net.impulsem.transport.realtime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.github.centrifugal.centrifuge.internal.protocol.Protocol;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.impulsem.transport.errors.RpcError;
import net.impulsem.transport.realtime.ProtoCentrifugoServer.CommandKind;
import net.impulsem.transport.realtime.ProtoCentrifugoServer.RpcResponder;
import net.impulsem.transport.realtime.ProtoCentrifugoServer.Session;
import net.impulsem.transport.rpc.CarrierCall;
import net.impulsem.transport.rpc.CarrierCallback;
import net.impulsem.transport.rpc.CarrierErrors;
import net.impulsem.transport.rpc.CarrierFailure;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class CentrifugoClientRpcTest {

    private static final long WaitMillis = ProtoCentrifugoServer.WaitMillis;
    private static final String Instance = "AbCdEfGhIjKlMnOpQrStUv";
    private static final long OneHourMillis = 3_600_000L;
    private static final long ShortTimeoutMillis = 300L;


    /** Records the first connect and nothing else. */
    private static final class ConnectLatch implements CentrifugoListener {

        final CountDownLatch connected = new CountDownLatch(1);


        @Override
        public void onConnected() {
            connected.countDown();
        }


        @Override
        public void onSubscribed(
            String channel,
            boolean recovered,
            boolean wasRecovering
        ) {
        }


        @Override
        public void onPublication(Publication publication) {
        }


        @Override
        public void onUnsubscribed(
            String channel,
            int code,
            String reason
        ) {
        }


        @Override
        public void onDisconnected(
            int code,
            String reason,
            boolean willReconnect
        ) {
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
    private final List<Socket> rawSockets = new CopyOnWriteArrayList<Socket>();
    private ScheduledExecutorService executor;
    private MockWebServer server;
    private OkHttpClient http;
    private ConnectLatch listener;
    private CentrifugoClient client;


    @Before
    public void setUp() throws IOException {
        executor = Executors.newScheduledThreadPool(2);
        sessions.add(new Session());
        server = ProtoCentrifugoServer.start(sessions);
        http = new OkHttpClient.Builder()
            .socketFactory(CentrifugoClientTest.trackingSocketFactory(rawSockets))
            .build();
        listener = new ConnectLatch();
    }


    @After
    public void tearDown() throws IOException {
        if (client != null) {
            client.disconnect();
        }
        for (Socket raw : rawSockets) {
            try {
                raw.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
        executor.shutdownNow();
        http.dispatcher().executorService().shutdown();
        try {
            server.shutdown();
        } catch (IOException slowCloseHandshake) {
            // MockWebServer waits for its websocket close-handshake tasks on a socket the client already dropped;
            // the test result does not depend on that wait.
        }
    }


    @Test
    public void rpcCarriesMethodAndDataAndDeliversTheReply() throws Exception {
        sessions.get(0).rpcResponder = new RpcResponder() {
            @Override
            public Protocol.Reply.Builder answer(Protocol.RPCRequest request) {
                return Protocol.Reply.newBuilder()
                    .setRpc(Protocol.RPCResult.newBuilder().setData(ByteString.copyFrom(new byte[] {7, 8})));
            }
        };
        startClient(Instance);
        Outcome outcome = new Outcome();

        client.call("messages.getHistory", new byte[] {1, 2, 3}, WaitMillis, outcome);

        Protocol.RPCRequest request = sessions.get(0).nextCommandOf(CommandKind.RPC).getRpc();
        assertEquals("messages.getHistory", request.getMethod());
        assertArrayEquals(new byte[] {1, 2, 3}, request.getData().toByteArray());
        outcome.awaitDone();
        assertArrayEquals(new byte[] {7, 8}, outcome.reply);
        assertEquals(1, outcome.calls.get());
    }


    @Test
    public void appErrorReachesOnFailureWithItsFields() throws Exception {
        sessions.get(0).rpcResponder = new RpcResponder() {
            @Override
            public Protocol.Reply.Builder answer(Protocol.RPCRequest request) {
                return Protocol.Reply.newBuilder().setError(Protocol.Error.newBuilder()
                    .setCode(100)
                    .setAppCode(420)
                    .setAppType("FLOOD_WAIT_7")
                    .putMetadata("retry-after", "7"));
            }
        };
        startClient(Instance);
        Outcome outcome = new Outcome();

        client.call("messages.sendMessage", new byte[0], WaitMillis, outcome);

        outcome.awaitDone();
        assertNull(outcome.reply);
        CarrierFailure failure = outcome.failure;
        assertNotNull(failure);
        assertEquals(420, failure.appCode);
        assertEquals("FLOOD_WAIT_7", failure.appType);
        assertTrue(failure.metadata.containsKey("retry-after"));
        RpcError mapped = CarrierErrors.toRpcError(failure);
        assertNotNull(mapped);
        assertEquals(420, mapped.code);
        assertEquals("FLOOD_WAIT_7", mapped.text);
    }


    @Test
    public void emptyReplyIsAnEmptyArray() throws Exception {
        sessions.get(0).rpcResponder = new RpcResponder() {
            @Override
            public Protocol.Reply.Builder answer(Protocol.RPCRequest request) {
                return Protocol.Reply.newBuilder().setRpc(Protocol.RPCResult.newBuilder());
            }
        };
        startClient(Instance);
        Outcome outcome = new Outcome();

        client.call("updates.getState", new byte[0], WaitMillis, outcome);

        outcome.awaitDone();
        assertNotNull(outcome.reply);
        assertEquals(0, outcome.reply.length);
    }


    @Test
    public void unansweredCallTimesOut() throws Exception {
        startClient(Instance);
        Outcome outcome = new Outcome();

        client.call("messages.getHistory", new byte[0], ShortTimeoutMillis, outcome);

        outcome.awaitDone();
        assertNull(outcome.reply);
        assertNotNull(outcome.failure);
        assertNull(CarrierErrors.toRpcError(outcome.failure));
    }


    @Test
    public void instanceCallsCarryAMsgId() throws Exception {
        sessions.get(0).confirmRpcOptions = true;
        startClient(Instance);
        Outcome outcome = new Outcome();

        client.call("messages.getHistory", new byte[0], ShortTimeoutMillis, outcome);

        Protocol.RPCRequest request = sessions.get(0).nextCommandOf(CommandKind.RPC).getRpc();
        assertTrue("msg_id must be set when the client has an instance", request.getMsgId() != 0L);
        outcome.awaitDone();
    }


    @Test
    public void clientWithoutInstanceSendsNoMsgId() throws Exception {
        sessions.get(0).confirmRpcOptions = true;
        startClient(null);
        Outcome outcome = new Outcome();

        client.call("messages.getHistory", new byte[0], ShortTimeoutMillis, outcome);

        Protocol.RPCRequest request = sessions.get(0).nextCommandOf(CommandKind.RPC).getRpc();
        assertEquals(0L, request.getMsgId());
        outcome.awaitDone();
    }


    @Test
    public void cancelOfASentCallSettlesItOnce() throws Exception {
        sessions.get(0).confirmRpcOptions = true;
        startClient(Instance);
        Outcome outcome = new Outcome();
        CarrierCall call = client.call("messages.getHistory", new byte[0], WaitMillis, outcome);
        sessions.get(0).nextCommandOf(CommandKind.RPC);

        call.cancel();

        outcome.awaitDone();
        Thread.sleep(1000L);
        assertEquals(1, outcome.calls.get());
        assertNotNull(outcome.failure);
        assertNull(CarrierErrors.toRpcError(outcome.failure));
    }


    @Test
    public void serverTimeFollowsTheConnectReply() throws Exception {
        long expected = System.currentTimeMillis() + OneHourMillis;
        sessions.get(0).serverTimeMillis = expected;
        sessions.get(0).rpcResponder = new RpcResponder() {
            @Override
            public Protocol.Reply.Builder answer(Protocol.RPCRequest request) {
                return Protocol.Reply.newBuilder().setRpc(Protocol.RPCResult.newBuilder());
            }
        };
        startClient(Instance);
        Outcome roundTrip = new Outcome();
        client.call("updates.getState", new byte[0], WaitMillis, roundTrip);
        roundTrip.awaitDone();

        long serverTime = client.serverTimeMillis();

        assertTrue("server time " + serverTime + " far from " + expected, Math.abs(serverTime - expected) < 5000L);
    }


    @Test
    public void refreshConnectionTokenSendsTheNewTokenOnTheLiveConnection() throws Exception {
        startClient(Instance);

        client.refreshConnectionToken("t2");

        Protocol.RefreshRequest refresh = sessions.get(0).nextCommandOf(CommandKind.REFRESH).getRefresh();
        assertEquals("t2", refresh.getToken());
        assertEquals(1, server.getRequestCount());
    }


    @Test
    public void refreshConnectionTokenIgnoresAnEmptyToken() throws Exception {
        startClient(Instance);

        client.refreshConnectionToken("");
        client.refreshConnectionToken(null);
        client.refreshConnectionToken("t2");

        // The first refresh the server sees must be the real token: an empty one would have been sent before it.
        Protocol.RefreshRequest refresh = sessions.get(0).nextCommandOf(CommandKind.REFRESH).getRefresh();
        assertEquals("t2", refresh.getToken());
    }


    /** Builds the client against the fake server with the given instance (null for none) and waits for the connect. */
    private void startClient(String instance) throws InterruptedException {
        client = new CentrifugoClient(
            http,
            server.url("/connection/websocket?cf_ws_frame_ping_pong=true").toString(),
            new CentrifugoClient.TokenProvider() {
                @Override
                public String fetchToken() {
                    return "tok-1";
                }
            },
            listener,
            executor,
            instance
        );
        client.connect();
        assertTrue("not connected", listener.connected.await(WaitMillis, TimeUnit.MILLISECONDS));
    }
}
