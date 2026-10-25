package net.impulsem.transport.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.Random;
import net.impulsem.transport.errors.RpcError;
import net.impulsem.transport.errors.RpcErrors;
import net.impulsem.transport.grpcweb.GrpcWebClient;
import net.impulsem.transport.grpcweb.GrpcWebResponse;
import net.impulsem.transport.policy.RequestPolicy;
import net.impulsem.transport.rpc.ConnectionDeclarer;


/**
 * Holds the session, the pending 2FA token and the QR exporter ticket, and refreshes the session
 * with a single in-flight request shared by every waiting caller.
 */
public final class TokenManager {

    private static final long PendingLifetimeMillis = 5 * 60 * 1000L;
    private static final long NoRefreshPointMillis = Long.MAX_VALUE;
    private static final int NoFlags = 0;
    private static final int RateLimitedCode = 420;

    private static final class Pending {

        final String token;
        final long expiresAt;


        Pending(
            String token,
            long expiresAt
        ) {
            this.token = token;
            this.expiresAt = expiresAt;
        }
    }


    /** A stored token pair with the refresh point drawn when it was stored. The point is never persisted. */
    private static final class Held {

        final SessionTokens tokens;
        final long refreshPointMillis;


        Held(
            SessionTokens tokens,
            long refreshPointMillis
        ) {
            this.tokens = tokens;
            this.refreshPointMillis = refreshPointMillis;
        }
    }


    private final SessionStore store;
    private final GrpcWebClient client;
    private final Clock clock;
    private final RefreshSchedule schedule;

    private final Object refreshLock = new Object();
    private boolean refreshing;
    private long refreshGeneration;
    private boolean lastRefreshResult;
    private IOException lastRefreshFailure;
    private boolean deferredPending;
    private long deferredGeneration;

    private long clearGeneration;

    private volatile Held held;
    private volatile Pending pending;
    private volatile String qrTicket;
    private volatile Runnable sessionListener;
    private volatile Executor refreshExecutor;
    private volatile ConnectionDeclarer.Delayer refreshDelayer;


    public TokenManager(
        SessionStore store,
        GrpcWebClient client,
        Clock clock
    ) {
        this(store, client, clock, new Random());
    }


    public TokenManager(
        SessionStore store,
        GrpcWebClient client,
        Clock clock,
        Random random
    ) {
        this.store = store;
        this.client = client;
        this.clock = clock;
        this.schedule = new RefreshSchedule(random);
        this.held = hold(store.load());
    }


    /** The pending token if set and unexpired, else the access token, else null. */
    public String bearer() {
        String active = activePendingToken();
        if (active != null) {
            return active;
        }
        Held current = held;
        return current == null ? null : current.tokens.accessToken;
    }


    public void onLoginTokens(
        String access,
        String refresh,
        long userId
    ) {
        SessionTokens value = new SessionTokens(access, refresh, userId);
        synchronized (this) {
            held = hold(value);
            pending = null;
            cancelDeferredRefresh();
            store.save(value);
        }
        notifySessionChanged();
    }


    /**
     * Persists a refreshed pair. Unlike onLoginTokens it leaves an active pending 2FA token alone.
     * Returns false and stores nothing when clear() ran since the refresh began.
     */
    private boolean onRefreshedTokens(
        SessionTokens value,
        long expectedClearGeneration
    ) {
        synchronized (this) {
            if (clearGeneration != expectedClearGeneration) {
                return false;
            }
            held = hold(value);
            store.save(value);
        }
        notifySessionChanged();
        return true;
    }


    /** Pairs a stored token with the refresh point drawn now; an unreadable access token gets no point. */
    private Held hold(SessionTokens value) {
        if (value == null) {
            return null;
        }
        long exp = value.accessToken == null ? -1 : expirySeconds(value.accessToken);
        long point = exp < 0
            ? NoRefreshPointMillis
            : schedule.drawRefreshPointMillis(exp, clock.nowMillis());
        return new Held(value, point);
    }


    /** The pending token if set and unexpired; an expired one is dropped only if nobody replaced it meanwhile. */
    private String activePendingToken() {
        Pending seen = pending;
        if (seen == null) {
            return null;
        }
        if (clock.nowMillis() < seen.expiresAt) {
            return seen.token;
        }
        synchronized (this) {
            if (pending == seen) {
                pending = null;
            }
        }
        return null;
    }


    public void setPendingToken(String jwt) {
        pending = new Pending(jwt, clock.nowMillis() + PendingLifetimeMillis);
    }


    public String qrExporterTicket() {
        return qrTicket;
    }


    public void setQrExporterTicket(String ticket) {
        qrTicket = ticket;
    }


    /**
     * Enables retrying a refresh refused with FLOOD_WAIT_X: the retry runs on the executor after X seconds, and only
     * while it would land before the access token reaches exp - 120 s. Unset, a refused refresh fails at once.
     */
    public void setDeferredRefreshScheduling(
        Executor executor,
        ConnectionDeclarer.Delayer delayer
    ) {
        refreshExecutor = executor;
        refreshDelayer = delayer;
    }


    /** Called after login or refresh stored a new token pair, never on clear. */
    public void setSessionListener(Runnable listener) {
        sessionListener = listener;
    }


    private void notifySessionChanged() {
        Runnable listener = sessionListener;
        if (listener != null) {
            listener.run();
        }
    }


    public boolean hasSession() {
        return held != null;
    }


    public void clear() {
        synchronized (this) {
            clearGeneration++;
            store.clear();
            held = null;
            pending = null;
            qrTicket = null;
            cancelDeferredRefresh();
        }
    }


    /**
     * True once the refresh point drawn for the stored access token has passed. Opaque or missing tokens never need it.
     */
    public boolean needsProactiveRefresh() {
        Held current = held;
        if (current == null || current.tokens.accessToken == null || current.tokens.refreshToken == null) {
            return false;
        }
        if (activePendingToken() != null) {
            return false;
        }
        return clock.nowMillis() >= current.refreshPointMillis;
    }


    /**
     * Single-flight: while one refresh is on the wire every other caller waits for its outcome
     * instead of sending a second request, which would burn the already rotated refresh token.
     *
     * @return true when a fresh session is in place; false when the session is unrecoverable
     *     (the store has been cleared).
     * @throws IOException on transport failures and unexpected server errors; the session is kept.
     */
    public boolean refreshBlocking() throws IOException {
        long joinedGeneration;
        synchronized (refreshLock) {
            if (deferredPending) {
                return true;
            }
            if (refreshing) {
                joinedGeneration = refreshGeneration;
                while (refreshing && refreshGeneration == joinedGeneration) {
                    try {
                        refreshLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("interrupted while waiting for session refresh");
                    }
                }
                if (lastRefreshFailure != null) {
                    throw new IOException(lastRefreshFailure.getMessage(), lastRefreshFailure);
                }
                return lastRefreshResult;
            }
            refreshing = true;
        }

        boolean result = false;
        IOException failure = null;
        try {
            result = doRefresh();
        } catch (IOException e) {
            failure = e;
        } catch (RuntimeException e) {
            failure = new IOException("session refresh failed: " + e.getMessage(), e);
        } finally {
            synchronized (refreshLock) {
                lastRefreshResult = result;
                lastRefreshFailure = failure;
                refreshGeneration++;
                refreshing = false;
                refreshLock.notifyAll();
            }
        }
        if (failure != null) {
            throw failure;
        }
        return result;
    }


    private boolean doRefresh() throws IOException {
        long startedClearGeneration;
        SessionTokens current;
        synchronized (this) {
            startedClearGeneration = clearGeneration;
            current = held == null ? null : held.tokens;
        }
        if (current == null || current.refreshToken == null) {
            return false;
        }
        GrpcWebResponse response = client.execute(
            client.newCall(
                ImpulseRpcPaths.RefreshSession,
                ImpulseRpcPaths.encodeRefreshSession(current.refreshToken),
                Collections.<String, String>emptyMap()
            )
        );
        RpcError error = RpcErrors.fromResponse(response);
        if (error != null) {
            if (isUnrecoverable(error)) {
                clear();
                return false;
            }
            if (error.code == RateLimitedCode && deferRefresh(error, current.accessToken, startedClearGeneration)) {
                return true;
            }
            throw new IOException("RefreshSession failed: " + error);
        }
        ImpulseRpcPaths.Authorization authorization = ImpulseRpcPaths.decodeAuthorization(response.body);
        if (authorization.sessionToken == null
            || authorization.sessionToken.isEmpty()
            || authorization.refreshToken == null
            || authorization.refreshToken.isEmpty()
        ) {
            throw new IOException("RefreshSession response has no tokens");
        }
        if (authorization.userId == 0) {
            throw new IOException("RefreshSession response has no user id");
        }
        // False means the session was cleared while the request was on the wire; the new tokens are dropped.
        return onRefreshedTokens(
            new SessionTokens(authorization.sessionToken, authorization.refreshToken, authorization.userId),
            startedClearGeneration
        );
    }


    /**
     * Schedules a retry of a refused refresh after the advertised wait, if it lands before exp - 120 s. The current
     * access token stays valid until then, so callers keep using it and send no request while the retry is pending.
     */
    private boolean deferRefresh(
        RpcError error,
        String accessToken,
        long startedClearGeneration
    ) {
        Executor executor = refreshExecutor;
        ConnectionDeclarer.Delayer delayer = refreshDelayer;
        if (executor == null || delayer == null) {
            return false;
        }
        RequestPolicy.Decision decision = RequestPolicy.onError(
            NoFlags,
            RequestPolicy.CONNECTION_TYPE_GENERIC,
            error,
            1
        );
        if (decision.action != RequestPolicy.Action.RETRY_AFTER) {
            return false;
        }
        long expSeconds = accessToken == null ? -1 : expirySeconds(accessToken);
        if (expSeconds < 0) {
            return false;
        }
        long landingMillis = clock.nowMillis() + decision.delayMillis;
        if (landingMillis >= (expSeconds - RefreshSchedule.WindowEndBeforeExpirySeconds) * 1000L) {
            return false;
        }
        final long generation;
        synchronized (this) {
            if (clearGeneration != startedClearGeneration) {
                return false;
            }
            synchronized (refreshLock) {
                deferredGeneration++;
                deferredPending = true;
                generation = deferredGeneration;
            }
        }
        delayer.schedule(
            new Runnable() {
                @Override
                public void run() {
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            runDeferredRefresh(generation);
                        }
                    });
                }
            },
            decision.delayMillis
        );
        return true;
    }


    private void runDeferredRefresh(long generation) {
        synchronized (refreshLock) {
            if (deferredGeneration != generation) {
                return;
            }
            deferredPending = false;
        }
        try {
            refreshBlocking();
        } catch (IOException e) {
            // A background task must not throw: on Android that kills the app. The next request past the refresh
            // point refreshes again in the foreground and reports its own failure.
        }
    }


    private void cancelDeferredRefresh() {
        synchronized (refreshLock) {
            deferredPending = false;
            deferredGeneration++;
        }
    }


    private static boolean isUnrecoverable(RpcError error) {
        return "AUTH_TOKEN_INVALID".equals(error.text)
            || "AUTH_TOKEN_EXPIRED".equals(error.text)
            || "SESSION_EXPIRED".equals(error.text);
    }


    /** The exp claim in unix seconds, or -1 when the token is not a readable JWT. */
    private static long expirySeconds(String jwt) {
        try {
            int first = jwt.indexOf('.');
            int second = first < 0 ? -1 : jwt.indexOf('.', first + 1);
            if (first < 0 || second < 0) {
                return -1;
            }
            byte[] payload = decodeBase64Url(jwt.substring(first + 1, second));
            JsonElement root = JsonParser.parseString(new String(payload, "UTF-8"));
            if (!root.isJsonObject()) {
                return -1;
            }
            JsonObject object = root.getAsJsonObject();
            JsonElement exp = object.get("exp");
            if (exp == null || !exp.isJsonPrimitive()) {
                return -1;
            }
            long value = exp.getAsLong();
            return value > 0 ? value : -1;
        } catch (Exception e) {
            return -1;
        }
    }


    /** Hand-rolled so the module does not need java.util.Base64 (absent below Android API 26). */
    private static byte[] decodeBase64Url(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int value;
            if (c >= 'A' && c <= 'Z') {
                value = c - 'A';
            } else if (c >= 'a' && c <= 'z') {
                value = c - 'a' + 26;
            } else if (c >= '0' && c <= '9') {
                value = c - '0' + 52;
            } else if (c == '-' || c == '+') {
                value = 62;
            } else if (c == '_' || c == '/') {
                value = 63;
            } else if (c == '=') {
                break;
            } else {
                throw new IllegalArgumentException("bad base64url character");
            }
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}
