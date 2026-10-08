package net.impulsem.transport.realtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.SocketFactory;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class CentrifugoClientResumeTest {

    private static final long WaitMillis = 5000L;
    private static final long BackoffMillis = 4000L;
    private static final long ImmediateMillis = 1500L;


    /** Answers the connect command so the client reaches the connected state. */
    private static final class Session extends WebSocketListener {

        final CountDownLatch connectSeen = new CountDownLatch(1);


        @Override
        public void onMessage(
            WebSocket webSocket,
            String text
        ) {
            if (text.contains("\"connect\"")) {
                webSocket.send("{\"id\":1,\"connect\":{\"client\":\"c1\",\"expires\":true,\"ttl\":60}}");
                connectSeen.countDown();
            }
        }
    }


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


    private MockWebServer server;
    private ScheduledExecutorService executor;
    private OkHttpClient http;
    private final CopyOnWriteArrayList<Socket> rawSockets = new CopyOnWriteArrayList<Socket>();
    private final AtomicInteger tokenCounter = new AtomicInteger();
    private final Session first = new Session();
    private final Session second = new Session();
    private final Session third = new Session();
    private Recorder recorder;
    private CentrifugoClient client;


    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        executor = Executors.newScheduledThreadPool(2);
        http = new OkHttpClient.Builder()
            .socketFactory(new SocketFactory() {
                private Socket track(Socket socket) {
                    rawSockets.add(socket);
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
            })
            .build();
        recorder = new Recorder();
        server.enqueue(new MockResponse().withWebSocketUpgrade(first));
        server.enqueue(new MockResponse().withWebSocketUpgrade(second));
        server.enqueue(new MockResponse().withWebSocketUpgrade(third));
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
            new Backoff(BackoffMillis, BackoffMillis, 0.0, new Random(3L))
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


    private Object readField(String name) throws Exception {
        Field lockField = CentrifugoClient.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        Field field = CentrifugoClient.class.getDeclaredField(name);
        field.setAccessible(true);
        synchronized (lockField.get(client)) {
            return field.get(client);
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


    private void waitForPendingTimer() throws Exception {
        long deadline = System.currentTimeMillis() + WaitMillis;
        while (readField("reconnectTask") == null) {
            assertTrue("no reconnect timer was scheduled", System.currentTimeMillis() < deadline);
            Thread.sleep(10L);
        }
    }


    @Test
    public void reconnectNowSkipsAPendingBackoff() throws Exception {
        client.connect();
        assertTrue(recorder.connected.await(WaitMillis, TimeUnit.MILLISECONDS));
        rawSockets.get(0).close();
        assertTrue(recorder.reconnecting.await(WaitMillis, TimeUnit.MILLISECONDS));
        // Wait until the backoff timer is really pending, so the cancel path is the one under test.
        waitForPendingTimer();
        client.reconnectNow();
        // The pending timer would only fire after BackoffMillis, far beyond this bound.
        assertTrue("not connected at once", second.connectSeen.await(ImmediateMillis, TimeUnit.MILLISECONDS));
        assertEquals(2, tokenCounter.get());
        // The cancelled timer must not drive a further attempt once its delay has passed.
        Thread.sleep(BackoffMillis + 700L);
        assertEquals(2, server.getRequestCount());
        assertEquals(2, tokenCounter.get());
    }


    @Test
    public void reconnectNowReplacesASilentOpenSocket() throws Exception {
        client.connect();
        assertTrue(recorder.connected.await(WaitMillis, TimeUnit.MILLISECONDS));
        // Pretend the process was frozen: no frame has arrived for far longer than the watchdog window.
        setField("lastFrameAt", 0L);
        client.reconnectNow();
        // The watchdog window is 25 s plus grace, so only reconnectNow can replace the socket this fast; a plain
        // reconnect would wait for the backoff.
        assertTrue("socket not replaced at once", second.connectSeen.await(ImmediateMillis, TimeUnit.MILLISECONDS));
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
