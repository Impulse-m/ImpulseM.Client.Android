package net.impulsem.transport.realtime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.SocketFactory;
import net.impulsem.transport.logging.LogLevel;
import net.impulsem.transport.logging.LogRecord;
import net.impulsem.transport.errors.RpcError;
import net.impulsem.transport.logging.TraceSink;
import net.impulsem.transport.rpc.SessionLostException;
import net.impulsem.transport.realtime.ProtoCentrifugoServer.CommandKind;
import net.impulsem.transport.realtime.ProtoCentrifugoServer.Session;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockWebServer;
import okio.ByteString;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class CentrifugoClientTest {

    private static final long WaitMillis = ProtoCentrifugoServer.WaitMillis;


    private static final class Recorder implements CentrifugoListener {

        final BlockingQueue<String> events = new LinkedBlockingQueue<String>();
        final BlockingQueue<Publication> publications = new LinkedBlockingQueue<Publication>();
        final List<Boolean> willReconnect = new CopyOnWriteArrayList<Boolean>();


        @Override
        public void onConnected() {
            events.add("connected");
        }


        @Override
        public void onSubscribed(
            String channel,
            boolean recovered,
            boolean wasRecovering
        ) {
            events.add("subscribed:" + channel + ":" + recovered + ":" + wasRecovering);
        }


        @Override
        public void onPublication(Publication publication) {
            publications.add(publication);
        }


        @Override
        public void onUnsubscribed(
            String channel,
            int code,
            String reason
        ) {
            events.add("unsubscribed:" + channel + ":" + code);
        }


        @Override
        public void onDisconnected(
            int code,
            String reason,
            boolean willReconnect
        ) {
            this.willReconnect.add(willReconnect);
            events.add("disconnected:" + code + ":" + willReconnect);
        }


        void expect(String event) throws InterruptedException {
            long deadline = System.currentTimeMillis() + WaitMillis;
            while (System.currentTimeMillis() < deadline) {
                String next = events.poll(100, TimeUnit.MILLISECONDS);
                if (event.equals(next)) {
                    return;
                }
            }
            throw new AssertionError("event not seen: " + event);
        }
    }


    /** Keeps every trace with its fields, so a test can look for an event and check what travels with it. */
    private static final class TraceLog implements TraceSink {

        final List<String> events = new CopyOnWriteArrayList<String>();
        final List<Map<String, Object>> fields = new CopyOnWriteArrayList<Map<String, Object>>();


        @Override
        public void trace(
            LogLevel level,
            String event,
            Object... keysAndValues
        ) {
            fields.add(LogRecord.fieldsOf(keysAndValues));
            events.add(event);
        }


        Map<String, Object> await(String event) throws InterruptedException {
            long deadline = System.currentTimeMillis() + WaitMillis;
            while (System.currentTimeMillis() < deadline) {
                int index = events.indexOf(event);
                if (index >= 0) {
                    return fields.get(index);
                }
                Thread.sleep(20L);
            }
            throw new AssertionError("trace not seen: " + event + " in " + events);
        }


        void assertNoFreeText() {
            for (Map<String, Object> traceFields : fields) {
                for (String key : traceFields.keySet()) {
                    assertFalse(
                        "free text must not travel as a field: " + key,
                        key.equals("reason") || key.equals("message") || key.equals("error")
                    );
                }
            }
        }
    }


    private final TraceLog traces = new TraceLog();
    private MockWebServer server;
    private ScheduledExecutorService executor;
    private OkHttpClient http;
    private final List<Session> sessions = new ArrayList<Session>();
    private final List<Socket> rawSockets = new CopyOnWriteArrayList<Socket>();
    private final AtomicInteger tokenCounter = new AtomicInteger();
    private volatile boolean sessionRevoked;
    private Recorder recorder;
    private CentrifugoClient client;


    @Before
    public void setUp() throws IOException {
        executor = Executors.newScheduledThreadPool(2);
        for (int i = 0; i < 4; i++) {
            sessions.add(new Session());
        }
        server = ProtoCentrifugoServer.start(sessions);
        http = new OkHttpClient.Builder()
            .socketFactory(trackingSocketFactory(rawSockets))
            .build();
        recorder = new Recorder();
        client = new CentrifugoClient(
            http,
            server.url("/connection/websocket?cf_ws_frame_ping_pong=true").toString(),
            new CentrifugoClient.TokenProvider() {
                @Override
                public String fetchToken() throws IOException {
                    int number = tokenCounter.incrementAndGet();
                    if (sessionRevoked) {
                        throw new SessionLostException("session revoked", new RpcError(401, "SESSION_EXPIRED"));
                    }
                    return "tok-" + number;
                }
            },
            recorder,
            executor
        );
    }


    @After
    public void tearDown() throws IOException {
        client.disconnect();
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
            // MockWebServer waits for its websocket close-handshake tasks (up to 60 s) on a socket the
            // client already dropped; the test result does not depend on that wait.
        }
    }


    static SocketFactory trackingSocketFactory(final List<Socket> sockets) {
        return new SocketFactory() {
            private Socket track(Socket socket) {
                sockets.add(socket);
                return socket;
            }


            @Override
            public Socket createSocket() {
                return track(new Socket());
            }


            @Override
            public Socket createSocket(
                String host,
                int port
            ) throws IOException {
                return track(new Socket(host, port));
            }


            @Override
            public Socket createSocket(
                String host,
                int port,
                InetAddress localHost,
                int localPort
            ) throws IOException {
                return track(new Socket(host, port, localHost, localPort));
            }


            @Override
            public Socket createSocket(
                InetAddress host,
                int port
            ) throws IOException {
                return track(new Socket(host, port));
            }


            @Override
            public Socket createSocket(
                InetAddress address,
                int port,
                InetAddress localAddress,
                int localPort
            ) throws IOException {
                return track(new Socket(address, port, localAddress, localPort));
            }
        };
    }


    /** Simulates a dropped TCP connection: closes the client's raw socket of the n-th connection. */
    private void abort(int connectionIndex) throws IOException {
        rawSockets.get(connectionIndex).close();
    }


    private static byte[] bytesOf(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }


    @Test
    public void theConnectionLifecycleIsTracedWithTypedFields() throws Exception {
        client.setTraceSink(traces);
        client.connect();
        recorder.expect("connected");
        client.subscribe("user:1");
        recorder.expect("subscribed:user:1:false:false");

        assertNotNull(traces.await("REALTIME_CONNECTING").get("attempt"));
        traces.await("REALTIME_CONNECT_REPLY");
        Map<String, Object> sent = traces.await("REALTIME_SUBSCRIBE_SENT");
        assertEquals("user", sent.get("lane"));
        assertEquals(false, sent.get("wasRecovering"));
        Map<String, Object> subscribed = traces.await("REALTIME_SUBSCRIBE_REPLY");
        assertEquals("user", subscribed.get("lane"));
        assertEquals(false, subscribed.get("recovered"));
        assertEquals(false, subscribed.get("wasRecovering"));
        traces.assertNoFreeText();
    }


    @Test
    public void aDroppedConnectionTracesTheScheduledReconnect() throws Exception {
        client.setTraceSink(traces);
        client.connect();
        recorder.expect("connected");

        abort(0);

        Map<String, Object> scheduled = traces.await("REALTIME_RECONNECT_SCHEDULED");
        assertEquals(true, scheduled.get("wasConnected"));
        assertEquals(false, scheduled.get("terminal"));
        assertNotNull(scheduled.get("closeCode"));
        traces.assertNoFreeText();
    }


    @Test
    public void aThrowingTraceSinkDoesNotStallTheReconnect() throws Exception {
        client.setTraceSink(new TraceSink() {
            @Override
            public void trace(
                LogLevel level,
                String event,
                Object... keysAndValues
            ) {
                throw new IllegalStateException("sink failure");
            }
        });
        client.connect();
        recorder.expect("connected");

        abort(0);

        recorder.expect("disconnected:1006:true");
        recorder.expect("connected");
    }


    @Test
    public void aTerminalCloseIsTracedAsClosed() throws Exception {
        client.setTraceSink(traces);
        client.connect();
        recorder.expect("connected");

        sessions.get(0).socket.close(3501, "bad request");

        Map<String, Object> closed = traces.await("REALTIME_CLOSED");
        assertEquals(3501, ((Number) closed.get("closeCode")).intValue());
        assertEquals(true, closed.get("terminal"));
        traces.assertNoFreeText();
    }


    @Test
    public void aFailedSubscribeTracesTheErrorCodeButNotTheServerText() throws Exception {
        client.setTraceSink(traces);
        client.connect();
        recorder.expect("connected");
        sessions.get(0).subscribeErrors.add(
            Protocol.Error.newBuilder().setCode(103).setMessage("permission denied for alice").build()
        );

        client.subscribe("user:1");

        Map<String, Object> failed = traces.await("REALTIME_SUBSCRIBE_FAILED");
        assertEquals(103, ((Number) failed.get("errorCode")).intValue());
        assertEquals("user", failed.get("lane"));
        for (Object value : failed.values()) {
            assertFalse(String.valueOf(value), String.valueOf(value).contains("alice"));
        }
        traces.assertNoFreeText();
    }


    @Test
    public void connectFrameCarriesToken() throws Exception {
        client.connect();
        Protocol.ConnectRequest connect = sessions.get(0).nextCommand().getConnect();
        assertEquals("tok-1", connect.getToken());
        assertEquals("impulsem-android", connect.getName());
        assertTrue(connect.getVersion().length() > 0);
        recorder.expect("connected");
    }


    @Test
    public void subscribeSendsNoTagsFilter() throws Exception {
        client.subscribe("channel:5");
        client.subscribe("user:1");
        client.connect();
        Session session = sessions.get(0);
        Map<String, Protocol.SubscribeRequest> byChannel = new HashMap<String, Protocol.SubscribeRequest>();
        for (int i = 0; i < 2; i++) {
            Protocol.SubscribeRequest request = session.nextCommandOf(CommandKind.SUBSCRIBE).getSubscribe();
            byChannel.put(request.getChannel(), request);
        }
        Protocol.SubscribeRequest channelSub = byChannel.get("channel:5");
        Protocol.SubscribeRequest userSub = byChannel.get("user:1");
        assertNotNull(channelSub);
        assertNotNull(userSub);
        assertFalse(channelSub.hasTf());
        assertFalse(channelSub.getRecover());
        assertFalse(userSub.hasTf());
        expectAll("subscribed:channel:5:false:false", "subscribed:user:1:false:false");
        assertTrue(client.subscribedChannels().contains("channel:5"));
        assertTrue(client.subscribedChannels().contains("user:1"));
    }


    @Test
    public void publicationReachesListenerWithParsedEnvelope() throws Exception {
        client.connect();
        client.subscribe("user:1");
        recorder.expect("subscribed:user:1:false:false");
        sessions.get(0).pushPublication("user:1", 813L, ProtoCentrifugoServer.EnvelopeData);
        Publication publication = recorder.publications.poll(WaitMillis, TimeUnit.MILLISECONDS);
        assertNotNull(publication);
        assertEquals("user:1", publication.channel);
        assertEquals(813L, publication.offset);
        assertEquals(1, publication.envelope.type);
        assertEquals(5L, publication.envelope.pts);
        assertArrayEquals("hello".getBytes(), publication.envelope.updatesProto);
    }


    @Test
    public void connectionIsRefreshedBeforeTheServerClosesIt() throws Exception {
        Session session = sessions.get(0);
        session.ttl = 2;
        session.expiredCloseGraceMillis = 3000;
        client.connect();
        recorder.expect("connected");
        Protocol.RefreshRequest refresh = session.nextCommandOf(CommandKind.REFRESH).getRefresh();
        assertEquals("tok-2", refresh.getToken());
        // The reply to the refresh schedules the next one, on time against the grace again.
        Protocol.RefreshRequest second = session.nextCommandOf(CommandKind.REFRESH).getRefresh();
        assertEquals("tok-3", second.getToken());
        List<String> seen = new ArrayList<String>();
        recorder.events.drainTo(seen);
        for (String event : seen) {
            assertFalse("connection dropped: " + event, event.startsWith("disconnected"));
        }
        assertEquals(1, server.getRequestCount());
    }


    @Test
    public void pingIsAnsweredWithPong() throws Exception {
        client.connect();
        recorder.expect("connected");
        Session session = sessions.get(0);
        session.rawFrames.clear();
        session.sendRaw(ProtoCentrifugoServer.EmptyFrame);
        ByteString frame = session.rawFrames.poll(WaitMillis, TimeUnit.MILLISECONDS);
        assertNotNull(frame);
        assertArrayEquals(ProtoCentrifugoServer.EmptyFrame, frame.toByteArray());
    }


    @Test
    public void multipleObjectsInOneFrame() throws Exception {
        client.connect();
        client.subscribe("user:1");
        recorder.expect("subscribed:user:1:false:false");
        Session session = sessions.get(0);
        session.rawFrames.clear();
        session.sendRaw(concat(
            ProtoCentrifugoServer.EmptyFrame,
            publicationFrame("user:1", 11L),
            publicationFrame("user:1", 12L),
            ProtoCentrifugoServer.EmptyFrame
        ));
        Publication first = recorder.publications.poll(WaitMillis, TimeUnit.MILLISECONDS);
        Publication second = recorder.publications.poll(WaitMillis, TimeUnit.MILLISECONDS);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(11L, first.offset);
        assertEquals(12L, second.offset);
        assertArrayEquals(ProtoCentrifugoServer.EmptyFrame, session.rawFrames.poll(WaitMillis, TimeUnit.MILLISECONDS).toByteArray());
    }


    @Test
    public void unsubscribePush2501ResubscribesWithRecover() throws Exception {
        client.connect();
        client.subscribe("user:1");
        recorder.expect("subscribed:user:1:false:false");
        Session session = sessions.get(0);
        session.pushPublication("user:1", 813L, ProtoCentrifugoServer.EnvelopeData);
        assertNotNull(recorder.publications.poll(WaitMillis, TimeUnit.MILLISECONDS));
        session.commands.clear();
        session.pushUnsubscribe("user:1", 2501, "insufficient state");
        Protocol.SubscribeRequest resubscribe = session.nextCommandOf(CommandKind.SUBSCRIBE).getSubscribe();
        assertEquals("user:1", resubscribe.getChannel());
        assertTrue(resubscribe.getRecover());
        assertEquals(813L, resubscribe.getOffset());
        assertEquals("ep1", resubscribe.getEpoch());
        recorder.expect("subscribed:user:1:true:true");
    }


    @Test
    public void unsubscribePush2502ResubscribesAndReportsNoRecovery() throws Exception {
        client.connect();
        client.subscribe("user:1");
        recorder.expect("subscribed:user:1:false:false");
        Session session = sessions.get(0);
        session.pushPublication("user:1", 813L, ProtoCentrifugoServer.EnvelopeData);
        assertNotNull(recorder.publications.poll(WaitMillis, TimeUnit.MILLISECONDS));
        session.commands.clear();
        session.pushUnsubscribe("user:1", 2502, "state invalidated");
        // Which position fields the resubscribe carries is SDK-owned (invalidateState), so only the resubscribe
        // and the listener events are asserted.
        assertEquals("user:1", session.nextCommandOf(CommandKind.SUBSCRIBE).getSubscribe().getChannel());
        recorder.expect("unsubscribed:user:1:2502");
        recorder.expect("subscribed:user:1:false:false");
    }


    @Test
    public void unsubscribePush2000ForgetsTheChannel() throws Exception {
        client.connect();
        client.subscribe("channel:9");
        recorder.expect("subscribed:channel:9:false:false");
        Session session = sessions.get(0);
        session.commands.clear();
        session.pushUnsubscribe("channel:9", 2000, "unsubscribed");
        recorder.expect("unsubscribed:channel:9:2000");
        Thread.sleep(300L);
        assertTrue(session.commands.isEmpty());
        assertFalse(client.subscribedChannels().contains("channel:9"));
    }


    @Test
    public void close3005ReconnectsWithFreshToken() throws Exception {
        client.connect();
        client.subscribe("user:1");
        recorder.expect("subscribed:user:1:false:false");
        sessions.get(0).socket.close(3005, "connection expired");
        Protocol.ConnectRequest connect = sessions.get(1).nextCommand().getConnect();
        assertEquals("tok-2", connect.getToken());
        Protocol.SubscribeRequest resubscribe = sessions.get(1).nextCommandOf(CommandKind.SUBSCRIBE).getSubscribe();
        assertEquals("user:1", resubscribe.getChannel());
        assertEquals(2, tokenCounter.get());
    }


    @Test
    public void connectAgainAfterDisconnectOpensANewConnection() throws Exception {
        client.connect();
        recorder.expect("connected");
        client.disconnect();
        recorder.expect("disconnected:1000:false");
        client.connect();
        Protocol.ConnectRequest connect = sessions.get(1).nextCommand().getConnect();
        assertEquals("tok-2", connect.getToken());
    }


    @Test
    public void garbagePublicationIsDeliveredUndecodableAndDoesNotKillTheSocket() throws Exception {
        client.connect();
        client.subscribe("user:1");
        recorder.expect("subscribed:user:1:false:false");
        Session session = sessions.get(0);
        session.pushPublication("user:1", 31L, bytesOf("not an object"));
        session.pushPublication("user:1", 0L, bytesOf("{\"type\":1,\"pts\":\"zzz\",\"data\":\"aGVsbG8=\"}"));
        session.pushPublication("user:1", 33L, bytesOf("{\"type\":1,\"pts\":1,\"data\":\"@@@\"}"));
        session.pushPublication("user:1", 34L, ProtoCentrifugoServer.EnvelopeData);
        Publication first = recorder.publications.poll(WaitMillis, TimeUnit.MILLISECONDS);
        Publication second = recorder.publications.poll(WaitMillis, TimeUnit.MILLISECONDS);
        Publication third = recorder.publications.poll(WaitMillis, TimeUnit.MILLISECONDS);
        Publication good = recorder.publications.poll(WaitMillis, TimeUnit.MILLISECONDS);
        assertNotNull(first);
        assertNotNull(second);
        assertNotNull(third);
        assertNotNull(good);
        assertTrue(first.envelope.undecodable());
        assertEquals("user:1", first.channel);
        assertEquals(31L, first.offset);
        assertTrue(second.envelope.undecodable());
        assertEquals(0L, second.offset);
        assertTrue(third.envelope.undecodable());
        assertEquals(33L, third.offset);
        assertFalse(good.envelope.undecodable());
        assertEquals(34L, good.offset);
        assertEquals(1, server.getRequestCount());
        assertTrue(recorder.willReconnect.isEmpty());
    }


    @Test
    public void silenceBeyondPingWindowReconnects() throws Exception {
        client.setMaxServerPingDelayMillis(1000);
        sessions.get(0).pingSeconds = 1;
        client.connect();
        recorder.expect("connected");
        long start = System.currentTimeMillis();
        recorder.expect("disconnected:1006:true");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("watchdog fired after " + elapsed, elapsed >= 900L && elapsed < 4000L);
        Protocol.ConnectRequest connect = sessions.get(1).nextCommand().getConnect();
        assertEquals("tok-2", connect.getToken());
    }


    @Test
    public void pingsKeepTheWatchdogQuiet() throws Exception {
        sessions.get(0).pingSeconds = 1;
        client.connect();
        recorder.expect("connected");
        for (int i = 0; i < 6; i++) {
            Thread.sleep(500L);
            sessions.get(0).sendRaw(ProtoCentrifugoServer.EmptyFrame);
        }
        assertTrue(recorder.willReconnect.isEmpty());
        assertEquals(1, server.getRequestCount());
    }


    @Test
    public void sessionLostReportsOnceAndStaysDisconnectedUntilConnectAgain() throws Exception {
        client.connect();
        recorder.expect("connected");
        sessionRevoked = true;

        abort(0);

        recorder.expect("disconnected:1006:true");
        Thread.sleep(3000L);
        List<String> seen = new ArrayList<String>();
        recorder.events.drainTo(seen);
        assertEquals(1, java.util.Collections.frequency(seen, "disconnected:0:false"));
        assertFalse(seen.contains("connected"));
        // The socket opens before the SDK asks for a token, so the revoked attempt is one upgrade that sends no connect.
        assertEquals(2, server.getRequestCount());
        assertTrue(sessions.get(1).commands.isEmpty());

        sessionRevoked = false;
        client.connect();
        Protocol.ConnectRequest connect = sessions.get(2).nextCommand().getConnect();
        assertEquals("tok-3", connect.getToken());
        recorder.expect("connected");
    }


    @Test
    public void disconnectedEventPrecedesReconnectedAfter3005() throws Exception {
        client.connect();
        recorder.expect("connected");
        sessions.get(0).socket.close(3005, "connection expired");
        assertEquals("disconnected:3005:true", recorder.events.poll(WaitMillis, TimeUnit.MILLISECONDS));
        assertEquals("connected", recorder.events.poll(WaitMillis, TimeUnit.MILLISECONDS));
    }


    @Test
    public void subscribeTwiceForTheSameChannelSendsOneSubscribe() throws Exception {
        client.connect();
        recorder.expect("connected");
        client.subscribe("channel:5");
        recorder.expect("subscribed:channel:5:false:false");
        client.subscribe("channel:5");
        Thread.sleep(500L);
        int subscribes = 0;
        Protocol.Command command;
        while ((command = sessions.get(0).commands.poll()) != null) {
            if (command.hasSubscribe() && "channel:5".equals(command.getSubscribe().getChannel())) {
                subscribes++;
            }
        }
        assertEquals(1, subscribes);
        assertTrue(client.subscribedChannels().contains("channel:5"));
    }


    @Test
    public void close3501IsTerminal() throws Exception {
        client.connect();
        recorder.expect("connected");
        sessions.get(0).socket.close(3501, "bad request");
        recorder.expect("disconnected:3501:false");
        Thread.sleep(800L);
        assertEquals(1, server.getRequestCount());
    }


    @Test
    public void close4500RangeIsTerminalButSessionRevoked4001Reconnects() throws Exception {
        client.connect();
        recorder.expect("connected");
        sessions.get(0).socket.close(4001, "session_revoked");
        recorder.expect("disconnected:4001:true");
        assertNotNull(sessions.get(1).nextCommand());
        recorder.expect("connected");
        sessions.get(1).socket.close(4500, "x");
        recorder.expect("disconnected:4500:false");
        Thread.sleep(500L);
        assertEquals(2, server.getRequestCount());
    }


    @Test
    public void abnormalCloseReconnectsAndResubscribesWithRecover() throws Exception {
        client.connect();
        client.subscribe("user:1");
        recorder.expect("subscribed:user:1:false:false");
        Session first = sessions.get(0);
        first.pushPublication("user:1", 813L, ProtoCentrifugoServer.EnvelopeData);
        assertNotNull(recorder.publications.poll(WaitMillis, TimeUnit.MILLISECONDS));
        abort(0);
        recorder.expect("disconnected:1006:true");
        Protocol.ConnectRequest connect = sessions.get(1).nextCommand().getConnect();
        assertEquals("tok-2", connect.getToken());
        Protocol.SubscribeRequest resubscribe = sessions.get(1).nextCommandOf(CommandKind.SUBSCRIBE).getSubscribe();
        assertTrue(resubscribe.getRecover());
        assertEquals(813L, resubscribe.getOffset());
        assertEquals("ep1", resubscribe.getEpoch());
        recorder.expect("subscribed:user:1:true:true");
    }


    @Test
    public void recoveredPublicationsInSubscribeReplyAreDelivered() throws Exception {
        sessions.get(1).subscribeReplyPublications.add(publication(21L, 6L));
        sessions.get(1).subscribeReplyPublications.add(publication(22L, 7L));
        client.connect();
        client.subscribe("user:1");
        recorder.expect("subscribed:user:1:false:false");
        sessions.get(0).pushPublication("user:1", 20L, ProtoCentrifugoServer.EnvelopeData);
        assertNotNull(recorder.publications.poll(WaitMillis, TimeUnit.MILLISECONDS));
        abort(0);
        Publication a = recorder.publications.poll(WaitMillis, TimeUnit.MILLISECONDS);
        Publication b = recorder.publications.poll(WaitMillis, TimeUnit.MILLISECONDS);
        assertNotNull(a);
        assertNotNull(b);
        assertEquals("user:1", a.channel);
        assertEquals(21L, a.offset);
        assertEquals(22L, b.offset);
        assertEquals(7L, b.envelope.pts);
    }


    @Test
    public void temporaryErrorIsRetriedWithNewId() throws Exception {
        sessions.get(0).subscribeErrors.add(Protocol.Error.newBuilder().setCode(100).setMessage("internal server error").setTemporary(true).build());
        client.connect();
        client.subscribe("user:1");
        Session session = sessions.get(0);
        Protocol.Command first = session.nextCommandOf(CommandKind.SUBSCRIBE);
        Protocol.Command second = session.nextCommandOf(CommandKind.SUBSCRIBE);
        assertTrue(second.getId() > first.getId());
        recorder.expect("subscribed:user:1:false:false");
    }


    @Test
    public void fatalSubscribeErrorReportsUnsubscribe() throws Exception {
        sessions.get(0).subscribeErrors.add(Protocol.Error.newBuilder().setCode(103).setMessage("permission denied").build());
        client.connect();
        client.subscribe("channel:77");
        recorder.expect("unsubscribed:channel:77:103");
        assertFalse(client.subscribedChannels().contains("channel:77"));
    }


    @Test
    public void explicitUnsubscribeSendsCommandAndForgets() throws Exception {
        client.connect();
        client.subscribe("user:1");
        recorder.expect("subscribed:user:1:false:false");
        client.unsubscribe("user:1");
        Protocol.UnsubscribeRequest command = sessions.get(0).nextCommandOf(CommandKind.UNSUBSCRIBE).getUnsubscribe();
        assertEquals("user:1", command.getChannel());
        assertFalse(client.subscribedChannels().contains("user:1"));
    }


    @Test
    public void unsubscribeThenSubscribeLeavesExactlyOneSubscription() throws Exception {
        client.connect();
        recorder.expect("connected");
        client.subscribe("channel:5");
        recorder.expect("subscribed:channel:5:false:false");
        for (int i = 1; i <= 4; i++) {
            client.unsubscribe("channel:5");
            client.subscribe("channel:5");
        }
        Thread.sleep(1000L);
        assertTrue(client.subscribedChannels().contains("channel:5"));
        // Net server-side subscriptions for the channel: every subscribe must be matched by one unsubscribe, but
        // the last one. Counted over every command the server received.
        int active = 0;
        Protocol.Command command;
        while ((command = sessions.get(0).commands.poll()) != null) {
            if (command.hasSubscribe() && "channel:5".equals(command.getSubscribe().getChannel())) {
                active++;
            }
            if (command.hasUnsubscribe() && "channel:5".equals(command.getUnsubscribe().getChannel())) {
                active--;
            }
        }
        assertEquals(1, active);
    }


    /** Waits until every named event has arrived, in any order: the SDK does not order events of two channels. */
    private void expectAll(String... events) throws InterruptedException {
        Set<String> pending = new HashSet<String>(Arrays.asList(events));
        long deadline = System.currentTimeMillis() + WaitMillis;
        while (!pending.isEmpty() && System.currentTimeMillis() < deadline) {
            String next = recorder.events.poll(100, TimeUnit.MILLISECONDS);
            if (next != null) {
                pending.remove(next);
            }
        }
        assertTrue("events not seen: " + pending, pending.isEmpty());
    }


    private static byte[] publicationFrame(
        String channel,
        long offset
    ) {
        Protocol.Reply reply = Protocol.Reply.newBuilder()
            .setPush(Protocol.Push.newBuilder()
                .setChannel(channel)
                .setPub(Protocol.Publication.newBuilder()
                    .setData(com.google.protobuf.ByteString.copyFrom(ProtoCentrifugoServer.EnvelopeData))
                    .setOffset(offset)))
            .build();
        return ProtoCentrifugoServer.delimited(reply);
    }


    private static Protocol.Publication publication(
        long offset,
        long pts
    ) {
        byte[] data = bytesOf("{\"type\":1,\"pts\":" + pts + ",\"ptsCount\":1,\"date\":1,\"data\":\"aGVsbG8=\"}");
        return Protocol.Publication.newBuilder()
            .setData(com.google.protobuf.ByteString.copyFrom(data))
            .setOffset(offset)
            .build();
    }


    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }
}
