package net.impulsem.transport.realtime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.impulsem.transport.logging.LogLevel;
import net.impulsem.transport.logging.LogRecord;
import net.impulsem.transport.logging.TraceSink;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class CentrifugoClientTest {

    private static final long WaitMillis = 5000L;


    /** One server-side connection: records frames and answers commands like Centrifugo would. */
    private static final class Session extends WebSocketListener {

        final BlockingQueue<JsonObject> commands = new LinkedBlockingQueue<JsonObject>();
        final BlockingQueue<String> rawFrames = new LinkedBlockingQueue<String>();
        final BlockingQueue<JsonObject> subscribeErrors = new LinkedBlockingQueue<JsonObject>();
        volatile WebSocket socket;
        volatile int ttl = 60;
        volatile long subscribeOffset = 10L;
        volatile boolean replyToConnect = true;
        volatile String subscribeReplyExtra = "";
        volatile int pingSeconds = 0;


        @Override
        public void onOpen(
            WebSocket webSocket,
            Response response
        ) {
            socket = webSocket;
        }


        @Override
        public void onMessage(
            WebSocket webSocket,
            String text
        ) {
            rawFrames.add(text);
            JsonObject frame = JsonParser.parseString(text).getAsJsonObject();
            if (frame.size() == 0) {
                return;
            }
            commands.add(frame);
            int id = frame.get("id").getAsInt();
            if (frame.has("connect")) {
                if (replyToConnect) {
                    webSocket.send(
                        "{\"id\":" + id + ",\"connect\":{\"client\":\"c1\",\"expires\":true,\"ttl\":" + ttl
                            + (pingSeconds > 0 ? ",\"ping\":" + pingSeconds : "") + "}}"
                    );
                }
            } else if (frame.has("subscribe")) {
                JsonObject error = subscribeErrors.poll();
                if (error != null) {
                    webSocket.send("{\"id\":" + id + ",\"error\":" + error + "}");
                    return;
                }
                boolean recover = frame.getAsJsonObject("subscribe").has("recover");
                webSocket.send(
                    "{\"id\":" + id + ",\"subscribe\":{\"epoch\":\"ep1\",\"offset\":" + subscribeOffset
                        + ",\"recoverable\":true" + (recover ? ",\"recovered\":true" : "") + subscribeReplyExtra + "}}"
                );
            } else if (frame.has("refresh")) {
                webSocket.send("{\"id\":" + id + ",\"refresh\":{\"client\":\"c1\",\"expires\":true,\"ttl\":" + ttl + "}}");
            } else if (frame.has("unsubscribe")) {
                webSocket.send("{\"id\":" + id + ",\"unsubscribe\":{}}");
            }
        }


        JsonObject nextCommand() throws InterruptedException {
            JsonObject command = commands.poll(WaitMillis, TimeUnit.MILLISECONDS);
            assertNotNull("no command received", command);
            return command;
        }


        JsonObject nextCommandOf(String kind) throws InterruptedException {
            long deadline = System.currentTimeMillis() + WaitMillis;
            while (System.currentTimeMillis() < deadline) {
                JsonObject command = commands.poll(100, TimeUnit.MILLISECONDS);
                if (command != null && command.has(kind)) {
                    return command;
                }
            }
            throw new AssertionError("no " + kind + " command");
        }
    }


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
    private final List<java.net.Socket> rawSockets = new CopyOnWriteArrayList<java.net.Socket>();
    private final AtomicInteger tokenCounter = new AtomicInteger();
    private Recorder recorder;
    private CentrifugoClient client;


    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        executor = Executors.newScheduledThreadPool(2);
        http = new OkHttpClient.Builder()
            .socketFactory(new javax.net.SocketFactory() {
                private java.net.Socket track(java.net.Socket socket) {
                    rawSockets.add(socket);
                    return socket;
                }


                @Override
                public java.net.Socket createSocket() {
                    return track(new java.net.Socket());
                }


                @Override
                public java.net.Socket createSocket(
                    String host,
                    int port
                ) throws IOException {
                    return track(new java.net.Socket(host, port));
                }


                @Override
                public java.net.Socket createSocket(
                    String host,
                    int port,
                    java.net.InetAddress localHost,
                    int localPort
                ) throws IOException {
                    return track(new java.net.Socket(host, port, localHost, localPort));
                }


                @Override
                public java.net.Socket createSocket(
                    java.net.InetAddress host,
                    int port
                ) throws IOException {
                    return track(new java.net.Socket(host, port));
                }


                @Override
                public java.net.Socket createSocket(
                    java.net.InetAddress address,
                    int port,
                    java.net.InetAddress localAddress,
                    int localPort
                ) throws IOException {
                    return track(new java.net.Socket(address, port, localAddress, localPort));
                }
            })
            .build();
        recorder = new Recorder();
        // Enqueue a handful of connections up front.
        for (int i = 0; i < 4; i++) {
            Session session = new Session();
            sessions.add(session);
            server.enqueue(new MockResponse().withWebSocketUpgrade(session));
        }
        client = new CentrifugoClient(
            http,
            server.url("/connection/websocket?cf_ws_frame_ping_pong=true").toString(),
            new CentrifugoClient.TokenProvider() {
                @Override
                public String fetchToken() {
                    return "tok-" + tokenCounter.incrementAndGet();
                }
            },
            recorder,
            executor,
            new Backoff(50L, 400L, 0.2, new Random(3L))
        );
    }


    @After
    public void tearDown() throws IOException {
        client.disconnect();
        for (java.net.Socket raw : rawSockets) {
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


    /** Simulates a dropped TCP connection: closes the client's raw socket of the n-th connection. */
    private void abort(int connectionIndex) throws IOException {
        rawSockets.get(connectionIndex).close();
    }


    private static String envelopeFrame(
        String channel,
        long offset
    ) {
        return "{\"push\":{\"channel\":\"" + channel + "\",\"pub\":{\"data\":{\"type\":1,\"pts\":5,\"ptsCount\":1,\"date\":1700000000,"
            + "\"data\":\"aGVsbG8=\"},\"offset\":" + offset + "}}}";
    }


    @Test
    public void theConnectionLifecycleIsTracedWithTypedFields() throws Exception {
        client.setTraceSink(traces);
        client.connect();
        recorder.expect("connected");
        client.subscribe("user:1", null);
        recorder.expect("subscribed:user:1:false:false");

        assertNotNull(traces.await("REALTIME_CONNECTING").get("attempt"));
        Map<String, Object> reply = traces.await("REALTIME_CONNECT_REPLY");
        assertNotNull(reply.get("gen"));
        assertNotNull(reply.get("pingSeconds"));
        Map<String, Object> sent = traces.await("REALTIME_SUBSCRIBE_SENT");
        assertEquals("user", sent.get("lane"));
        assertEquals(false, sent.get("wasRecovering"));
        Map<String, Object> subscribed = traces.await("REALTIME_SUBSCRIBE_REPLY");
        assertEquals("user", subscribed.get("lane"));
        assertEquals(false, subscribed.get("recovered"));
        assertEquals(false, subscribed.get("wasRecovering"));
        assertEquals(0, ((Number) subscribed.get("replayed")).intValue());
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
        assertNotNull(scheduled.get("delayMs"));
        assertNotNull(scheduled.get("gen"));
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
            JsonParser.parseString("{\"code\":103,\"message\":\"permission denied for alice\"}").getAsJsonObject()
        );

        client.subscribe("user:1", null);

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
        JsonObject command = sessions.get(0).nextCommand();
        assertEquals(1, command.get("id").getAsInt());
        JsonObject connect = command.getAsJsonObject("connect");
        assertEquals("tok-1", connect.get("token").getAsString());
        assertEquals("impulsem-android", connect.get("name").getAsString());
        assertTrue(connect.has("version"));
        recorder.expect("connected");
    }


    @Test
    public void subscribeSendsTagFilterOnChannelLaneOnly() throws Exception {
        client.connect();
        client.subscribe("channel:5", "tagA");
        client.subscribe("user:1", null);
        Session session = sessions.get(0);
        JsonObject channelSub = session.nextCommandOf("subscribe").getAsJsonObject("subscribe");
        JsonObject userSub = session.nextCommandOf("subscribe").getAsJsonObject("subscribe");
        assertEquals("channel:5", channelSub.get("channel").getAsString());
        JsonObject tf = channelSub.getAsJsonObject("tf");
        assertEquals("actor", tf.get("key").getAsString());
        assertEquals("neq", tf.get("cmp").getAsString());
        assertEquals("tagA", tf.get("val").getAsString());
        assertFalse(channelSub.has("recover"));
        assertEquals("user:1", userSub.get("channel").getAsString());
        assertFalse(userSub.has("tf"));
        recorder.expect("subscribed:channel:5:false:false");
        recorder.expect("subscribed:user:1:false:false");
        assertTrue(client.subscribedChannels().contains("channel:5"));
        assertTrue(client.subscribedChannels().contains("user:1"));
    }


    @Test
    public void publicationReachesListenerWithParsedEnvelope() throws Exception {
        client.connect();
        client.subscribe("user:1", null);
        recorder.expect("subscribed:user:1:false:false");
        sessions.get(0).socket.send(envelopeFrame("user:1", 813L));
        Publication publication = recorder.publications.poll(WaitMillis, TimeUnit.MILLISECONDS);
        assertNotNull(publication);
        assertEquals("user:1", publication.channel);
        assertEquals(813L, publication.offset);
        assertEquals(1, publication.envelope.type);
        assertEquals(5L, publication.envelope.pts);
        assertArrayEquals("hello".getBytes(), publication.envelope.updatesProto);
    }


    @Test
    public void connectionRefreshIsSentBeforeTtl() throws Exception {
        sessions.get(0).ttl = 2;
        long start = System.currentTimeMillis();
        client.connect();
        JsonObject refresh = sessions.get(0).nextCommandOf("refresh");
        long elapsed = System.currentTimeMillis() - start;
        assertEquals("tok-2", refresh.getAsJsonObject("refresh").get("token").getAsString());
        assertEquals(2, refresh.get("id").getAsInt());
        assertTrue("refresh took " + elapsed, elapsed < 2000L);
        // The reply to the refresh schedules the next one.
        JsonObject second = sessions.get(0).nextCommandOf("refresh");
        assertEquals("tok-3", second.getAsJsonObject("refresh").get("token").getAsString());
    }


    @Test
    public void pingIsAnsweredWithPong() throws Exception {
        client.connect();
        recorder.expect("connected");
        Session session = sessions.get(0);
        session.rawFrames.clear();
        session.socket.send("{}");
        String frame = session.rawFrames.poll(WaitMillis, TimeUnit.MILLISECONDS);
        assertEquals("{}", frame);
    }


    @Test
    public void multipleObjectsInOneFrame() throws Exception {
        client.connect();
        client.subscribe("user:1", null);
        recorder.expect("subscribed:user:1:false:false");
        Session session = sessions.get(0);
        session.rawFrames.clear();
        session.socket.send("{}\n" + envelopeFrame("user:1", 11L) + "\n" + envelopeFrame("user:1", 12L) + "\n");
        Publication first = recorder.publications.poll(WaitMillis, TimeUnit.MILLISECONDS);
        Publication second = recorder.publications.poll(WaitMillis, TimeUnit.MILLISECONDS);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(11L, first.offset);
        assertEquals(12L, second.offset);
        assertEquals("{}", session.rawFrames.poll(WaitMillis, TimeUnit.MILLISECONDS));
    }


    @Test
    public void unsubscribePush2501ResubscribesWithRecover() throws Exception {
        client.connect();
        client.subscribe("user:1", null);
        recorder.expect("subscribed:user:1:false:false");
        Session session = sessions.get(0);
        session.socket.send(envelopeFrame("user:1", 813L));
        assertNotNull(recorder.publications.poll(WaitMillis, TimeUnit.MILLISECONDS));
        session.commands.clear();
        session.socket.send("{\"push\":{\"channel\":\"user:1\",\"unsubscribe\":{\"code\":2501,\"reason\":\"insufficient state\"}}}");
        JsonObject resubscribe = session.nextCommandOf("subscribe").getAsJsonObject("subscribe");
        assertEquals("user:1", resubscribe.get("channel").getAsString());
        assertTrue(resubscribe.get("recover").getAsBoolean());
        assertEquals(813L, resubscribe.get("offset").getAsLong());
        assertEquals("ep1", resubscribe.get("epoch").getAsString());
        recorder.expect("subscribed:user:1:true:true");
    }


    @Test
    public void unsubscribePush2502DropsPositionFirst() throws Exception {
        client.connect();
        client.subscribe("user:1", null);
        recorder.expect("subscribed:user:1:false:false");
        Session session = sessions.get(0);
        session.socket.send(envelopeFrame("user:1", 813L));
        assertNotNull(recorder.publications.poll(WaitMillis, TimeUnit.MILLISECONDS));
        session.commands.clear();
        session.socket.send("{\"push\":{\"channel\":\"user:1\",\"unsubscribe\":{\"code\":2502,\"reason\":\"state invalidated\"}}}");
        JsonObject resubscribe = session.nextCommandOf("subscribe").getAsJsonObject("subscribe");
        assertFalse(resubscribe.has("recover"));
        assertFalse(resubscribe.has("offset"));
        assertFalse(resubscribe.has("epoch"));
        recorder.expect("subscribed:user:1:false:false");
    }


    @Test
    public void unsubscribePush2000ForgetsTheChannel() throws Exception {
        client.connect();
        client.subscribe("channel:9", "t");
        recorder.expect("subscribed:channel:9:false:false");
        Session session = sessions.get(0);
        session.commands.clear();
        session.socket.send("{\"push\":{\"channel\":\"channel:9\",\"unsubscribe\":{\"code\":2000,\"reason\":\"unsubscribed\"}}}");
        recorder.expect("unsubscribed:channel:9:2000");
        Thread.sleep(300L);
        assertTrue(session.commands.isEmpty());
        assertFalse(client.subscribedChannels().contains("channel:9"));
    }


    @Test
    public void close3005ReconnectsWithFreshToken() throws Exception {
        client.connect();
        client.subscribe("user:1", null);
        recorder.expect("subscribed:user:1:false:false");
        sessions.get(0).socket.close(3005, "connection expired");
        JsonObject connect = sessions.get(1).nextCommand();
        assertEquals("tok-2", connect.getAsJsonObject("connect").get("token").getAsString());
        assertEquals(1, connect.get("id").getAsInt());
        JsonObject resubscribe = sessions.get(1).nextCommandOf("subscribe").getAsJsonObject("subscribe");
        assertEquals("user:1", resubscribe.get("channel").getAsString());
        assertEquals(2, tokenCounter.get());
    }


    @Test
    public void connectAgainAfterDisconnectOpensANewConnection() throws Exception {
        client.connect();
        recorder.expect("connected");
        client.disconnect();
        recorder.expect("disconnected:1000:false");
        client.connect();
        JsonObject connect = sessions.get(1).nextCommand();
        assertEquals("tok-2", connect.getAsJsonObject("connect").get("token").getAsString());
        assertEquals(1, connect.get("id").getAsInt());
    }


    @Test
    public void garbagePublicationIsDeliveredUndecodableAndDoesNotKillTheSocket() throws Exception {
        client.connect();
        client.subscribe("user:1", null);
        recorder.expect("subscribed:user:1:false:false");
        Session session = sessions.get(0);
        session.socket.send("{\"push\":{\"channel\":\"user:1\",\"pub\":{\"data\":\"not an object\",\"offset\":31}}}");
        session.socket.send("{\"push\":{\"channel\":\"user:1\",\"pub\":{\"data\":{\"type\":1,\"pts\":\"zzz\",\"data\":\"aGVsbG8=\"},\"offset\":\"abc\"}}}");
        session.socket.send("{\"push\":{\"channel\":\"user:1\",\"pub\":{\"data\":{\"type\":1,\"pts\":1,\"data\":\"@@@\"},\"offset\":33}}}");
        session.socket.send("{\"push\":{\"channel\":[1,2]}}");
        session.socket.send(envelopeFrame("user:1", 34L));
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
        client.setPingGraceMillis(300L);
        sessions.get(0).pingSeconds = 1;
        client.connect();
        recorder.expect("connected");
        long start = System.currentTimeMillis();
        recorder.expect("disconnected:1006:true");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("watchdog fired after " + elapsed, elapsed >= 900L && elapsed < 4000L);
        JsonObject connect = sessions.get(1).nextCommand();
        assertEquals("tok-2", connect.getAsJsonObject("connect").get("token").getAsString());
    }


    @Test
    public void pingsKeepTheWatchdogQuiet() throws Exception {
        client.setPingGraceMillis(400L);
        sessions.get(0).pingSeconds = 1;
        client.connect();
        recorder.expect("connected");
        for (int i = 0; i < 6; i++) {
            Thread.sleep(500L);
            sessions.get(0).socket.send("{}");
        }
        assertTrue(recorder.willReconnect.isEmpty());
        assertEquals(1, server.getRequestCount());
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
    public void changedActorTagLeavesAndResubscribesWithNewFilter() throws Exception {
        client.connect();
        client.subscribe("channel:5", "tagA");
        recorder.expect("subscribed:channel:5:false:false");
        Session session = sessions.get(0);
        session.commands.clear();
        client.subscribe("channel:5", "tagA");
        client.subscribe("channel:5", "tagB");
        JsonObject leave = session.nextCommandOf("unsubscribe");
        assertEquals("channel:5", leave.getAsJsonObject("unsubscribe").get("channel").getAsString());
        JsonObject again = session.nextCommandOf("subscribe").getAsJsonObject("subscribe");
        assertEquals("tagB", again.getAsJsonObject("tf").get("val").getAsString());
        assertFalse(again.has("recover"));
        recorder.expect("subscribed:channel:5:false:false");
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
        client.subscribe("user:1", null);
        recorder.expect("subscribed:user:1:false:false");
        Session first = sessions.get(0);
        first.socket.send(envelopeFrame("user:1", 813L));
        assertNotNull(recorder.publications.poll(WaitMillis, TimeUnit.MILLISECONDS));
        abort(0);
        recorder.expect("disconnected:1006:true");
        JsonObject connect = sessions.get(1).nextCommand();
        assertEquals("tok-2", connect.getAsJsonObject("connect").get("token").getAsString());
        JsonObject resubscribe = sessions.get(1).nextCommandOf("subscribe").getAsJsonObject("subscribe");
        assertTrue(resubscribe.get("recover").getAsBoolean());
        assertEquals(813L, resubscribe.get("offset").getAsLong());
        assertEquals("ep1", resubscribe.get("epoch").getAsString());
        recorder.expect("subscribed:user:1:true:true");
    }


    @Test
    public void recoveredPublicationsInSubscribeReplyAreDelivered() throws Exception {
        sessions.get(1).subscribeReplyExtra = ",\"publications\":["
            + "{\"data\":{\"type\":1,\"pts\":6,\"ptsCount\":1,\"date\":1,\"data\":\"aGVsbG8=\"},\"offset\":21},"
            + "{\"data\":{\"type\":1,\"pts\":7,\"ptsCount\":1,\"date\":1,\"data\":\"aGVsbG8=\"},\"offset\":22}]";
        client.connect();
        client.subscribe("user:1", null);
        recorder.expect("subscribed:user:1:false:false");
        sessions.get(0).socket.send(envelopeFrame("user:1", 20L));
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
        sessions.get(0).subscribeErrors.add(JsonParser.parseString("{\"code\":100,\"message\":\"internal server error\",\"temporary\":true}").getAsJsonObject());
        client.connect();
        client.subscribe("user:1", null);
        Session session = sessions.get(0);
        JsonObject first = session.nextCommandOf("subscribe");
        JsonObject second = session.nextCommandOf("subscribe");
        assertTrue(second.get("id").getAsInt() > first.get("id").getAsInt());
        recorder.expect("subscribed:user:1:false:false");
    }


    @Test
    public void fatalSubscribeErrorReportsUnsubscribe() throws Exception {
        sessions.get(0).subscribeErrors.add(JsonParser.parseString("{\"code\":103,\"message\":\"permission denied\"}").getAsJsonObject());
        client.connect();
        client.subscribe("channel:77", "t");
        recorder.expect("unsubscribed:channel:77:103");
        assertFalse(client.subscribedChannels().contains("channel:77"));
    }


    @Test
    public void explicitUnsubscribeSendsCommandAndForgets() throws Exception {
        client.connect();
        client.subscribe("user:1", null);
        recorder.expect("subscribed:user:1:false:false");
        client.unsubscribe("user:1");
        JsonObject command = sessions.get(0).nextCommandOf("unsubscribe");
        assertEquals("user:1", command.getAsJsonObject("unsubscribe").get("channel").getAsString());
        assertFalse(client.subscribedChannels().contains("user:1"));
    }
}
