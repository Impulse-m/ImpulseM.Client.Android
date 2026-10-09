package net.impulsem.transport.realtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.impulsem.transport.logging.LogLevel;
import net.impulsem.transport.logging.TraceSink;
import net.impulsem.transport.rpc.SessionLostException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;


/**
 * Hand-written Centrifugo client (JSON protocol) over an OkHttp WebSocket.
 *
 * <p>All state lives behind one lock. Frames are processed on the OkHttp reader thread, in order. Anything
 * that can block (minting a token) or that must not run on the reader (resubscribing after a server
 * unsubscribe) goes through the supplied executor. Listener callbacks are never invoked with the lock held.
 *
 * <p>Requirements on the caller:
 * <ul>
 * <li>The executor needs at least 2 threads: a token fetch blocks one while timers and resubscribes need another.</li>
 * <li>The 32 channel-lane cap is the caller's responsibility; this client does not enforce it.</li>
 * <li>Liveness is detected with a watchdog on the server's application-level {@code {}} pings (interval taken
 * from the connect reply, 25 s by default, plus a 10 s grace). The supplied {@code OkHttpClient} should not set
 * {@code pingInterval}: it is not needed, and its coexistence with Centrifugo's own pings was not verified.</li>
 * <li>Publications that cannot be decoded are still delivered, with {@code envelope.undecodable() == true}.</li>
 * </ul>
 */
public final class CentrifugoClient {

    public interface TokenProvider {

        String fetchToken() throws IOException;
    }


    private interface ReplyHandler {

        void onSuccess(JsonObject reply);


        void onError(
            int code,
            String message
        );
    }


    private static final Logger Log = Logger.getLogger(CentrifugoClient.class.getName());

    private static final String ClientName = "impulsem-android";
    private static final long ReplyTimeoutMillis = 30000L;
    private static final long[] TemporaryRetryDelaysMillis = {250L, 500L, 1000L};
    private static final int TemporaryErrorCode = 100;
    private static final int ConnectionExpiredCloseCode = 3005;
    private static final int ResubscribeFromUnsubscribeCode = 2500;
    private static final int StateInvalidatedUnsubscribeCode = 2502;
    private static final int AbnormalCloseCode = 1006;
    private static final long TokenRetryMillis = 2000L;
    private static final int RefreshMarginSeconds = 30;
    private static final int DefaultPingSeconds = 25;
    private static final long DefaultPingGraceMillis = 10000L;


    private static final class Pending {

        final int id;
        final JsonObject body;
        final ReplyHandler handler;
        final int attempt;
        ScheduledFuture<?> timeout;


        Pending(
            int id,
            JsonObject body,
            ReplyHandler handler,
            int attempt
        ) {
            this.id = id;
            this.body = body;
            this.handler = handler;
            this.attempt = attempt;
        }
    }


    private static final class ChannelState {

        final String channel;
        String actorTag;
        boolean resubscribeAfterReply;
        long offset;
        String epoch;
        boolean subscribed;
        boolean inFlight;


        ChannelState(
            String channel,
            String actorTag
        ) {
            this.channel = channel;
            this.actorTag = actorTag;
        }


        boolean canRecover() {
            return epoch != null && !epoch.isEmpty();
        }


        void dropPosition() {
            offset = 0L;
            epoch = null;
        }
    }


    private final OkHttpClient http;
    private final String wsUrl;
    private final TokenProvider tokens;
    private final CentrifugoListener listener;
    private final ScheduledExecutorService executor;
    private final Backoff backoff;
    private final Object lock = new Object();

    private final Map<String, ChannelState> channels = new LinkedHashMap<String, ChannelState>();
    private final Map<Integer, Pending> pending = new HashMap<Integer, Pending>();

    private volatile String appVersion = "1.0";
    private volatile String logLabel = "";
    private volatile TraceSink traceSink = TraceSink.None;

    private boolean wantConnected;
    private boolean connecting;
    private boolean immediateReconnect;
    private int attemptSerial;
    private boolean connected;
    private int generation;
    private int nextId;
    private WebSocket socket;
    private ScheduledFuture<?> refreshTask;
    private ScheduledFuture<?> reconnectTask;
    private ScheduledFuture<?> watchdogTask;
    private long lastFrameAt;
    private long watchdogWindowMillis;
    private long pingGraceMillis = DefaultPingGraceMillis;


    public CentrifugoClient(
        OkHttpClient http,
        String wsUrl,
        TokenProvider tokens,
        CentrifugoListener listener,
        ScheduledExecutorService executor
    ) {
        this(http, wsUrl, tokens, listener, executor, new Backoff());
    }


    CentrifugoClient(
        OkHttpClient http,
        String wsUrl,
        TokenProvider tokens,
        CentrifugoListener listener,
        ScheduledExecutorService executor,
        Backoff backoff
    ) {
        this.http = http;
        this.wsUrl = wsUrl;
        this.tokens = tokens;
        this.listener = listener;
        this.executor = executor;
        this.backoff = backoff;
    }


    /** Test hook: how long past the server ping interval silence is tolerated. */
    void setPingGraceMillis(long pingGraceMillis) {
        this.pingGraceMillis = pingGraceMillis;
    }


    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }


    /** Prefixes this client's log lines, e.g. "account=0", so the connections of several accounts can be told apart. */
    public void setLogLabel(String logLabel) {
        this.logLabel = logLabel == null ? "" : logLabel;
    }


    /** Where the structured connection-lifecycle traces go; none by default. */
    public void setTraceSink(TraceSink traceSink) {
        this.traceSink = traceSink == null ? TraceSink.None : traceSink;
    }


    /** Starts connecting; the client keeps reconnecting until {@link #disconnect()} or a terminal close. */
    public void connect() {
        synchronized (lock) {
            if (wantConnected) {
                return;
            }
            wantConnected = true;
            immediateReconnect = false;
        }
        connectAsync();
    }


    /**
     * Connects at once when a reconnect is waiting on its backoff timer, or when the open socket has been silent for
     * longer than the watchdog window (a process freeze leaves a dead socket that still looks open). Does nothing when
     * the client does not want a connection, or when the socket is healthy or a connect attempt is in flight.
     */
    public void reconnectNow() {
        int staleGen = -1;
        synchronized (lock) {
            if (!wantConnected) {
                return;
            }
            if (socket != null) {
                if (!connected || System.currentTimeMillis() - lastFrameAt <= watchdogWindowMillis) {
                    return;
                }
                staleGen = generation;
                immediateReconnect = true;
                backoff.reset();
            } else {
                if (connecting || reconnectTask == null) {
                    return;
                }
                reconnectTask.cancel(false);
                reconnectTask = null;
                backoff.reset();
            }
        }
        if (staleGen >= 0) {
            Log.log(Level.WARNING, "socket silent beyond the watchdog window on resume, reconnecting");
            trace(
                LogLevel.INFO,
                "REALTIME_RECONNECT_NOW",
                "gen=" + staleGen + " cause=silent_socket",
                "gen", staleGen,
                "cause", "silent_socket"
            );
            // handleClosed sees immediateReconnect and starts the next attempt without a delay.
            forceClose(staleGen);
        } else {
            trace(
                LogLevel.INFO,
                "REALTIME_RECONNECT_NOW",
                "cause=backoff_skipped",
                "cause", "backoff_skipped"
            );
            connectAsync();
        }
    }


    public void disconnect() {
        WebSocket closing;
        boolean notify;
        synchronized (lock) {
            notify = wantConnected;
            wantConnected = false;
            connected = false;
            generation++;
            closing = socket;
            socket = null;
            cancelRefreshLocked();
            cancelWatchdogLocked();
            if (reconnectTask != null) {
                reconnectTask.cancel(false);
                reconnectTask = null;
            }
            clearPendingLocked();
            resetChannelFlagsLocked();
            // Orphans a token fetch that is still in flight, so a later connect() starts cleanly.
            connecting = false;
            immediateReconnect = false;
            attemptSerial++;
        }
        if (closing != null) {
            closing.close(1000, "client disconnect");
        }
        if (notify) {
            fireDisconnected(1000, "client disconnect", false);
        }
    }


    /**
     * Declares interest in a channel. The subscription is sent now when connected, and again after every
     * reconnect. Pass the account's own actor tag for {@code channel:} lanes and null for {@code user:}.
     */
    public void subscribe(
        String channel,
        String actorTagOrNull
    ) {
        ChannelState state;
        int gen;
        boolean retag = false;
        synchronized (lock) {
            state = channels.get(channel);
            if (state != null) {
                boolean same = state.actorTag == null ? actorTagOrNull == null : state.actorTag.equals(actorTagOrNull);
                if (same) {
                    return;
                }
                state.actorTag = actorTagOrNull;
                if (!connected) {
                    return;
                }
                if (state.inFlight) {
                    state.resubscribeAfterReply = true;
                    return;
                }
                if (!state.subscribed) {
                    return;
                }
                retag = true;
            } else {
                state = new ChannelState(channel, actorTagOrNull);
                channels.put(channel, state);
                if (!connected) {
                    return;
                }
            }
            gen = generation;
        }
        if (retag) {
            resubscribeWithNewTag(gen, state);
        } else {
            sendSubscribe(gen, state);
        }
    }


    /** A subscription's tag filter cannot change in place: leave the channel, then subscribe again without a position. */
    private void resubscribeWithNewTag(
        final int gen,
        final ChannelState state
    ) {
        synchronized (lock) {
            if (gen != generation || channels.get(state.channel) != state) {
                return;
            }
            state.subscribed = false;
            state.inFlight = true;
            state.dropPosition();
        }
        JsonObject inner = new JsonObject();
        inner.addProperty("channel", state.channel);
        JsonObject body = new JsonObject();
        body.add("unsubscribe", inner);
        ReplyHandler next = new ReplyHandler() {
            @Override
            public void onSuccess(JsonObject reply) {
                proceed();
            }


            @Override
            public void onError(
                int code,
                String message
            ) {
                proceed();
            }


            private void proceed() {
                synchronized (lock) {
                    state.inFlight = false;
                }
                sendSubscribe(gen, state);
            }
        };
        sendCommand(gen, body, next, 0);
    }


    public void unsubscribe(String channel) {
        int gen = -1;
        synchronized (lock) {
            ChannelState state = channels.remove(channel);
            if (state != null && connected && (state.subscribed || state.inFlight)) {
                gen = generation;
            }
        }
        if (gen >= 0) {
            JsonObject body = new JsonObject();
            JsonObject inner = new JsonObject();
            inner.addProperty("channel", channel);
            body.add("unsubscribe", inner);
            sendCommand(gen, body, new ReplyHandler() {
                @Override
                public void onSuccess(JsonObject reply) {
                }


                @Override
                public void onError(
                    int code,
                    String message
                ) {
                }
            }, 0);
        }
    }


    /** The channels this client wants to hold: confirmed, in flight, or waiting for the next connection. */
    public Set<String> subscribedChannels() {
        synchronized (lock) {
            return new LinkedHashSet<String>(channels.keySet());
        }
    }


    // Connection lifecycle.

    private void connectAsync() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                attemptConnect();
            }
        });
    }


    private void attemptConnect() {
        final int serial;
        synchronized (lock) {
            if (!wantConnected || connecting) {
                return;
            }
            connecting = true;
            reconnectTask = null;
            serial = attemptSerial;
        }
        trace(
            LogLevel.INFO,
            "REALTIME_CONNECTING",
            "attempt=" + serial,
            "attempt", serial
        );
        String token;
        try {
            token = tokens.fetchToken();
        } catch (SessionLostException e) {
            synchronized (lock) {
                if (serial != attemptSerial) {
                    return;
                }
                connecting = false;
                wantConnected = false;
            }
            fireDisconnected(0, "session lost: " + e.getMessage(), false);
            return;
        } catch (IOException e) {
            failedAttempt(serial, "token: " + e.getMessage());
            return;
        } catch (RuntimeException e) {
            failedAttempt(serial, "token: " + e);
            return;
        }
        synchronized (lock) {
            if (!wantConnected || serial != attemptSerial) {
                return;
            }
            generation++;
            nextId = 1;
            connected = false;
            Request request = new Request.Builder().url(wsUrl).build();
            socket = http.newWebSocket(request, new SocketEvents(generation, token));
        }
    }


    private void failedAttempt(
        int serial,
        String reason
    ) {
        long delay;
        synchronized (lock) {
            if (serial != attemptSerial) {
                return;
            }
            connecting = false;
            if (!wantConnected) {
                return;
            }
            delay = backoff.nextDelayMillis();
            reconnectTask = executor.schedule(new Runnable() {
                @Override
                public void run() {
                    attemptConnect();
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
        trace(
            LogLevel.WARN,
            "REALTIME_RECONNECT_SCHEDULED",
            "delayMs=" + delay + " cause=connect_failed reason=" + reason,
            "attempt", serial,
            "delayMs", delay,
            "cause", "connect_failed"
        );
        fireDisconnected(AbnormalCloseCode, reason, true);
    }


    private final class SocketEvents extends WebSocketListener {

        private final int gen;
        private final String token;


        SocketEvents(
            int gen,
            String token
        ) {
            this.gen = gen;
            this.token = token;
        }


        @Override
        public void onOpen(
            WebSocket webSocket,
            Response response
        ) {
            JsonObject connect = new JsonObject();
            connect.addProperty("token", token);
            connect.addProperty("name", ClientName);
            connect.addProperty("version", appVersion);
            JsonObject body = new JsonObject();
            body.add("connect", connect);
            sendCommand(gen, body, new ReplyHandler() {
                @Override
                public void onSuccess(JsonObject reply) {
                    onConnectReply(gen, reply);
                }


                @Override
                public void onError(
                    int code,
                    String message
                ) {
                    forceClose(gen);
                }
            }, TemporaryRetryDelaysMillis.length);
        }


        @Override
        public void onMessage(
            WebSocket webSocket,
            String text
        ) {
            handleFrame(gen, webSocket, text);
        }


        @Override
        public void onClosing(
            WebSocket webSocket,
            int code,
            String reason
        ) {
            webSocket.close(1000, null);
            handleClosed(gen, code, reason);
        }


        @Override
        public void onClosed(
            WebSocket webSocket,
            int code,
            String reason
        ) {
            handleClosed(gen, code, reason);
        }


        @Override
        public void onFailure(
            WebSocket webSocket,
            Throwable t,
            Response response
        ) {
            handleClosed(gen, AbnormalCloseCode, String.valueOf(t.getMessage()));
        }
    }


    private void onConnectReply(
        int gen,
        JsonObject reply
    ) {
        JsonObject result = objectOf(reply, "connect");
        long pingSeconds;
        synchronized (lock) {
            if (gen != generation) {
                return;
            }
            connected = true;
            backoff.reset();
            scheduleRefreshLocked(gen, result);
            pingSeconds = longOfSafe(result, "ping");
            if (pingSeconds <= 0) {
                pingSeconds = DefaultPingSeconds;
            }
            watchdogWindowMillis = pingSeconds * 1000L + pingGraceMillis;
            lastFrameAt = System.currentTimeMillis();
            scheduleWatchdogLocked(gen, watchdogWindowMillis);
        }
        trace(
            LogLevel.INFO,
            "REALTIME_CONNECT_REPLY",
            "gen=" + gen + " pingSeconds=" + pingSeconds,
            "gen", gen,
            "pingSeconds", pingSeconds
        );
        fireConnected();
        resubscribeAll(gen);
    }


    private void resubscribeAll(int gen) {
        List<ChannelState> due = new ArrayList<ChannelState>();
        synchronized (lock) {
            if (gen != generation) {
                return;
            }
            for (ChannelState state : channels.values()) {
                if (!state.subscribed && !state.inFlight) {
                    due.add(state);
                }
            }
        }
        for (ChannelState state : due) {
            sendSubscribe(gen, state);
        }
    }


    private void forceClose(int gen) {
        WebSocket current;
        synchronized (lock) {
            if (gen != generation) {
                return;
            }
            current = socket;
        }
        if (current != null) {
            current.cancel();
        }
    }


    private void handleClosed(
        final int gen,
        int code,
        String reason
    ) {
        long reconnectDelay = -1L;
        boolean willReconnect;
        boolean wasConnected;
        synchronized (lock) {
            if (gen != generation || socket == null) {
                return;
            }
            wasConnected = connected;
            boolean immediate = immediateReconnect;
            immediateReconnect = false;
            socket = null;
            connected = false;
            connecting = false;
            cancelRefreshLocked();
            cancelWatchdogLocked();
            clearPendingLocked();
            resetChannelFlagsLocked();
            if (!wantConnected) {
                return;
            }
            if (isTerminalClose(code)) {
                wantConnected = false;
                willReconnect = false;
            } else {
                willReconnect = true;
                if ((code == ConnectionExpiredCloseCode && wasConnected) || immediate) {
                    reconnectDelay = 0L;
                } else {
                    reconnectDelay = backoff.nextDelayMillis();
                }
            }
        }
        trace(
            willReconnect ? LogLevel.INFO : LogLevel.WARN,
            willReconnect ? "REALTIME_RECONNECT_SCHEDULED" : "REALTIME_CLOSED",
            "gen=" + gen + " delayMs=" + reconnectDelay + " closeCode=" + code + " wasConnected=" + wasConnected
                + " terminal=" + !willReconnect + " reason=" + reason,
            "gen", gen,
            "delayMs", reconnectDelay,
            "closeCode", code,
            "wasConnected", wasConnected,
            "terminal", !willReconnect
        );
        // The listener hears about the disconnect before any reconnect can start, so onConnected never overtakes it.
        fireDisconnected(code, reason, willReconnect);
        if (reconnectDelay < 0L) {
            return;
        }
        synchronized (lock) {
            if (!wantConnected || gen != generation) {
                return;
            }
            Runnable attempt = new Runnable() {
                @Override
                public void run() {
                    attemptConnect();
                }
            };
            if (reconnectDelay == 0L) {
                executor.execute(attempt);
            } else {
                reconnectTask = executor.schedule(attempt, reconnectDelay, TimeUnit.MILLISECONDS);
            }
        }
    }


    private static boolean isTerminalClose(int code) {
        return (code >= 3500 && code <= 3999) || (code >= 4500 && code <= 4999);
    }


    // Token refresh.

    private void scheduleRefreshLocked(
        final int gen,
        JsonObject result
    ) {
        cancelRefreshLocked();
        if (!boolOf(result, "expires")) {
            return;
        }
        final int ttl = (int) longOf(result, "ttl");
        if (ttl <= 0) {
            return;
        }
        long delaySeconds = Math.max(ttl - RefreshMarginSeconds, Math.max(ttl / 2, 1));
        refreshTask = executor.schedule(new Runnable() {
            @Override
            public void run() {
                doRefresh(gen, ttl);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }


    private void doRefresh(
        final int gen,
        final int lastTtl
    ) {
        synchronized (lock) {
            if (gen != generation || !connected) {
                return;
            }
        }
        String token;
        try {
            token = tokens.fetchToken();
        } catch (IOException e) {
            retryRefreshLater(gen, lastTtl);
            return;
        } catch (RuntimeException e) {
            retryRefreshLater(gen, lastTtl);
            return;
        }
        JsonObject inner = new JsonObject();
        inner.addProperty("token", token);
        JsonObject body = new JsonObject();
        body.add("refresh", inner);
        sendCommand(gen, body, new ReplyHandler() {
            @Override
            public void onSuccess(JsonObject reply) {
                JsonObject result = objectOf(reply, "refresh");
                if (!result.has("ttl")) {
                    result.addProperty("ttl", lastTtl);
                }
                if (!result.has("expires")) {
                    result.addProperty("expires", true);
                }
                synchronized (lock) {
                    if (gen == generation && connected) {
                        scheduleRefreshLocked(gen, result);
                    }
                }
            }


            @Override
            public void onError(
                int code,
                String message
            ) {
                forceClose(gen);
            }
        }, 0);
    }


    private void retryRefreshLater(
        final int gen,
        final int lastTtl
    ) {
        synchronized (lock) {
            if (gen != generation || !connected) {
                return;
            }
            refreshTask = executor.schedule(new Runnable() {
                @Override
                public void run() {
                    doRefresh(gen, lastTtl);
                }
            }, TokenRetryMillis, TimeUnit.MILLISECONDS);
        }
    }


    private void scheduleWatchdogLocked(
        final int gen,
        long delayMillis
    ) {
        cancelWatchdogLocked();
        watchdogTask = executor.schedule(new Runnable() {
            @Override
            public void run() {
                long remaining;
                synchronized (lock) {
                    if (gen != generation || !connected) {
                        return;
                    }
                    remaining = lastFrameAt + watchdogWindowMillis - System.currentTimeMillis();
                    if (remaining > 0) {
                        scheduleWatchdogLocked(gen, remaining);
                        return;
                    }
                }
                Log.log(Level.WARNING, "no frame from the server for " + watchdogWindowMillis + " ms, reconnecting");
                forceClose(gen);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }


    private void cancelWatchdogLocked() {
        if (watchdogTask != null) {
            watchdogTask.cancel(false);
            watchdogTask = null;
        }
    }


    private void cancelRefreshLocked() {
        if (refreshTask != null) {
            refreshTask.cancel(false);
            refreshTask = null;
        }
    }


    // Commands.

    /**
     * Sends a command and routes its reply to the handler. {@code attempt} is the number of temporary-error
     * retries already spent; a timeout drops the whole connection.
     */
    private void sendCommand(
        final int gen,
        final JsonObject body,
        final ReplyHandler handler,
        final int attempt
    ) {
        synchronized (lock) {
            if (gen != generation || socket == null) {
                return;
            }
            final int id = nextId++;
            final Pending command = new Pending(id, body, handler, attempt);
            command.timeout = executor.schedule(new Runnable() {
                @Override
                public void run() {
                    boolean expired;
                    synchronized (lock) {
                        expired = pending.remove(id) == command;
                    }
                    if (expired) {
                        forceClose(gen);
                    }
                }
            }, ReplyTimeoutMillis, TimeUnit.MILLISECONDS);
            pending.put(id, command);
            JsonObject frame = new JsonObject();
            frame.addProperty("id", id);
            for (Map.Entry<String, JsonElement> entry : body.entrySet()) {
                frame.add(entry.getKey(), entry.getValue());
            }
            socket.send(frame.toString());
        }
    }


    private void sendSubscribe(
        int gen,
        final ChannelState state
    ) {
        final boolean recover;
        JsonObject inner = new JsonObject();
        synchronized (lock) {
            if (gen != generation || !connected || channels.get(state.channel) != state || state.inFlight) {
                return;
            }
            state.inFlight = true;
            state.subscribed = false;
            recover = state.canRecover();
            inner.addProperty("channel", state.channel);
            if (state.actorTag != null) {
                JsonObject tf = new JsonObject();
                tf.addProperty("key", "actor");
                tf.addProperty("cmp", "neq");
                tf.addProperty("val", state.actorTag);
                inner.add("tf", tf);
            }
            if (recover) {
                inner.addProperty("recover", true);
                inner.addProperty("offset", state.offset);
                inner.addProperty("epoch", state.epoch);
            }
        }
        trace(
            LogLevel.INFO,
            "REALTIME_SUBSCRIBE_SENT",
            "gen=" + gen + " lane=" + laneKind(state.channel) + " recover=" + recover,
            "gen", gen,
            "lane", laneKind(state.channel),
            "wasRecovering", recover
        );
        JsonObject body = new JsonObject();
        body.add("subscribe", inner);
        final int sentGen = gen;
        sendCommand(gen, body, new ReplyHandler() {
            @Override
            public void onSuccess(JsonObject reply) {
                onSubscribeReply(sentGen, state, recover, reply);
            }


            @Override
            public void onError(
                int code,
                String message
            ) {
                boolean current;
                synchronized (lock) {
                    state.inFlight = false;
                    current = channels.get(state.channel) == state;
                    if (current) {
                        channels.remove(state.channel);
                    }
                }
                trace(
                    LogLevel.WARN,
                    "REALTIME_SUBSCRIBE_FAILED",
                    "gen=" + sentGen + " lane=" + laneKind(state.channel) + " errorCode=" + code + " error=" + message,
                    "gen", sentGen,
                    "lane", laneKind(state.channel),
                    "errorCode", code
                );
                if (current) {
                    fireUnsubscribed(state.channel, code, message);
                }
            }
        }, 0);
    }


    private void onSubscribeReply(
        int gen,
        ChannelState state,
        boolean wasRecovering,
        JsonObject reply
    ) {
        JsonObject result = objectOf(reply, "subscribe");
        boolean recovered = boolOf(result, "recovered");
        List<Publication> replayed = new ArrayList<Publication>();
        synchronized (lock) {
            state.inFlight = false;
            if (gen != generation || channels.get(state.channel) != state) {
                return;
            }
            state.subscribed = true;
            String epoch = stringOf(result, "epoch");
            if (epoch != null && !epoch.isEmpty()) {
                state.epoch = epoch;
                state.offset = longOf(result, "offset");
            } else {
                state.dropPosition();
            }
            JsonElement publications = result.get("publications");
            if (publications != null && publications.isJsonArray()) {
                for (JsonElement element : publications.getAsJsonArray()) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    Publication publication = toPublication(state.channel, element.getAsJsonObject());
                    if (publication != null) {
                        replayed.add(publication);
                        if (publication.offset > state.offset) {
                            state.offset = publication.offset;
                        }
                    }
                }
            }
        }
        trace(
            LogLevel.INFO,
            "REALTIME_SUBSCRIBE_REPLY",
            "gen=" + gen + " lane=" + laneKind(state.channel) + " recovered=" + recovered + " wasRecovering=" + wasRecovering
                + " replayed=" + replayed.size(),
            "gen", gen,
            "lane", laneKind(state.channel),
            "recovered", recovered,
            "wasRecovering", wasRecovering,
            "replayed", replayed.size()
        );
        fireSubscribed(state.channel, recovered, wasRecovering);
        for (Publication publication : replayed) {
            firePublication(publication);
        }
        boolean retag;
        synchronized (lock) {
            retag = state.resubscribeAfterReply && gen == generation && channels.get(state.channel) == state;
            state.resubscribeAfterReply = false;
        }
        if (retag) {
            resubscribeWithNewTag(gen, state);
        }
    }


    // Inbound frames.

    private void handleFrame(
        int gen,
        WebSocket webSocket,
        String text
    ) {
        synchronized (lock) {
            if (gen == generation) {
                lastFrameAt = System.currentTimeMillis();
            }
        }
        for (String line : text.split("\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                JsonElement parsed = JsonParser.parseString(line);
                if (!parsed.isJsonObject()) {
                    continue;
                }
                JsonObject object = parsed.getAsJsonObject();
                if (object.size() == 0) {
                    webSocket.send("{}");
                } else if (object.has("id")) {
                    handleReply(gen, object);
                } else if (object.has("push")) {
                    handlePush(gen, objectOf(object, "push"));
                }
            } catch (RuntimeException e) {
                // Nothing may escape to OkHttp's reader thread: it would tear the connection down.
                Log.log(Level.WARNING, "realtime frame object skipped", e);
            }
        }
    }


    private void handleReply(
        final int gen,
        final JsonObject reply
    ) {
        int id = (int) longOf(reply, "id");
        final Pending command;
        synchronized (lock) {
            if (gen != generation) {
                return;
            }
            command = pending.remove(id);
            if (command != null && command.timeout != null) {
                command.timeout.cancel(false);
            }
        }
        if (command == null) {
            return;
        }
        if (!reply.has("error")) {
            command.handler.onSuccess(reply);
            return;
        }
        JsonObject error = objectOf(reply, "error");
        int code = (int) longOf(error, "code");
        String message = stringOf(error, "message");
        boolean temporary = code == TemporaryErrorCode || boolOf(error, "temporary");
        if (temporary && command.attempt < TemporaryRetryDelaysMillis.length) {
            long delay = TemporaryRetryDelaysMillis[command.attempt];
            executor.schedule(new Runnable() {
                @Override
                public void run() {
                    sendCommand(gen, command.body, command.handler, command.attempt + 1);
                }
            }, delay, TimeUnit.MILLISECONDS);
            return;
        }
        command.handler.onError(code, message == null ? "" : message);
    }


    private void handlePush(
        final int gen,
        JsonObject push
    ) {
        String channel = stringOf(push, "channel");
        if (channel == null) {
            return;
        }
        if (push.has("pub")) {
            Publication publication = toPublication(channel, objectOf(push, "pub"));
            if (publication == null) {
                return;
            }
            synchronized (lock) {
                ChannelState state = channels.get(channel);
                if (gen != generation) {
                    return;
                }
                if (state != null && publication.offset > state.offset) {
                    state.offset = publication.offset;
                }
            }
            firePublication(publication);
        } else if (push.has("unsubscribe")) {
            handleServerUnsubscribe(gen, channel, objectOf(push, "unsubscribe"));
        }
    }


    private void handleServerUnsubscribe(
        final int gen,
        final String channel,
        JsonObject unsubscribe
    ) {
        int code = (int) longOf(unsubscribe, "code");
        String reason = stringOf(unsubscribe, "reason");
        final ChannelState state;
        synchronized (lock) {
            if (gen != generation) {
                return;
            }
            state = channels.get(channel);
            if (state == null) {
                return;
            }
            state.subscribed = false;
            state.inFlight = false;
            if (code < ResubscribeFromUnsubscribeCode) {
                channels.remove(channel);
            } else if (code == StateInvalidatedUnsubscribeCode) {
                state.dropPosition();
            }
        }
        fireUnsubscribed(channel, code, reason == null ? "" : reason);
        if (code >= ResubscribeFromUnsubscribeCode) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    sendSubscribe(gen, state);
                }
            });
        }
    }


    /** Always yields a publication; an unreadable envelope is flagged so the app can run getDifference. */
    private static Publication toPublication(
        String channel,
        JsonObject pub
    ) {
        return new Publication(channel, Envelope.parse(pub.get("data")), longOfSafe(pub, "offset"));
    }


    // State helpers.

    private void clearPendingLocked() {
        for (Pending command : pending.values()) {
            if (command.timeout != null) {
                command.timeout.cancel(false);
            }
        }
        pending.clear();
    }


    private void resetChannelFlagsLocked() {
        for (ChannelState state : channels.values()) {
            state.subscribed = false;
            state.inFlight = false;
        }
    }


    // Listener dispatch, always outside the lock.

    private void fireConnected() {
        try {
            listener.onConnected();
        } catch (RuntimeException e) {
            Log.log(Level.WARNING, "listener.onConnected failed", e);
        }
    }


    private void fireSubscribed(
        String channel,
        boolean recovered,
        boolean wasRecovering
    ) {
        try {
            listener.onSubscribed(channel, recovered, wasRecovering);
        } catch (RuntimeException e) {
            Log.log(Level.WARNING, "listener.onSubscribed failed", e);
        }
    }


    private void firePublication(Publication publication) {
        try {
            listener.onPublication(publication);
        } catch (RuntimeException e) {
            Log.log(Level.WARNING, "listener.onPublication failed", e);
        }
    }


    private void fireUnsubscribed(
        String channel,
        int code,
        String reason
    ) {
        try {
            listener.onUnsubscribed(channel, code, reason);
        } catch (RuntimeException e) {
            Log.log(Level.WARNING, "listener.onUnsubscribed failed", e);
        }
    }


    private void fireDisconnected(
        int code,
        String reason,
        boolean willReconnect
    ) {
        try {
            listener.onDisconnected(code, reason, willReconnect);
        } catch (RuntimeException e) {
            Log.log(Level.WARNING, "listener.onDisconnected failed", e);
        }
    }


    /**
     * One connection-lifecycle event. The local log line carries the event name first, so it can be grepped, then the
     * label and the free-text details; the sink gets only the typed fields, never the server's reason or message.
     */
    private void trace(
        LogLevel level,
        String event,
        String details,
        Object... fields
    ) {
        traceSink.trace(level, event, fields);
        if (Log.isLoggable(Level.INFO)) {
            Log.info(event + " " + logLabel + " " + details);
        }
    }


    private static String laneKind(String channel) {
        int separator = channel.indexOf(':');
        return separator < 0 ? channel : channel.substring(0, separator);
    }


    // JSON helpers.

    private static JsonObject objectOf(
        JsonObject parent,
        String name
    ) {
        JsonElement element = parent.get(name);
        if (element != null && element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        return new JsonObject();
    }


    private static long longOfSafe(
        JsonObject object,
        String name
    ) {
        try {
            return longOf(object, name);
        } catch (RuntimeException e) {
            return 0L;
        }
    }


    private static long longOf(
        JsonObject object,
        String name
    ) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            return 0L;
        }
        return element.getAsLong();
    }


    private static boolean boolOf(
        JsonObject object,
        String name
    ) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }


    private static String stringOf(
        JsonObject object,
        String name
    ) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }
}
