package org.telegram.tgnet.impulse;

import android.os.SystemClock;

import net.impulsem.transport.auth.Clock;
import net.impulsem.transport.auth.TokenManager;
import net.impulsem.transport.codec.Transcoder;
import net.impulsem.transport.errors.RpcError;
import net.impulsem.transport.grpcweb.GrpcWebClient;
import net.impulsem.transport.policy.RequestPolicy;
import net.impulsem.transport.rpc.RpcClient;
import net.impulsem.transport.rpc.RpcOutcome;
import net.impulsem.transport.schema.TlProtoSchema;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;


/**
 * Runs every ConnectionsManager request of one account over the ImpulseM transport: gating on login,
 * invokeAfter chaining, retry policy, cancellation, config and server time, and the connection state.
 * It replaces the C++ connection layer, which is never started.
 */
public final class ImpulseConnection {

    private static final int ConnectionStateConnecting = 1;
    private static final int ConnectionStateWaitingForNetwork = 2;
    private static final int ConnectionStateConnected = 3;

    private static final long ConfigRefreshMillis = 3600L * 1000L;
    private static final long ConfigRetryMillis = 30L * 1000L;
    private static final long ListenAfterCancelMillis = 25L * 1000L;
    private static final long TickMillis = 1000L;
    private static final long PauseTickGraceMillis = 10L * 1000L;
    private static final int MaxWorkerThreads = 48;

    private static final ImpulseConnection[] Instances = new ImpulseConnection[UserConfig.MAX_ACCOUNT_COUNT];
    private static OkHttpClient httpClient;


    private enum State {
        WAITING_LOGIN,
        CHAIN_QUEUED,
        ACTIVE
    }


    private static final class Pending {

        final RequestEntry entry;

        State state = State.ACTIVE;
        boolean inChain;
        boolean listening;
        boolean cancelled;
        boolean waitingRetry;
        int serverFailures;
        int networkFailures;
        Call call;
        ScheduledFuture<?> timer;


        Pending(RequestEntry entry) {
            this.entry = entry;
        }
    }


    private final int account;
    private final ConnectionsManager owner;
    private final Object lock = new Object();

    private final AndroidSessionStore store;
    private final GrpcWebClient grpc;
    private final TokenManager tokens;
    private RpcClient rpc;
    private Transcoder transcoder;

    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService scheduler;

    private final Map<Integer, Pending> pendings = new HashMap<>();
    private final List<Pending> loginWaiting = new ArrayList<>();
    private final ArrayDeque<Pending> chain = new ArrayDeque<>();
    private final Map<Integer, Set<Integer>> tokensByGuid = new HashMap<>();
    private final Map<Integer, Integer> guidByToken = new HashMap<>();
    private final AtomicBoolean logoutPosted = new AtomicBoolean();
    private final ImpulseRealtime realtime;

    private boolean chainBusy;
    private boolean configInFlight;
    private boolean configAgain;
    private boolean started;
    private long userId;
    private int connectionState = ConnectionStateConnecting;
    private int thisDatacenter = 1;

    private volatile long timeOffsetMillis;
    private volatile int lastPingMillis;
    private volatile boolean tickSuspended;
    private ScheduledFuture<?> suspendTask;


    public static synchronized ImpulseConnection forAccount(
        int account,
        ConnectionsManager owner
    ) {
        ImpulseConnection existing = Instances[account];
        if (existing != null && existing.owner == owner) {
            return existing;
        }
        ImpulseConnection created = new ImpulseConnection(account, owner);
        Instances[account] = created;
        return created;
    }


    private static synchronized OkHttpClient sharedHttpClient() {
        if (httpClient == null) {
            Dispatcher dispatcher = new Dispatcher();
            dispatcher.setMaxRequests(64);
            dispatcher.setMaxRequestsPerHost(32);
            httpClient = new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        }
        return httpClient;
    }


    private ImpulseConnection(
        int account,
        ConnectionsManager owner
    ) {
        this.account = account;
        this.owner = owner;
        this.store = new AndroidSessionStore(account);
        this.grpc = new GrpcWebClient(sharedHttpClient(), ImpulseEndpoints.rpcBaseUrl());
        this.tokens = new TokenManager(store, grpc, Clock.SYSTEM);
        this.executor = new ThreadPoolExecutor(
            MaxWorkerThreads,
            MaxWorkerThreads,
            30L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(),
            namedThreads("impulse-rpc-" + account)
        );
        this.executor.allowCoreThreadTimeOut(true);
        ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(1, namedThreads("impulse-timer-" + account));
        timers.setRemoveOnCancelPolicy(true);
        this.scheduler = timers;
        this.realtime = ImpulseRealtime.create(account, this);
    }


    /** Starts the tick and the config refresh. Never touches the native connection layer. */
    public void start(long userId) {
        boolean stale;
        boolean alreadyStarted;
        synchronized (lock) {
            this.userId = userId;
            alreadyStarted = started;
            started = true;
            stale = !alreadyStarted && userId != 0 && !tokens.hasSession();
        }
        if (alreadyStarted) {
            // A second start only refreshes the user; the tick and config tasks already run.
            releaseLoginWaiters();
            return;
        }
        log("start user=" + userId + " session=" + tokens.hasSession());
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    rpc();
                } catch (RuntimeException e) {
                    FileLog.e(e);
                }
            }
        });
        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                if (!tickSuspended) {
                    ConnectionsManager.onUpdate(account);
                }
            }
        }, TickMillis, TickMillis, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                fetchConfig();
            }
        }, 0L, ConfigRefreshMillis, TimeUnit.MILLISECONDS);
        realtime.start();
        releaseLoginWaiters();
        if (stale) {
            // The app thinks it is logged in but there is no ImpulseM session to authenticate with.
            log("stored user without a session, logging out");
            postLogout();
        }
    }


    public void send(RequestEntry entry) {
        Pending pending = new Pending(entry);
        synchronized (lock) {
            pendings.put(entry.token, pending);
            if (RequestPolicy.mustWaitForLogin(entry.flags, isLoggedInLocked())) {
                pending.state = State.WAITING_LOGIN;
                loginWaiting.add(pending);
                return;
            }
        }
        dispatch(pending);
    }


    public void cancel(
        int token,
        boolean notifyServer
    ) {
        Pending pending;
        boolean hardCancel;
        synchronized (lock) {
            pending = pendings.get(token);
            if (pending == null || pending.listening) {
                return;
            }
            if (pending.state != State.ACTIVE) {
                loginWaiting.remove(pending);
                chain.remove(pending);
                forgetLocked(pending);
                hardCancel = true;
            } else if ((pending.entry.flags & RequestPolicy.FLAG_LISTEN_AFTER_CANCEL) != 0 && pending.call != null) {
                pending.listening = true;
                hardCancel = false;
            } else {
                pending.cancelled = true;
                forgetLocked(pending);
                hardCancel = true;
            }
        }
        if (hardCancel) {
            abort(pending);
            ConnectionsManager.onRequestClear(account, token, true);
            releaseChain(pending);
            return;
        }
        final Pending listening = pending;
        scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                expireListening(listening);
            }
        }, ListenAfterCancelMillis, TimeUnit.MILLISECONDS);
    }


    public void cancelForGuid(int guid) {
        List<Integer> toCancel;
        synchronized (lock) {
            Set<Integer> set = tokensByGuid.get(guid);
            if (set == null) {
                return;
            }
            toCancel = new ArrayList<>(set);
        }
        for (int a = 0; a < toCancel.size(); a++) {
            cancel(toCancel.get(a), false);
        }
    }


    public void bindToGuid(
        int token,
        int guid
    ) {
        synchronized (lock) {
            if (!pendings.containsKey(token)) {
                return;
            }
            Set<Integer> set = tokensByGuid.get(guid);
            if (set == null) {
                set = new HashSet<>();
                tokensByGuid.put(guid, set);
            }
            set.add(token);
            guidByToken.put(token, guid);
        }
    }


    /** Queued requests that have not started fail with -2000 CANCELLED_REQUEST. */
    public void failNotRunning(int token) {
        Pending pending;
        synchronized (lock) {
            pending = pendings.get(token);
            if (pending == null || pending.state == State.ACTIVE) {
                return;
            }
            loginWaiting.remove(pending);
            chain.remove(pending);
        }
        finishError(pending, -2000, "CANCELLED_REQUEST");
    }


    /** Fails every request that needs a login with -1000 and forgets the user; with reset also the session. */
    public void cleanup(boolean resetKeys) {
        List<Pending> victims = new ArrayList<>();
        boolean hadUser;
        synchronized (lock) {
            hadUser = userId != 0;
            userId = 0;
            Iterator<Pending> iterator = pendings.values().iterator();
            while (iterator.hasNext()) {
                Pending pending = iterator.next();
                if ((pending.entry.flags & RequestPolicy.FLAG_WITHOUT_LOGIN) != 0) {
                    continue;
                }
                iterator.remove();
                loginWaiting.remove(pending);
                chain.remove(pending);
                forgetGuidLocked(pending.entry.token);
                pending.cancelled = true;
                victims.add(pending);
            }
        }
        for (int a = 0; a < victims.size(); a++) {
            Pending victim = victims.get(a);
            abort(victim);
            deliverError(victim, -1000, "");
            releaseChain(victim);
        }
        if (resetKeys || hadUser) {
            tokens.clear();
        }
        realtime.syncSoon();
        log("cleanup reset=" + resetKeys + " hadUser=" + hadUser + " failed=" + victims.size());
    }


    public void setUserId(long id) {
        boolean changed;
        synchronized (lock) {
            changed = userId != id;
            userId = id;
        }
        if (id != 0) {
            logoutPosted.set(false);
        }
        log("setUserId " + id);
        realtime.syncSoon();
        releaseLoginWaiters();
        if (changed && id != 0) {
            fetchConfig();
        }
    }


    /** Fetches help.getConfig again, which also refreshes the server time offset. */
    public void updateDcSettings() {
        fetchConfig();
    }


    public long currentTimeMillis() {
        return System.currentTimeMillis() + timeOffsetMillis;
    }


    public int timeDifference() {
        return (int) (timeOffsetMillis / 1000L);
    }


    public int datacenterId() {
        synchronized (lock) {
            return thisDatacenter;
        }
    }


    /** The last measured round trip of a generic request, in milliseconds. */
    public int lastPingMillis() {
        return lastPingMillis;
    }


    public void setAppPaused(boolean paused) {
        synchronized (lock) {
            if (suspendTask != null) {
                suspendTask.cancel(false);
                suspendTask = null;
            }
            if (paused) {
                suspendTask = scheduler.schedule(new Runnable() {
                    @Override
                    public void run() {
                        tickSuspended = true;
                    }
                }, PauseTickGraceMillis, TimeUnit.MILLISECONDS);
            } else {
                tickSuspended = false;
            }
        }
    }


    public void networkChanged() {
        realtime.syncSoon();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                boolean online = ApplicationLoader.isNetworkOnline();
                if (!online) {
                    setState(ConnectionStateWaitingForNetwork);
                    return;
                }
                boolean wasOffline;
                synchronized (lock) {
                    wasOffline = connectionState == ConnectionStateWaitingForNetwork;
                }
                if (wasOffline) {
                    setState(ConnectionStateConnecting);
                    retryNow();
                    fetchConfig();
                }
            }
        });
    }


    public void resume() {
        setAppPaused(false);
        networkChanged();
    }


    private static ThreadFactory namedThreads(final String prefix) {
        final AtomicInteger counter = new AtomicInteger(1);
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
    }


    static OkHttpClient httpClient() {
        return sharedHttpClient();
    }


    RpcClient rpcClient() {
        return rpc();
    }


    Transcoder transcoder() {
        rpc();
        return transcoder;
    }


    long currentUserId() {
        synchronized (lock) {
            return userId;
        }
    }


    boolean hasSession() {
        return tokens.hasSession();
    }


    /** The realtime layer could not get a token because the session is gone: log out like any forced logout. */
    void onRealtimeSessionLost() {
        boolean loggedIn;
        synchronized (lock) {
            loggedIn = RequestPolicy.shouldForceLogout(userId);
        }
        if (loggedIn) {
            tokens.clear();
            postLogout();
        }
    }


    private synchronized RpcClient rpc() {
        if (rpc == null) {
            transcoder = new Transcoder(TlProtoSchema.load());
            rpc = new RpcClient(transcoder, grpc, tokens);
        }
        return rpc;
    }


    /** Sends the requests that wait for a login if a user and a session exist now. Safe to call any time. */
    private void releaseLoginWaiters() {
        List<Pending> released = null;
        synchronized (lock) {
            if (!loginWaiting.isEmpty() && RequestPolicy.canReleaseLoginWaiters(userId, tokens.hasSession())) {
                released = new ArrayList<>(loginWaiting);
                loginWaiting.clear();
            }
        }
        if (released != null) {
            for (int a = 0; a < released.size(); a++) {
                dispatch(released.get(a));
            }
        }
    }


    private boolean isLoggedInLocked() {
        return userId != 0 && tokens.hasSession();
    }


    private void dispatch(final Pending pending) {
        if ((pending.entry.flags & RequestPolicy.FLAG_INVOKE_AFTER) != 0) {
            synchronized (lock) {
                pending.state = State.CHAIN_QUEUED;
                chain.add(pending);
            }
            pumpChain();
            return;
        }
        synchronized (lock) {
            pending.state = State.ACTIVE;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                runRequest(pending);
            }
        });
    }


    private void pumpChain() {
        final Pending next;
        synchronized (lock) {
            if (chainBusy || chain.isEmpty()) {
                return;
            }
            next = chain.poll();
            chainBusy = true;
            next.inChain = true;
            next.state = State.ACTIVE;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                runRequest(next);
            }
        });
    }


    private void releaseChain(Pending pending) {
        synchronized (lock) {
            if (!pending.inChain) {
                return;
            }
            pending.inChain = false;
            chainBusy = false;
        }
        pumpChain();
    }


    private void runRequest(Pending pending) {
        RequestEntry entry = pending.entry;
        synchronized (lock) {
            pending.waitingRetry = false;
            pending.timer = null;
            if (pending.cancelled) {
                return;
            }
        }
        RpcClient client;
        try {
            client = rpc();
        } catch (RuntimeException e) {
            FileLog.e(e);
            finishError(pending, 500, "TRANSPORT_UNAVAILABLE");
            return;
        }
        byte[] data = entry.data;
        int methodId = Transcoder.leadingId(data);
        if (!transcoder.hasMethod(methodId)) {
            log("method 0x" + Integer.toHexString(methodId) + " is not in the mapping");
            finishError(pending, 400, "METHOD_INVALID");
            return;
        }
        Call call;
        try {
            call = client.prepare(data);
        } catch (RuntimeException e) {
            FileLog.e("impulse: cannot transcode method 0x" + Integer.toHexString(methodId) + ": " + e);
            finishError(pending, 400, "TRANSCODE_FAILED");
            return;
        }
        synchronized (lock) {
            if (pending.cancelled) {
                return;
            }
            pending.call = call;
        }
        ConnectionsManager.onRequestWriteToSocket(account, entry.token);
        long startTime = SystemClock.elapsedRealtime();
        RpcOutcome outcome;
        try {
            outcome = client.execute(call);
        } catch (IOException e) {
            onNetworkFailure(pending, e);
            return;
        } catch (RuntimeException e) {
            FileLog.e(e);
            finishError(pending, 500, "CLIENT_ERROR");
            return;
        }
        synchronized (lock) {
            pending.call = null;
            if (pending.cancelled) {
                return;
            }
        }
        if ((entry.connectionType & ConnectionsManager.ConnectionTypeGeneric) != 0) {
            lastPingMillis = (int) Math.min(SystemClock.elapsedRealtime() - startTime, Integer.MAX_VALUE);
        }
        setState(ConnectionStateConnected);
        releaseLoginWaiters();
        ConnectionsManager.onBytesSent(entry.data.length, ApplicationLoader.getCurrentNetworkType(), account);
        if (outcome.error == null) {
            ConnectionsManager.onBytesReceived(outcome.tlResult.length, ApplicationLoader.getCurrentNetworkType(), account);
            finishSuccess(pending, outcome.tlResult);
        } else {
            onServerError(pending, outcome);
        }
    }


    private void onServerError(
        Pending pending,
        RpcOutcome outcome
    ) {
        RpcError error = outcome.error;
        RequestEntry entry = pending.entry;
        RequestPolicy.Decision decision = RequestPolicy.onError(entry.flags, entry.connectionType, error, pending.serverFailures);
        log("token " + entry.token + " error " + error.code + " " + error.text + " -> " + decision.action);
        if (outcome.forceLogout || decision.action == RequestPolicy.Action.LOGOUT_AND_DELIVER) {
            boolean loggedIn;
            synchronized (lock) {
                loggedIn = RequestPolicy.shouldForceLogout(userId);
            }
            if (loggedIn) {
                tokens.clear();
                finishError(pending, error.code, error.text);
                postLogout();
            } else {
                // Not logged in (login flow): deliver only, an active pending 2FA token must stay.
                finishError(pending, error.code, error.text);
            }
            return;
        }
        if (decision.action == RequestPolicy.Action.RETRY_AFTER) {
            if (decision.premiumFloodWait) {
                ConnectionsManager.onPremiumFloodWait(
                    account,
                    entry.token,
                    (entry.connectionType & ConnectionsManager.ConnectionTypeUpload) != 0
                );
            }
            pending.serverFailures++;
            scheduleRun(pending, decision.delayMillis, false);
            return;
        }
        finishError(pending, error.code, error.text);
    }


    private void onNetworkFailure(
        Pending pending,
        IOException failure
    ) {
        synchronized (lock) {
            pending.call = null;
            if (pending.cancelled) {
                return;
            }
        }
        RequestEntry entry = pending.entry;
        pending.networkFailures++;
        log("token " + entry.token + " network failure " + pending.networkFailures + ": " + failure);
        setState(ApplicationLoader.isNetworkOnline() ? ConnectionStateConnecting : ConnectionStateWaitingForNetwork);
        RequestPolicy.Decision decision = RequestPolicy.onNetworkFailure(entry.flags, entry.connectionType, pending.networkFailures);
        if (decision.action == RequestPolicy.Action.RETRY_AFTER) {
            scheduleRun(pending, decision.delayMillis, true);
        } else {
            finishError(pending, decision.errorCode, decision.errorText);
        }
    }


    private void scheduleRun(
        final Pending pending,
        long delayMillis,
        boolean afterNetworkFailure
    ) {
        if (delayMillis <= 0L) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    runRequest(pending);
                }
            });
            return;
        }
        synchronized (lock) {
            if (pending.cancelled) {
                return;
            }
            pending.waitingRetry = afterNetworkFailure;
            pending.timer = scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            runRequest(pending);
                        }
                    });
                }
            }, delayMillis, TimeUnit.MILLISECONDS);
        }
    }


    /** The network is back: requests that wait out a network failure go again at once. */
    private void retryNow() {
        List<Pending> due = new ArrayList<>();
        synchronized (lock) {
            for (Pending pending : pendings.values()) {
                if (pending.waitingRetry && pending.timer != null && pending.timer.cancel(false)) {
                    pending.timer = null;
                    pending.waitingRetry = false;
                    due.add(pending);
                }
            }
        }
        for (int a = 0; a < due.size(); a++) {
            final Pending pending = due.get(a);
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    runRequest(pending);
                }
            });
        }
    }


    private void expireListening(Pending pending) {
        synchronized (lock) {
            if (pendings.get(pending.entry.token) != pending) {
                return;
            }
            pending.cancelled = true;
            forgetLocked(pending);
        }
        abort(pending);
        ConnectionsManager.onRequestClear(account, pending.entry.token, true);
        releaseChain(pending);
    }


    private void abort(Pending pending) {
        Call call;
        ScheduledFuture<?> timer;
        synchronized (lock) {
            call = pending.call;
            timer = pending.timer;
            pending.timer = null;
        }
        if (timer != null) {
            timer.cancel(false);
        }
        if (call != null) {
            call.cancel();
        }
    }


    private void forgetLocked(Pending pending) {
        pendings.remove(pending.entry.token);
        forgetGuidLocked(pending.entry.token);
    }


    private void forgetGuidLocked(int token) {
        Integer guid = guidByToken.remove(token);
        if (guid != null) {
            Set<Integer> set = tokensByGuid.get(guid);
            if (set != null) {
                set.remove(token);
                if (set.isEmpty()) {
                    tokensByGuid.remove(guid);
                }
            }
        }
    }


    /** Removes the request; false when something else (cancel, cleanup) already finished it. */
    private boolean complete(Pending pending) {
        synchronized (lock) {
            if (pendings.get(pending.entry.token) != pending) {
                return false;
            }
            forgetLocked(pending);
            return true;
        }
    }


    private void finishSuccess(
        Pending pending,
        byte[] result
    ) {
        if (!complete(pending)) {
            return;
        }
        try {
            NativeByteBuffer buffer = new NativeByteBuffer(result.length);
            buffer.writeBytes(result);
            buffer.position(0);
            owner.deliverResponse(
                pending.entry.token,
                buffer,
                0,
                null,
                ApplicationLoader.getCurrentNetworkType(),
                currentTimeMillis()
            );
        } catch (Exception e) {
            FileLog.e(e);
            deliverError(pending, 500, "CLIENT_ERROR");
        } finally {
            releaseChain(pending);
        }
    }


    private void finishError(
        Pending pending,
        int code,
        String text
    ) {
        if (!complete(pending)) {
            return;
        }
        try {
            deliverError(pending, code, text);
        } finally {
            releaseChain(pending);
        }
    }


    private void deliverError(
        Pending pending,
        int code,
        String text
    ) {
        owner.deliverResponse(
            pending.entry.token,
            null,
            code,
            text,
            ApplicationLoader.getCurrentNetworkType(),
            currentTimeMillis()
        );
    }


    private void postLogout() {
        if (!logoutPosted.compareAndSet(false, true)) {
            return;
        }
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                ConnectionsManager.onLogout(account);
            }
        });
    }


    private void setState(int state) {
        synchronized (lock) {
            if (connectionState == state) {
                return;
            }
            connectionState = state;
        }
        log("state " + state);
        ConnectionsManager.onConnectionStateChanged(state, account);
    }


    /** help.getConfig with WithoutLogin semantics; sets the server time and hands the config to the app. */
    private void fetchConfig() {
        synchronized (lock) {
            if (!started) {
                return;
            }
            if (configInFlight) {
                configAgain = true;
                return;
            }
            configInFlight = true;
        }
        owner.sendRequest(
            new TLRPC.TL_help_getConfig(),
            (response, error) -> {
                boolean again;
                synchronized (lock) {
                    configInFlight = false;
                    again = configAgain;
                    configAgain = false;
                }
                if (response instanceof TLRPC.TL_config) {
                    TLRPC.TL_config config = (TLRPC.TL_config) response;
                    timeOffsetMillis = config.date * 1000L - System.currentTimeMillis();
                    synchronized (lock) {
                        thisDatacenter = config.this_dc;
                    }
                    log("config date=" + config.date + " this_dc=" + config.this_dc + " offset=" + timeOffsetMillis);
                    MessagesController.getInstance(account).updateConfig(config);
                } else {
                    log("config failed " + (error == null ? "" : error.code + " " + error.text));
                    scheduler.schedule(new Runnable() {
                        @Override
                        public void run() {
                            fetchConfig();
                        }
                    }, ConfigRetryMillis, TimeUnit.MILLISECONDS);
                }
                if (again) {
                    fetchConfig();
                }
            },
            ConnectionsManager.RequestFlagEnableUnauthorized | ConnectionsManager.RequestFlagWithoutLogin
        );
    }


    private static void log(String message) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("impulse: " + message);
        }
    }
}
