package net.impulsem.transport.realtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final long BackoffMillis = 30000L;


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
    private final CopyOnWriteArrayList<java.net.Socket> rawSockets = new CopyOnWriteArrayList<java.net.Socket>();
    private final AtomicInteger tokenCounter = new AtomicInteger();
    private final Session first = new Session();
    private final Session second = new Session();
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
        server.enqueue(new MockResponse().withWebSocketUpgrade(first));
        server.enqueue(new MockResponse().withWebSocketUpgrade(second));
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
            // MockWebServer may wait on a socket the client already dropped; the result does not depend on it.
        }
    }


    @Test
    public void reconnectNowSkipsAPendingBackoff() throws Exception {
        client.connect();
        assertTrue(recorder.connected.await(WaitMillis, TimeUnit.MILLISECONDS));
        rawSockets.get(0).close();
        assertTrue(recorder.reconnecting.await(WaitMillis, TimeUnit.MILLISECONDS));
        // A 30 s backoff is now pending; without reconnectNow the second connection would not come within the wait.
        client.reconnectNow();
        assertTrue(second.connectSeen.await(WaitMillis, TimeUnit.MILLISECONDS));
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
