package net.impulsem.transport.fleet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.protobuf.ByteString;
import io.github.centrifugal.centrifuge.ConnectionTokenEvent;
import io.github.centrifugal.centrifuge.ConnectionTokenGetter;
import io.github.centrifugal.centrifuge.TokenCallback;
import io.github.centrifugal.centrifuge.internal.protocol.Protocol;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.impulsem.transport.errors.RpcError;
import net.impulsem.transport.realtime.ProtoCentrifugoServer;
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


public class RpcPoolCarrierTest {

    private static final long WaitMillis = 5000L;
    private static final long SilenceMillis = 500L;
    private static final long CarrierTimeoutMillis = 30000L;


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
    private MockWebServer server;
    private OkHttpClient http;
    private RpcPoolCarrier carrier;


    @Before
    public void setUp() throws IOException {
        sessions.add(new Session());
        server = ProtoCentrifugoServer.start(sessions);
        http = new OkHttpClient.Builder().build();
    }


    @After
    public void tearDown() throws IOException {
        if (carrier != null) {
            carrier.close();
        }
        http.dispatcher().executorService().shutdown();
        try {
            server.shutdown();
        } catch (IOException slowCloseHandshake) {
            // MockWebServer waits for its websocket close-handshake tasks on a socket the pool already dropped;
            // the test result does not depend on that wait.
        }
    }


    @Test
    public void poolFleetCallsConnectionIdentityWithoutAToken() throws Exception {
        sessions.get(0).rpcResponder = new RpcResponder() {
            @Override
            public Protocol.Reply.Builder answer(Protocol.RPCRequest request) {
                return Protocol.Reply.newBuilder()
                    .setRpc(Protocol.RPCResult.newBuilder().setData(ByteString.copyFrom(new byte[] {4, 2})));
            }
        };
        carrier = RpcPoolCarrier.poolFleet(http, endpoint());
        Outcome outcome = new Outcome();

        carrier.call("connectionIdentity", new byte[] {9, 9}, WaitMillis, outcome);

        Protocol.ConnectRequest connect = sessions.get(0).nextCommandOf(CommandKind.CONNECT).getConnect();
        assertEquals("", connect.getToken());
        Protocol.RPCRequest request = sessions.get(0).nextCommandOf(CommandKind.RPC).getRpc();
        assertEquals("connectionIdentity", request.getMethod());
        assertArrayEquals(new byte[] {9, 9}, request.getData().toByteArray());
        outcome.awaitDone();
        assertArrayEquals(new byte[] {4, 2}, outcome.reply);
        assertEquals(1, outcome.calls.get());
    }


    @Test
    public void appErrorReachesOnFailure() throws Exception {
        sessions.get(0).rpcResponder = new RpcResponder() {
            @Override
            public Protocol.Reply.Builder answer(Protocol.RPCRequest request) {
                return Protocol.Reply.newBuilder().setError(Protocol.Error.newBuilder()
                    .setCode(100)
                    .setAppCode(400)
                    .setAppType("API_ID_INVALID"));
            }
        };
        carrier = RpcPoolCarrier.poolFleet(http, endpoint());
        Outcome outcome = new Outcome();

        carrier.call("refreshSession", new byte[0], WaitMillis, outcome);

        outcome.awaitDone();
        assertNull(outcome.reply);
        assertNotNull(outcome.failure);
        RpcError mapped = CarrierErrors.toRpcError(outcome.failure);
        assertNotNull(mapped);
        assertEquals(400, mapped.code);
        assertEquals("API_ID_INVALID", mapped.text);
    }


    @Test
    public void methodOutsideTheFleetIsRefusedWithoutSending() throws Exception {
        carrier = RpcPoolCarrier.poolFleet(http, endpoint());

        try {
            carrier.call("messages.getHistory", new byte[0], WaitMillis, new Outcome());
            fail("a method outside the fleet must be refused");
        } catch (IllegalArgumentException expected) {
            // the refusal is the contract
        }

        assertNull("nothing may be sent", sessions.get(0).commands.poll(SilenceMillis, TimeUnit.MILLISECONDS));
    }


    @Test
    public void tokenGetterTokenReachesTheConnect() throws Exception {
        sessions.get(0).rpcResponder = new RpcResponder() {
            @Override
            public Protocol.Reply.Builder answer(Protocol.RPCRequest request) {
                return Protocol.Reply.newBuilder().setRpc(Protocol.RPCResult.newBuilder());
            }
        };
        ConnectionTokenGetter getter = new ConnectionTokenGetter() {
            @Override
            public void getConnectionToken(
                ConnectionTokenEvent event,
                TokenCallback callback
            ) {
                callback.Done(null, "media-jwt");
            }
        };
        carrier = new RpcPoolCarrier(http, endpoint(), 1, 1, 64, CarrierTimeoutMillis, getter, null);
        Outcome outcome = new Outcome();

        carrier.call("upload.saveFilePart", new byte[0], WaitMillis, outcome);

        Protocol.ConnectRequest connect = sessions.get(0).nextCommandOf(CommandKind.CONNECT).getConnect();
        assertEquals("media-jwt", connect.getToken());
        outcome.awaitDone();
        assertEquals(1, outcome.calls.get());
    }


    @Test
    public void closeFailsACallInFlight() throws Exception {
        carrier = RpcPoolCarrier.poolFleet(http, endpoint());
        Outcome outcome = new Outcome();
        carrier.call("connectionIdentity", new byte[0], CarrierTimeoutMillis, outcome);
        sessions.get(0).nextCommandOf(CommandKind.RPC);

        carrier.close();

        outcome.awaitDone();
        Thread.sleep(SilenceMillis);
        assertEquals(1, outcome.calls.get());
        assertNotNull(outcome.failure);
        assertNull(CarrierErrors.toRpcError(outcome.failure));
    }


    private String endpoint() {
        return server.url("/connection/websocket").toString().replace("http", "ws");
    }
}
