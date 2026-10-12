package net.impulsem.transport.realtime;

import com.google.protobuf.MessageLite;
import io.github.centrifugal.centrifuge.internal.protocol.Protocol;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.ByteString;


/**
 * Centrifugo fake for the realtime tests, served over MockWebServer. Every WebSocket message is a stream of
 * varint-length-delimited protobuf messages, the same framing the SDK uses on the wire.
 */
final class ProtoCentrifugoServer {

    /** The byte sent for an empty command, which is the client's answer to a ping. */
    static final byte[] EmptyFrame = new byte[] {0};
    /** Envelope JSON carried in publication data: type 1, pts 5, body "hello". */
    static final byte[] EnvelopeData = "{\"type\":1,\"pts\":5,\"ptsCount\":1,\"date\":1700000000,\"data\":\"aGVsbG8=\"}"
        .getBytes(StandardCharsets.UTF_8);

    private static final long PollMillis = 100L;
    private static final ScheduledExecutorService Expiry = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fake-centrifugo-expiry");
        thread.setDaemon(true);
        return thread;
    });


    /** One server-side connection: records commands and answers them like Centrifugo would. */
    static final class Session extends WebSocketListener {

        final BlockingQueue<Protocol.Command> commands = new LinkedBlockingQueue<Protocol.Command>();
        final BlockingQueue<ByteString> rawFrames = new LinkedBlockingQueue<ByteString>();
        final BlockingQueue<Protocol.Error> subscribeErrors = new LinkedBlockingQueue<Protocol.Error>();
        final List<Protocol.Publication> subscribeReplyPublications = new CopyOnWriteArrayList<Protocol.Publication>();
        volatile WebSocket socket;
        volatile int ttl = 60;
        volatile long subscribeOffset = 10L;
        volatile boolean replyToConnect = true;
        volatile int pingSeconds = 0;
        /** When positive, the server closes with 3005 this long after ttl unless a refresh arrived. */
        volatile int expiredCloseGraceMillis = 0;
        private volatile ScheduledFuture<?> expiry;


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
            ByteString bytes
        ) {
            rawFrames.add(bytes);
            InputStream stream = new ByteArrayInputStream(bytes.toByteArray());
            try {
                while (stream.available() > 0) {
                    Protocol.Command command = Protocol.Command.parseDelimitedFrom(stream);
                    if (command == null) {
                        return;
                    }
                    if (command.getSerializedSize() == 0) {
                        continue;
                    }
                    commands.add(command);
                    answer(webSocket, command);
                }
            } catch (IOException e) {
                throw new IllegalStateException("fake server cannot parse the client frame", e);
            }
        }


        private void answer(
            WebSocket webSocket,
            Protocol.Command command
        ) {
            int id = command.getId();
            if (command.hasConnect()) {
                if (replyToConnect) {
                    Protocol.ConnectResult.Builder result = Protocol.ConnectResult.newBuilder()
                        .setClient("c1")
                        .setExpires(true)
                        .setTtl(ttl)
                        .setPong(true);
                    if (pingSeconds > 0) {
                        result.setPing(pingSeconds);
                    }
                    send(webSocket, Protocol.Reply.newBuilder().setId(id).setConnect(result).build());
                    armExpiry(webSocket);
                }
            } else if (command.hasSubscribe()) {
                Protocol.Error error = subscribeErrors.poll();
                if (error != null) {
                    send(webSocket, Protocol.Reply.newBuilder().setId(id).setError(error).build());
                    return;
                }
                boolean recover = command.getSubscribe().getRecover();
                Protocol.SubscribeResult.Builder result = Protocol.SubscribeResult.newBuilder()
                    .setRecoverable(true)
                    .setPositioned(true)
                    .setEpoch("ep1")
                    .setOffset(subscribeOffset)
                    .setRecovered(recover && "ep1".equals(command.getSubscribe().getEpoch()))
                    .setWasRecovering(recover)
                    .addAllPublications(subscribeReplyPublications);
                send(webSocket, Protocol.Reply.newBuilder().setId(id).setSubscribe(result).build());
            } else if (command.hasRefresh()) {
                Protocol.RefreshResult result = Protocol.RefreshResult.newBuilder()
                    .setClient("c1")
                    .setExpires(true)
                    .setTtl(ttl)
                    .build();
                send(webSocket, Protocol.Reply.newBuilder().setId(id).setRefresh(result).build());
                armExpiry(webSocket);
            } else if (command.hasUnsubscribe()) {
                send(webSocket, Protocol.Reply.newBuilder()
                    .setId(id)
                    .setUnsubscribe(Protocol.UnsubscribeResult.getDefaultInstance())
                    .build());
            }
        }


        private synchronized void armExpiry(final WebSocket webSocket) {
            if (expiredCloseGraceMillis <= 0) {
                return;
            }
            ScheduledFuture<?> previous = expiry;
            if (previous != null) {
                previous.cancel(false);
            }
            expiry = Expiry.schedule(new Runnable() {
                @Override
                public void run() {
                    webSocket.close(3005, "connection expired");
                }
            }, ttl * 1000L + expiredCloseGraceMillis, TimeUnit.MILLISECONDS);
        }


        /** Waits for the next command the client sent, of any kind. */
        Protocol.Command nextCommand() throws InterruptedException {
            Protocol.Command command = commands.poll(WaitMillis, TimeUnit.MILLISECONDS);
            if (command == null) {
                throw new AssertionError("no command received");
            }
            return command;
        }


        /** Waits for the next command of the given kind; the other commands are skipped. */
        Protocol.Command nextCommandOf(CommandKind kind) throws InterruptedException {
            long deadline = System.currentTimeMillis() + WaitMillis;
            while (System.currentTimeMillis() < deadline) {
                Protocol.Command command = commands.poll(PollMillis, TimeUnit.MILLISECONDS);
                if (command != null && kind.matches(command)) {
                    return command;
                }
            }
            throw new AssertionError("no " + kind + " command");
        }


        void sendReply(Protocol.Reply reply) {
            send(socket, reply);
        }


        void sendRaw(byte[] frame) {
            socket.send(ByteString.of(frame));
        }


        void pushPublication(
            String channel,
            long offset,
            byte[] data
        ) {
            Protocol.Publication publication = Protocol.Publication.newBuilder()
                .setData(com.google.protobuf.ByteString.copyFrom(data))
                .setOffset(offset)
                .build();
            sendReply(Protocol.Reply.newBuilder()
                .setPush(Protocol.Push.newBuilder().setChannel(channel).setPub(publication))
                .build());
        }


        void pushUnsubscribe(
            String channel,
            int code,
            String reason
        ) {
            Protocol.Unsubscribe unsubscribe = Protocol.Unsubscribe.newBuilder().setCode(code).setReason(reason).build();
            sendReply(Protocol.Reply.newBuilder()
                .setPush(Protocol.Push.newBuilder().setChannel(channel).setUnsubscribe(unsubscribe))
                .build());
        }
    }


    /** The command kinds the tests wait for. */
    enum CommandKind {
        CONNECT,
        SUBSCRIBE,
        UNSUBSCRIBE,
        REFRESH;

        boolean matches(Protocol.Command command) {
            switch (this) {
                case CONNECT:
                    return command.hasConnect();
                case SUBSCRIBE:
                    return command.hasSubscribe();
                case UNSUBSCRIBE:
                    return command.hasUnsubscribe();
                default:
                    return command.hasRefresh();
            }
        }
    }


    static final long WaitMillis = 5000L;


    private ProtoCentrifugoServer() {
    }


    /** Binds a MockWebServer whose upgrades are served by the sessions, in order. */
    static MockWebServer start(List<Session> sessions) throws IOException {
        MockWebServer server = new MockWebServer();
        server.start();
        for (Session session : sessions) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(session));
        }
        return server;
    }


    static byte[] delimited(MessageLite message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            message.writeDelimitedTo(out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }


    private static void send(
        WebSocket webSocket,
        Protocol.Reply reply
    ) {
        webSocket.send(ByteString.of(delimited(reply)));
    }
}
