package net.impulsem.transport.auth;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.impulsem.transport.errors.RpcError;
import net.impulsem.transport.policy.RequestPolicy;
import net.impulsem.transport.rpc.BlockingCall;
import net.impulsem.transport.rpc.CarrierErrors;
import net.impulsem.transport.rpc.ConnectionDeclarer;
import net.impulsem.transport.rpc.RpcCarrier;


/**
 * Holds the session and refreshes it with a single in-flight request shared by every waiting caller. The refresh
 * travels over the update socket while the access token works, and over the token-less pool fleet once the token has
 * expired or the server has refused it.
 */
public final class TokenManager {

    private static final long NoRefreshPointMillis = Long.MAX_VALUE;
    private static final int NoFlags = 0;
    private static final int RateLimitedCode = 420;
    private static final long RefreshTimeoutMillis = 30_000L;

    private static final Logger Log = Logger.getLogger(TokenManager.class.getName());


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
    private final Clock clock;
    private final RefreshSchedule schedule;
    private final CopyOnWriteArrayList<Runnable> sessionListeners = new CopyOnWriteArrayList<Runnable>();

    private final Object refreshLock = new Object();
    private boolean refreshing;
    private long refreshGeneration;
    private boolean lastRefreshResult;
    private IOException lastRefreshFailure;
    private boolean deferredPending;
    private long deferredGeneration;

    private long clearGeneration;

    private volatile Held held;
    private volatile RpcCarrier refreshSocket;
    private volatile RpcCarrier refreshPool;
    private volatile String refusedAccessToken;
    private volatile Executor refreshExecutor;
    private volatile ConnectionDeclarer.Delayer refreshDelayer;


    public TokenManager(
        SessionStore store,
        Clock clock
    ) {
        this(store, clock, new Random());
    }


    public TokenManager(
        SessionStore store,
        Clock clock,
        Random random
    ) {
        this.store = store;
        this.clock = clock;
        this.schedule = new RefreshSchedule(random);
        this.held = hold(store.load());
    }


    /**
     * Sets the carriers a refresh goes over: the update socket while its access token works, the pool fleet once the
     * token has expired or was refused. Until this is called a refresh fails. A setter, because the socket is built
     * after the token manager.
     */
    public void setRefreshCarriers(
        RpcCarrier updateSocket,
        RpcCarrier poolFleet
    ) {
        refreshSocket = updateSocket;
        refreshPool = poolFleet;
    }


    /** The stored access token, or null without a session. */
    public String bearer() {
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
            refusedAccessToken = null;
            cancelDeferredRefresh();
            store.save(value);
        }
        notifySessionChanged();
    }


    /**
     * Persists a refreshed pair. Returns false and stores nothing when clear() ran since the refresh began.
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
            refusedAccessToken = null;
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


    /**
     * Listeners run after login or refresh stored a new token pair, never on clear. Each runs in its own try block, so
     * one that throws does not keep the others from running.
     */
    public void addSessionListener(Runnable listener) {
        sessionListeners.add(listener);
    }


    private void notifySessionChanged() {
        for (Runnable listener : sessionListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                Log.log(Level.WARNING, "session listener failed", e);
            }
        }
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


    /** Remembers that the server refused this access token, e.g. with 103 or 109 on the socket. */
    public void markRefused(String accessToken) {
        refusedAccessToken = accessToken;
    }


    /**
     * True when the session's access token is refused or its exp has passed. An unreadable token is not unusable:
     * opaque tokens are never refreshed ahead of time.
     */
    public boolean accessUnusable() {
        Held current = held;
        if (current == null || current.tokens.accessToken == null) {
            return false;
        }
        String access = current.tokens.accessToken;
        if (access.equals(refusedAccessToken)) {
            return true;
        }
        long exp = expirySeconds(access);
        return exp >= 0 && clock.nowMillis() >= exp * 1000L;
    }


    public boolean hasSession() {
        return held != null;
    }


    public void clear() {
        synchronized (this) {
            clearGeneration++;
            store.clear();
            held = null;
            refusedAccessToken = null;
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
        return clock.nowMillis() >= current.refreshPointMillis;
    }


    /**
     * The token the update socket connects with. Null without a session, or when the session ended during the refresh.
     * An expired or refused access token is refreshed first, through the pool, so the socket does not connect with a
     * token the server would refuse.
     *
     * @throws IOException when that refresh fails with a transport error; the session is kept.
     */
    public String connectionTokenBlocking() throws IOException {
        if (!hasSession()) {
            return null;
        }
        // Only an unusable token is refreshed here, and that goes through the pool: the SDK asks for this token while
        // the socket connects, so a refresh on the socket would wait for itself. A valid token past its J6 point is
        // refreshed on the live socket afterwards.
        if (accessUnusable()) {
            if (!refreshBlocking()) {
                return null;
            }
        }
        return bearer();
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
        RpcCarrier socket = refreshSocket;
        RpcCarrier pool = refreshPool;
        if (socket == null || pool == null) {
            throw new IOException("no refresh carrier");
        }
        byte[] request = ImpulseRpcPaths.encodeRefreshSession(current.refreshToken);
        RpcCarrier carrier = accessUnusable() ? pool : socket;
        BlockingCall.Outcome outcome = BlockingCall.send(
            carrier,
            ImpulseRpcPaths.RefreshSessionMethod,
            request,
            RefreshTimeoutMillis,
            null
        );
        if (outcome.failure != null && carrier == socket && CarrierErrors.isTokenRefusal(outcome.failure)) {
            // The socket's own token was refused; the token-less pool fleet takes the refresh instead.
            outcome = BlockingCall.send(
                pool,
                ImpulseRpcPaths.RefreshSessionMethod,
                request,
                RefreshTimeoutMillis,
                null
            );
        }
        if (outcome.failure != null) {
            RpcError error = CarrierErrors.toRpcError(outcome.failure);
            if (error == null) {
                throw new IOException("RefreshSession failed: carrier fault", outcome.failure.cause);
            }
            if (isUnrecoverable(error)) {
                clear();
                return false;
            }
            if (error.code == RateLimitedCode && deferRefresh(error, current.accessToken, startedClearGeneration)) {
                return true;
            }
            throw new IOException("RefreshSession failed: " + error);
        }
        ImpulseRpcPaths.Authorization authorization = ImpulseRpcPaths.decodeRefreshSession(outcome.reply);
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
        JwtClaims claims = JwtClaims.parse(jwt);
        return claims == null ? -1 : claims.exp;
    }
}
