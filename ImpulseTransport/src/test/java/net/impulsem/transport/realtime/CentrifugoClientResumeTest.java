package net.impulsem.transport.realtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.impulsem.transport.logging.LogLevel;
import net.impulsem.transport.logging.LogRecord;
import net.impulsem.transport.logging.TraceSink;
import net.impulsem.transport.realtime.ProtoCentrifugoServer.Session;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class CentrifugoClientResumeTest {

    private static final long WaitMillis = ProtoCentrifugoServer.WaitMillis;
    private static final long ImmediateMillis = 1500L;


    private static final class Recorder implements CentrifugoListener {

        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch reconnecting = new CountDownLatch(1);


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
            if (willReconnect) {
                reconnecting.countDown();
            }
        }
    }


    private final List<Session> sessions = new CopyOnWriteArrayList<Session>();
    private final CopyOnWriteArrayList<Socket> rawSockets = new CopyOnWriteArrayList<Socket>();
    private MockWebServer server;
    private ScheduledExecutorService executor;
    private OkHttpClient http;
    private final AtomicInteger tokenCounter = new AtomicInteger();
    private final Session first = new Session();
    private final Session second = new Session();
    private final Session third = new Session();
    private Recorder recorder;
    private CentrifugoClient client;


    @Before
    public void setUp() throws IOException {
        executor = Executors.newScheduledThreadPool(2);
        sessions.add(first);
        sessions.add(second);
        sessions.add(third);
        server = ProtoCentrifugoServer.start(sessions);
        http = new OkHttpClient.Builder()
            .socketFactory(CentrifugoClientTest.trackingSocketFactory(rawSockets))
            .build();
        recorder = new Recorder();
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
            // MockWebServer may wait on a socket the client already dropped; the result does not depend on it.
        }
    }


    private void setField(
        String name,
        long value
    ) throws Exception {
        Field lockField = CentrifugoClient.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        Field field = CentrifugoClient.class.getDeclaredField(name);
        field.setAccessible(true);
        synchronized (lockField.get(client)) {
            field.setLong(client, value);
        }
    }


    /** True when the session received a connect command within the bound. */
    private static boolean connectArrives(
        Session session,
        long millis
    ) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            Protocol.Command command = session.commands.poll(50L, TimeUnit.MILLISECONDS);
            if (command != null && command.hasConnect()) {
                return true;
            }
        }
        return false;
    }


    @Test
    public void reconnectNowSkipsAPendingBackoff() throws Exception {
        client.connect();
        assertTrue(recorder.connected.await(WaitMillis, TimeUnit.MILLISECONDS));
        rawSockets.get(0).close();
        assertTrue(recorder.reconnecting.await(WaitMillis, TimeUnit.MILLISECONDS));
        client.reconnectNow();
        // The SDK's own reconnect timer fires within two seconds, so the bound only holds when reconnectNow connects.
        assertTrue("not connected at once", connectArrives(second, ImmediateMillis));
        assertEquals(2, tokenCounter.get());
        // The cancelled timer must not drive a further attempt once its delay has passed.
        Thread.sleep(4700L);
        assertEquals(2, server.getRequestCount());
        assertEquals(2, tokenCounter.get());
    }


    @Test
    public void reconnectNowTracesWhyItActed() throws Exception {
        final List<String> events = new CopyOnWriteArrayList<String>();
        final List<Object> causes = new CopyOnWriteArrayList<Object>();
        client.setTraceSink(new TraceSink() {
            @Override
            public void trace(
                LogLevel level,
                String event,
                Object... keysAndValues
            ) {
                events.add(event);
                Object cause = LogRecord.fieldsOf(keysAndValues).get("cause");
                if (event.equals("REALTIME_RECONNECT_NOW")) {
                    causes.add(cause);
                }
            }
        });
        client.connect();
        assertTrue(recorder.connected.await(WaitMillis, TimeUnit.MILLISECONDS));
        rawSockets.get(0).close();
        assertTrue(recorder.reconnecting.await(WaitMillis, TimeUnit.MILLISECONDS));
        client.reconnectNow();
        assertTrue(connectArrives(second, ImmediateMillis));
        Thread.sleep(300L);
        setField("lastFrameAt", 0L);
        client.reconnectNow();
        assertTrue(connectArrives(third, ImmediateMillis));

        assertEquals(java.util.Arrays.<Object>asList("backoff_skipped", "silent_socket"), causes);
    }


    @Test
    public void reconnectNowReplacesASilentOpenSocket() throws Exception {
        client.connect();
        assertTrue(recorder.connected.await(WaitMillis, TimeUnit.MILLISECONDS));
        // Pretend the process was frozen: no sign of life for far longer than the ping window.
        setField("lastFrameAt", 0L);
        client.reconnectNow();
        // The ping window is 25 s plus the SDK's 10 s maxServerPingDelay, so only reconnectNow can replace the
        // socket this fast; a plain reconnect would wait for the backoff.
        assertTrue("socket not replaced at once", connectArrives(second, ImmediateMillis));
        assertEquals(2, tokenCounter.get());
    }


    @Test
    public void reconnectNowWithAnOpenSocketOpensNoSecondSocket() throws Exception {
        client.connect();
        assertTrue(recorder.connected.await(WaitMillis, TimeUnit.MILLISECONDS));
        client.reconnectNow();
        Thread.sleep(500L);
        assertEquals(1, server.getRequestCount());
        assertEquals(1, tokenCounter.get());
    }


    @Test
    public void aPublicationRefreshesTheSignOfLifeSoReconnectNowKeepsTheSocket() throws Exception {
        client.connect();
        assertTrue(recorder.connected.await(WaitMillis, TimeUnit.MILLISECONDS));
        client.subscribe("user:1", null);
        Thread.sleep(300L);
        setField("lastFrameAt", 0L);
        first.pushPublication("user:1", 5L, ProtoCentrifugoServer.EnvelopeData);
        Thread.sleep(300L);
        client.reconnectNow();
        Thread.sleep(500L);
        assertEquals(1, server.getRequestCount());
        assertEquals(1, tokenCounter.get());
    }


    @Test
    public void reconnectNowAfterDisconnectDoesNothing() throws Exception {
        client.connect();
        assertTrue(recorder.connected.await(WaitMillis, TimeUnit.MILLISECONDS));
        client.disconnect();
        client.reconnectNow();
        Thread.sleep(500L);
        assertEquals(1, server.getRequestCount());
        assertEquals(1, tokenCounter.get());
    }
}
