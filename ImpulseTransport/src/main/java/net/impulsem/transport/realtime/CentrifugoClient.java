package net.impulsem.transport.realtime;

import io.github.centrifugal.centrifuge.Client;
import io.github.centrifugal.centrifuge.ConnectedEvent;
import io.github.centrifugal.centrifuge.ConnectingEvent;
import io.github.centrifugal.centrifuge.ConnectionTokenEvent;
import io.github.centrifugal.centrifuge.ConnectionTokenGetter;
import io.github.centrifugal.centrifuge.DisconnectedEvent;
import io.github.centrifugal.centrifuge.DuplicateSubscriptionException;
import io.github.centrifugal.centrifuge.ErrorEvent;
import io.github.centrifugal.centrifuge.EventListener;
import io.github.centrifugal.centrifuge.Options;
import io.github.centrifugal.centrifuge.PublicationEvent;
import io.github.centrifugal.centrifuge.RPCResult;
import io.github.centrifugal.centrifuge.ReplyError;
import io.github.centrifugal.centrifuge.ResultCallback;
import io.github.centrifugal.centrifuge.RpcCall;
import io.github.centrifugal.centrifuge.RpcOptions;
import io.github.centrifugal.centrifuge.SubscribedEvent;
import io.github.centrifugal.centrifuge.SubscribingEvent;
import io.github.centrifugal.centrifuge.Subscription;
import io.github.centrifugal.centrifuge.SubscriptionErrorEvent;
import io.github.centrifugal.centrifuge.SubscriptionEventListener;
import io.github.centrifugal.centrifuge.SubscriptionOptions;
import io.github.centrifugal.centrifuge.SubscriptionSubscribeError;
import io.github.centrifugal.centrifuge.TokenCallback;
import io.github.centrifugal.centrifuge.UnauthorizedException;
import io.github.centrifugal.centrifuge.UnsubscribedEvent;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.impulsem.transport.logging.LogLevel;
import net.impulsem.transport.logging.TraceSink;
import net.impulsem.transport.rpc.CarrierCall;
import net.impulsem.transport.rpc.CarrierCallback;
import net.impulsem.transport.rpc.CarrierFailure;
import net.impulsem.transport.rpc.RpcCarrier;
import net.impulsem.transport.rpc.SessionLostException;
import okhttp3.OkHttpClient;


/**
 * Realtime client for the Centrifugo server, on top of the ImpulseM Protobuf SDK ({@code io.github.centrifugal.centrifuge}).
 *
 * <p>The SDK owns the socket, the handshake, the reconnect backoff, the ping watchdog, token refresh and the
 * subscription recovery. This class maps its events onto {@link CentrifugoListener}, keeps the wanted channel set, and
 * adds the one behaviour the SDK does not give: {@link #reconnectNow()} resumes at once after the device wakes up.
 *
 * <p>Listener callbacks arrive on the SDK's thread and are never invoked while a lock is held. Calls that can block on
 * the SDK (token fetches) run on the supplied executor, which needs at least two threads.
 */
public final class CentrifugoClient implements RpcCarrier {

    public interface TokenProvider {

        String fetchToken() throws IOException;
    }


    /** One wanted channel: the SDK subscription that serves it, and the state the listener contract needs. */
    private static final class Wanted {

        final String channel;
        volatile Subscription subscription;
        boolean positioned;
        boolean positionDropped;
        int lastSubscribeErrorCode = NoError;


        Wanted(String channel) {
            this.channel = channel;
        }
    }


    private static final Logger Log = Logger.getLogger(CentrifugoClient.class.getName());

    private static final String ClientName = "impulsem-android";
    private static final String DefaultAppVersion = "1.0";
    private static final int ReplyTimeoutMillis = 30000;
    private static final int MinReconnectMillis = 1000;
    private static final int MaxReconnectMillis = 30000;
    /** The SDK keeps the server's ping interval private, so the liveness rule assumes the Centrifugo default. */
    private static final int AssumedPingSeconds = 25;
    private static final int AbnormalCloseCode = 1006;
    /** The SDK reports a dropped transport with this code and the reason "transport closed". */
    private static final int TransportClosedCode = 1;
    /** The SDK reports a missed server ping with this code; the old client reported that as 1006 too. */
    private static final int NoPingCode = 2;
    /** The SDK reports its own disconnect() with this code. */
    private static final int DisconnectCalledCode = 0;
    private static final String UnauthorizedReason = "unauthorized";
    private static final int ResubscribeFromUnsubscribeCode = 2500;
    private static final int StateInvalidatedUnsubscribeCode = 2502;
    private static final int NoError = -1;


    private final TokenProvider tokens;
    private final CentrifugoListener listener;
    private final ScheduledExecutorService executor;
    private final Options options;
    private final Client sdk;
    /** Serializes the calls that touch the SDK's subscription registry; SDK callbacks never take it. */
    private final Object commandLock = new Object();
    private final Object lock = new Object();
    private final Map<String, Wanted> channels = new LinkedHashMap<String, Wanted>();

    private volatile String logLabel = "";
    private volatile TraceSink traceSink = TraceSink.None;

    private boolean wantConnected;
    private boolean connectedUp;
    private boolean reconnectWaiting;
    private int attempts;
    private long lastFrameAt;


    public CentrifugoClient(
        OkHttpClient http,
        String wsUrl,
        TokenProvider tokens,
        CentrifugoListener listener,
        ScheduledExecutorService executor
    ) {
        this(http, wsUrl, tokens, listener, executor, null);
    }


    /** The instance names this client to the server for rpc options (msg_id); null sends none. */
    public CentrifugoClient(
        OkHttpClient http,
        String wsUrl,
        TokenProvider tokens,
        CentrifugoListener listener,
        ScheduledExecutorService executor,
        String instance
    ) {
        this.tokens = tokens;
        this.listener = listener;
        this.executor = executor;
        this.options = new Options();
        this.options.setName(ClientName);
        this.options.setVersion(DefaultAppVersion);
        this.options.setOkHttpClient(http);
        this.options.setTimeout(ReplyTimeoutMillis);
        this.options.setMinReconnectDelay(MinReconnectMillis);
        this.options.setMaxReconnectDelay(MaxReconnectMillis);
        if (instance != null) {
            this.options.setInstance(instance);
        }
        this.options.setTokenGetter(new ConnectionTokenGetter() {
            @Override
            public void getConnectionToken(
                ConnectionTokenEvent event,
                TokenCallback callback
            ) {
                fetchToken(callback);
            }
        });
        this.sdk = new Client(wsUrl, options, new SdkEvents());
    }


    public void setAppVersion(String appVersion) {
        this.options.setVersion(appVersion == null ? "" : appVersion);
    }


    /** Prefixes this client's log lines, e.g. "account=0", so the connections of several accounts can be told apart. */
    public void setLogLabel(String logLabel) {
        this.logLabel = logLabel == null ? "" : logLabel;
    }


    /** Where the structured connection-lifecycle traces go; none by default. */
    public void setTraceSink(TraceSink traceSink) {
        this.traceSink = traceSink == null ? TraceSink.None : traceSink;
    }


    /** Test hook: the SDK's maxServerPingDelay, which bounds the silence tolerated past the server ping interval. */
    void setMaxServerPingDelayMillis(int millis) {
        this.options.setMaxServerPingDelay(millis);
    }


    /** Test hook: the SDK's reconnect delay bounds. */
    void setReconnectDelaysMillis(
        int minMillis,
        int maxMillis
    ) {
        this.options.setMinReconnectDelay(minMillis);
        this.options.setMaxReconnectDelay(maxMillis);
    }


    /** Starts connecting; the client keeps reconnecting until {@link #disconnect()} or a terminal close. */
    public void connect() {
        synchronized (commandLock) {
            synchronized (lock) {
                if (wantConnected) {
                    return;
                }
                wantConnected = true;
            }
            sdk.setToken("");
            sdk.connect();
        }
    }


    /**
     * Connects at once when the SDK waits on a reconnect backoff, or when the open socket has been silent for longer
     * than the server's ping interval plus the SDK's maxServerPingDelay (a frozen process leaves a dead socket that still
     * looks open). Does nothing when the client does not want a connection, or while a connect attempt is in flight.
     * The internal disconnect does not reach the listener.
     */
    public void reconnectNow() {
        String cause;
        synchronized (commandLock) {
            synchronized (lock) {
                if (!wantConnected) {
                    return;
                }
                if (connectedUp) {
                    long silentMillis = System.currentTimeMillis() - lastFrameAt;
                    if (silentMillis <= livenessWindowMillis()) {
                        return;
                    }
                    cause = "silent_socket";
                    connectedUp = false;
                } else if (reconnectWaiting) {
                    cause = "backoff_skipped";
                    reconnectWaiting = false;
                } else {
                    return;
                }
            }
            if (cause.equals("silent_socket")) {
                Log.log(Level.WARNING, "socket silent beyond the ping window on resume, reconnecting");
            }
            trace(LogLevel.INFO, "REALTIME_RECONNECT_NOW", "cause", cause);
            sdk.disconnect();
            sdk.setToken("");
            sdk.connect();
        }
    }


    public void disconnect() {
        boolean notify;
        synchronized (commandLock) {
            synchronized (lock) {
                notify = wantConnected;
                wantConnected = false;
                connectedUp = false;
                reconnectWaiting = false;
            }
            sdk.disconnect();
        }
        if (notify) {
            fireDisconnected(1000, "client disconnect", false);
        }
    }


    /**
     * Declares interest in a channel. The subscription is sent now when connected, and again after every reconnect.
     * A channel that is already wanted is left as it is.
     */
    public void subscribe(String channel) {
        synchronized (commandLock) {
            Wanted next;
            synchronized (lock) {
                if (channels.containsKey(channel)) {
                    return;
                }
                next = new Wanted(channel);
                channels.put(channel, next);
            }
            next.subscription = newSdkSubscription(next);
            next.subscription.subscribe();
        }
    }


    public void unsubscribe(String channel) {
        synchronized (commandLock) {
            Wanted removed;
            synchronized (lock) {
                removed = channels.remove(channel);
            }
            if (removed != null && removed.subscription != null) {
                sdk.removeSubscription(removed.subscription);
            }
        }
    }


    /** The channels this client wants to hold: confirmed, in flight, or waiting for the next connection. */
    public Set<String> subscribedChannels() {
        synchronized (lock) {
            return new LinkedHashSet<String>(channels.keySet());
        }
    }


    /**
     * Sends the rpc on the connection. The SDK refuses a deadline above its client timeout, so a longer request is cut to
     * that timeout; a timeout of zero or less means the client timeout.
     */
    @Override
    public CarrierCall call(
        final String method,
        byte[] data,
        long timeoutMillis,
        final CarrierCallback callback
    ) {
        long timeout = timeoutMillis <= 0L ? ReplyTimeoutMillis : Math.min(timeoutMillis, ReplyTimeoutMillis);
        final RpcCall sdkCall = sdk.rpcCall(
            method,
            data,
            new RpcOptions.Builder().timeout(timeout).build(),
            new ResultCallback<RPCResult>() {
                @Override
                public void onDone(
                    Throwable error,
                    RPCResult result
                ) {
                    try {
                        if (error != null) {
                            callback.onFailure(CarrierFailure.of(error));
                        } else {
                            byte[] reply = result.getData();
                            callback.onReply(reply == null ? new byte[0] : reply);
                        }
                    } catch (RuntimeException ex) {
                        // An exception escaping an SDK callback disconnects the socket, so it is logged here.
                        Log.log(Level.WARNING, "rpc callback of " + method + " threw", ex);
                    }
                }
            }
        );
        return new CarrierCall() {
            @Override
            public void cancel() {
                sdkCall.cancel();
            }
        };
    }


    /** The server's clock, as the SDK estimates it from the last reply that carried a server time. */
    public long serverTimeMillis() {
        return sdk.serverTimeMillis();
    }


    // Token supply. The SDK calls this on connect and on refresh; the blocking fetch runs on our executor.

    private void fetchToken(final TokenCallback callback) {
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    answerToken(callback);
                }
            });
        } catch (RejectedExecutionException e) {
            callback.Done(e, null);
        }
    }


    private void answerToken(TokenCallback callback) {
        boolean refresh;
        int attempt = 0;
        synchronized (lock) {
            refresh = connectedUp;
            if (!refresh) {
                attempts++;
                attempt = attempts;
                reconnectWaiting = false;
            }
        }
        if (refresh) {
            trace(LogLevel.INFO, "REALTIME_TOKEN_REFRESH");
        } else {
            trace(LogLevel.INFO, "REALTIME_CONNECTING", "attempt", attempt);
        }
        String token;
        try {
            token = tokens.fetchToken();
        } catch (SessionLostException e) {
            sessionLost(e);
            callback.Done(new UnauthorizedException(), null);
            return;
        } catch (IOException e) {
            callback.Done(e, null);
            return;
        } catch (RuntimeException e) {
            callback.Done(e, null);
            return;
        }
        if (token == null) {
            callback.Done(new IOException("token provider returned no token"), null);
            return;
        }
        callback.Done(null, token);
    }


    /** The session is gone for good: report it once here, and stay disconnected until the next connect(). */
    private void sessionLost(SessionLostException e) {
        boolean notify;
        synchronized (lock) {
            notify = wantConnected;
            wantConnected = false;
            connectedUp = false;
            reconnectWaiting = false;
        }
        if (notify) {
            fireDisconnected(0, "session lost: " + e.getMessage(), false);
        }
    }


    // Wiring to the SDK.

    private Subscription newSdkSubscription(Wanted wanted) {
        SubscriptionOptions subscriptionOptions = new SubscriptionOptions();
        subscriptionOptions.setPositioned(true);
        subscriptionOptions.setRecoverable(true);
        Subscription subscription;
        try {
            subscription = sdk.newSubscription(wanted.channel, subscriptionOptions, new SubscriptionEvents(wanted));
        } catch (DuplicateSubscriptionException e) {
            throw new IllegalStateException("the SDK still holds a subscription for a channel that was removed", e);
        }
        return subscription;
    }


    private boolean isWanted(Wanted wanted) {
        synchronized (lock) {
            return channels.get(wanted.channel) == wanted;
        }
    }


    private long livenessWindowMillis() {
        return AssumedPingSeconds * 1000L + options.getMaxServerPingDelay();
    }


    /** Client-level events of the SDK. */
    private final class SdkEvents extends EventListener {

        @Override
        public void onConnecting(
            Client client,
            ConnectingEvent event
        ) {
            boolean wasUp;
            synchronized (lock) {
                wasUp = connectedUp;
                connectedUp = false;
                if (!wasUp) {
                    // The first connect() of a session: an attempt starts at once, nothing to report.
                    return;
                }
                reconnectWaiting = true;
            }
            // The SDK keeps its last token for the next attempt; a reconnect needs a fresh one from the provider.
            sdk.setToken("");
            int closeCode = event.getCode() == NoPingCode ? AbnormalCloseCode : mapCloseCode(event.getCode());
            trace(
                LogLevel.INFO,
                "REALTIME_RECONNECT_SCHEDULED",
                "wasConnected", true,
                "closeCode", closeCode,
                "terminal", false
            );
            fireDisconnected(closeCode, event.getReason(), true);
        }


        @Override
        public void onConnected(
            Client client,
            ConnectedEvent event
        ) {
            synchronized (lock) {
                connectedUp = true;
                reconnectWaiting = false;
                lastFrameAt = System.currentTimeMillis();
            }
            trace(LogLevel.INFO, "REALTIME_CONNECT_REPLY");
            fireConnected();
        }


        @Override
        public void onDisconnected(
            Client client,
            DisconnectedEvent event
        ) {
            int code = event.getCode();
            String reason = event.getReason();
            if (code == DisconnectCalledCode) {
                // disconnect() reports itself, and the internal step of reconnectNow() reports nothing.
                synchronized (lock) {
                    connectedUp = false;
                    reconnectWaiting = false;
                }
                return;
            }
            if (code == TransportClosedCode && UnauthorizedReason.equals(reason)) {
                // The session-lost path has already reported this, see sessionLost().
                synchronized (lock) {
                    connectedUp = false;
                }
                return;
            }
            boolean wasUp;
            synchronized (lock) {
                wasUp = connectedUp;
                connectedUp = false;
                reconnectWaiting = false;
                wantConnected = false;
            }
            int closeCode = mapCloseCode(code);
            trace(
                LogLevel.WARN,
                "REALTIME_CLOSED",
                "closeCode", closeCode,
                "wasConnected", wasUp,
                "terminal", true
            );
            fireDisconnected(closeCode, reason, false);
        }


        @Override
        public void onError(
            Client client,
            ErrorEvent event
        ) {
            synchronized (lock) {
                if (!connectedUp) {
                    reconnectWaiting = true;
                }
            }
            Log.log(Level.WARNING, "realtime error: " + describe(event.getError()));
        }
    }


    /** Events of one channel's SDK subscription. A subscription that is no longer wanted reports nothing. */
    private final class SubscriptionEvents extends SubscriptionEventListener {

        private final Wanted wanted;


        SubscriptionEvents(Wanted wanted) {
            this.wanted = wanted;
        }


        @Override
        public void onSubscribing(
            Subscription sub,
            SubscribingEvent event
        ) {
            if (!isWanted(wanted)) {
                return;
            }
            int code = event.getCode();
            boolean recovering;
            synchronized (lock) {
                if (code == StateInvalidatedUnsubscribeCode) {
                    wanted.positioned = false;
                    wanted.positionDropped = true;
                }
                recovering = wanted.positioned;
            }
            if (code >= ResubscribeFromUnsubscribeCode) {
                // The server took the channel away temporarily; the SDK resubscribes by itself.
                fireUnsubscribed(wanted.channel, code, event.getReason());
            }
            trace(
                LogLevel.INFO,
                "REALTIME_SUBSCRIBE_SENT",
                "lane", laneKind(wanted.channel),
                "wasRecovering", recovering
            );
        }


        @Override
        public void onSubscribed(
            Subscription sub,
            SubscribedEvent event
        ) {
            boolean recovered = Boolean.TRUE.equals(event.getRecovered());
            boolean wasRecovering;
            synchronized (lock) {
                if (channels.get(wanted.channel) != wanted) {
                    return;
                }
                wanted.positioned = Boolean.TRUE.equals(event.getRecoverable());
                // After a 2502 the position was dropped, so the listener must not hear a recovery.
                wasRecovering = Boolean.TRUE.equals(event.wasRecovering()) && !wanted.positionDropped;
                wanted.positionDropped = false;
                wanted.lastSubscribeErrorCode = NoError;
                lastFrameAt = System.currentTimeMillis();
            }
            trace(
                LogLevel.INFO,
                "REALTIME_SUBSCRIBE_REPLY",
                "lane", laneKind(wanted.channel),
                "recovered", recovered,
                "wasRecovering", wasRecovering
            );
            fireSubscribed(wanted.channel, recovered, wasRecovering);
        }


        @Override
        public void onPublication(
            Subscription sub,
            PublicationEvent event
        ) {
            if (!isWanted(wanted)) {
                return;
            }
            synchronized (lock) {
                lastFrameAt = System.currentTimeMillis();
            }
            Envelope envelope = Envelope.parseBytes(event.getData());
            firePublication(new Publication(wanted.channel, envelope, event.getOffset()));
        }


        /**
         * Only the wanted subscription of its channel acts here. A subscription that was already unsubscribed, or that
         * was replaced by a newer one for the same channel, finds the map changed under the lock and does nothing, so it
         * can never remove the newer subscription.
         */
        @Override
        public void onUnsubscribed(
            Subscription sub,
            UnsubscribedEvent event
        ) {
            int failedCode;
            synchronized (lock) {
                if (channels.get(wanted.channel) != wanted) {
                    return;
                }
                channels.remove(wanted.channel);
                sdk.removeSubscription(sub);
                failedCode = wanted.lastSubscribeErrorCode;
            }
            if (failedCode != NoError && failedCode == event.getCode()) {
                trace(
                    LogLevel.WARN,
                    "REALTIME_SUBSCRIBE_FAILED",
                    "lane", laneKind(wanted.channel),
                    "errorCode", failedCode
                );
            }
            fireUnsubscribed(wanted.channel, event.getCode(), event.getReason());
        }


        @Override
        public void onError(
            Subscription sub,
            SubscriptionErrorEvent event
        ) {
            Throwable error = event.getError();
            if (!(error instanceof SubscriptionSubscribeError)) {
                return;
            }
            Throwable cause = ((SubscriptionSubscribeError) error).getError();
            if (!(cause instanceof ReplyError)) {
                return;
            }
            synchronized (lock) {
                if (channels.get(wanted.channel) == wanted) {
                    wanted.lastSubscribeErrorCode = ((ReplyError) cause).getCode();
                }
            }
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
     * One connection-lifecycle event. The sink gets only the typed fields, never the server's reason or message; the
     * local log line carries the event name, the label and the same typed fields. A throwing sink is contained here.
     */
    private void trace(
        LogLevel level,
        String event,
        Object... keysAndValues
    ) {
        try {
            traceSink.trace(level, event, keysAndValues);
        } catch (RuntimeException e) {
            Log.log(Level.WARNING, "trace sink failed for " + event, e);
        }
        if (Log.isLoggable(Level.INFO)) {
            StringBuilder line = new StringBuilder(event).append(' ').append(logLabel);
            for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
                line.append(' ').append(keysAndValues[i]).append('=').append(keysAndValues[i + 1]);
            }
            Log.info(line.toString());
        }
    }


    private static int mapCloseCode(int sdkCode) {
        return sdkCode == TransportClosedCode ? AbnormalCloseCode : sdkCode;
    }


    private static String laneKind(String channel) {
        int separator = channel.indexOf(':');
        return separator < 0 ? channel : channel.substring(0, separator);
    }


    /** The class of a failure, never its message: messages can carry tokens or server text. */
    private static String describe(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }
}
