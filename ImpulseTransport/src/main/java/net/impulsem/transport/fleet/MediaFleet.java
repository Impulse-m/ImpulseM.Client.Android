package net.impulsem.transport.fleet;

import io.github.centrifugal.centrifuge.ConnectionTokenEvent;
import io.github.centrifugal.centrifuge.ConnectionTokenGetter;
import io.github.centrifugal.centrifuge.ReplyError;
import io.github.centrifugal.centrifuge.TokenCallback;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.impulsem.transport.auth.TokenManager;
import net.impulsem.transport.rpc.CarrierCall;
import net.impulsem.transport.rpc.CarrierCallback;
import net.impulsem.transport.rpc.CarrierFailure;
import net.impulsem.transport.rpc.RpcCarrier;
import okhttp3.OkHttpClient;


/**
 * The media fleet of this_dc: one RpcPoolCarrier on the media endpoint from the dc options. The pool is rebuilt when the
 * endpoint or the session changes, so no connection keeps another session's token. Calls made before the config is
 * published wait for it, each up to its own timeout.
 */
public final class MediaFleet implements RpcCarrier {

    private static final int ConnectionsPerEndpoint = 2;
    private static final int InflightPerConnection = 2;
    private static final int MaxQueued = 64;
    private static final long CallTimeoutMillis = 10_000L;


    /** One call. It waits in the queue until the config is published, then goes through the pool; it settles once. */
    private final class Outgoing implements CarrierCall {

        final String method;
        private final byte[] data;
        private final long deadlineMillis;
        private final CarrierCallback callback;
        private final AtomicBoolean settled = new AtomicBoolean();
        private volatile ScheduledFuture<?> timer;
        private volatile CarrierCall inFlight;
        private volatile boolean cancelRequested;


        Outgoing(
            String method,
            byte[] data,
            long deadlineMillis,
            CarrierCallback callback
        ) {
            this.method = method;
            this.data = data;
            this.deadlineMillis = deadlineMillis;
            this.callback = callback;
        }


        /** Starts the queued call's deadline. Runs under the fleet lock, before the call can be expired or dispatched. */
        void armTimer() {
            timer = scheduler.schedule(
                new Runnable() {
                    @Override
                    public void run() {
                        expire();
                    }
                },
                deadlineMillis,
                TimeUnit.MILLISECONDS
            );
        }


        /** Sends the call through the pool; the pool's own timeout takes over from the queue's deadline. */
        void send(RpcPoolCarrier target) {
            stopTimer();
            CarrierCall started = target.call(
                method,
                data,
                deadlineMillis,
                new CarrierCallback() {
                    @Override
                    public void onReply(byte[] reply) {
                        settleReply(reply);
                    }


                    @Override
                    public void onFailure(CarrierFailure failure) {
                        settleFailure(failure);
                    }
                }
            );
            inFlight = started;
            if (cancelRequested) {
                started.cancel();
            }
        }


        void settleReply(byte[] reply) {
            if (settle()) {
                callback.onReply(reply);
            }
        }


        void settleFailure(CarrierFailure failure) {
            if (settle()) {
                callback.onFailure(failure);
            }
        }


        @Override
        public void cancel() {
            boolean wasQueued;
            synchronized (lock) {
                wasQueued = queued.remove(this);
            }
            if (wasQueued) {
                settleFailure(CarrierFailure.of(new ReplyError(ReplyError.CANCELLED, "cancelled", false)));
                return;
            }
            // Set before reading inFlight: send() sets inFlight before it reads this flag, so one side always sees the other.
            cancelRequested = true;
            CarrierCall current = inFlight;
            if (current != null) {
                current.cancel();
            }
        }


        private void expire() {
            boolean stillQueued;
            synchronized (lock) {
                stillQueued = queued.remove(this);
            }
            if (stillQueued) {
                settleFailure(CarrierFailure.of(new IOException("no config yet for " + method)));
            }
        }


        private boolean settle() {
            if (!settled.compareAndSet(false, true)) {
                return false;
            }
            stopTimer();
            return true;
        }


        private void stopTimer() {
            ScheduledFuture<?> pending = timer;
            if (pending != null) {
                pending.cancel(false);
            }
        }
    }


    private final OkHttpClient http;
    private final TokenManager tokens;
    private final ConnectionTokenGetter tokenGetter;
    private final ScheduledExecutorService scheduler;
    private final Object lock = new Object();
    private final List<Outgoing> queued = new ArrayList<Outgoing>();
    private boolean published;
    private boolean closed;
    private String endpoint;
    private RpcPoolCarrier pool;


    /** The executor runs the token lookups, which block on the session; the SDK thread must not. */
    public MediaFleet(
        OkHttpClient http,
        TokenManager tokens,
        Executor executor
    ) {
        this.http = http;
        this.tokens = tokens;
        this.tokenGetter = new ConnectionTokenGetter() {
            @Override
            public void getConnectionToken(
                ConnectionTokenEvent event,
                final TokenCallback callback
            ) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        answerToken(callback);
                    }
                });
            }
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "media-fleet-timers");
                thread.setDaemon(true);
                return thread;
            }
        });
    }


    /**
     * The media endpoint from the current dc options, or null when the config has no media_only option for this_dc.
     * The first call publishes the config, and the calls queued before it are sent now.
     */
    public void setEndpoint(String endpointOrNull) {
        RpcPoolCarrier retired = null;
        List<Outgoing> toSend = new ArrayList<Outgoing>();
        RpcPoolCarrier target = null;
        synchronized (lock) {
            if (closed) {
                return;
            }
            boolean first = !published;
            published = true;
            if (!first && sameEndpoint(endpoint, endpointOrNull)) {
                return;
            }
            retired = pool;
            pool = null;
            endpoint = endpointOrNull;
            if (first) {
                toSend.addAll(queued);
                queued.clear();
            }
            target = toSend.isEmpty() ? null : poolLocked();
        }
        if (retired != null) {
            retired.close();
        }
        for (Outgoing outgoing : toSend) {
            if (target == null) {
                outgoing.settleFailure(CarrierFailure.of(new IOException("no media_only dc option for " + outgoing.method)));
            } else {
                outgoing.send(target);
            }
        }
    }


    /** The session changed (sign-in or sign-out): the pool is closed, and the next call builds one with the new token. */
    public void sessionChanged() {
        RpcPoolCarrier retired;
        synchronized (lock) {
            if (closed) {
                return;
            }
            retired = pool;
            pool = null;
        }
        if (retired != null) {
            retired.close();
        }
    }


    /**
     * Sends the rpc to the media endpoint. Before the config is published the call waits in a queue; with no media
     * option for this_dc it fails at once.
     */
    @Override
    public CarrierCall call(
        String method,
        byte[] data,
        long timeoutMillis,
        CarrierCallback callback
    ) {
        Outgoing outgoing = new Outgoing(method, data, deadlineMillis(timeoutMillis), callback);
        RpcPoolCarrier target = null;
        IOException refusal = null;
        synchronized (lock) {
            if (closed) {
                refusal = new IOException("media fleet is closed");
            } else if (!published) {
                queued.add(outgoing);
                outgoing.armTimer();
            } else {
                target = poolLocked();
                if (target == null) {
                    refusal = new IOException("no media_only dc option for " + method);
                }
            }
        }
        if (refusal != null) {
            outgoing.settleFailure(CarrierFailure.of(refusal));
        } else if (target != null) {
            outgoing.send(target);
        }
        return outgoing;
    }


    /** Closes the pool; calls in flight and queued calls fail. The fleet accepts no call afterwards. */
    public void close() {
        RpcPoolCarrier retired;
        List<Outgoing> drained;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            retired = pool;
            pool = null;
            drained = new ArrayList<Outgoing>(queued);
            queued.clear();
        }
        scheduler.shutdownNow();
        if (retired != null) {
            retired.close();
        }
        for (Outgoing outgoing : drained) {
            outgoing.settleFailure(CarrierFailure.of(new IOException("media fleet is closed")));
        }
    }


    /** The pool for the published endpoint, built on first use; null when there is no endpoint. Caller holds the lock. */
    private RpcPoolCarrier poolLocked() {
        if (pool == null && endpoint != null) {
            pool = new RpcPoolCarrier(
                http,
                endpoint,
                ConnectionsPerEndpoint,
                InflightPerConnection,
                MaxQueued,
                CallTimeoutMillis,
                tokenGetter,
                null
            );
        }
        return pool;
    }


    /** Hands the SDK the session's access token, or the reason there is none. Runs on the executor. */
    private void answerToken(TokenCallback callback) {
        String token;
        try {
            token = tokens.connectionTokenBlocking();
        } catch (IOException e) {
            callback.Done(e, null);
            return;
        } catch (RuntimeException e) {
            callback.Done(new IOException("media fleet token failed: " + e.getMessage(), e), null);
            return;
        }
        if (token == null) {
            callback.Done(new IOException("no session for the media fleet"), null);
        } else {
            callback.Done(null, token);
        }
    }


    private static long deadlineMillis(long timeoutMillis) {
        return timeoutMillis <= 0L ? CallTimeoutMillis : Math.min(timeoutMillis, CallTimeoutMillis);
    }


    private static boolean sameEndpoint(
        String current,
        String next
    ) {
        return current == null ? next == null : current.equals(next);
    }
}
