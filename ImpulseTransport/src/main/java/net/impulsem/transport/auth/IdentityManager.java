package net.impulsem.transport.auth;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import net.impulsem.transport.errors.RpcError;
import net.impulsem.transport.rpc.BlockingCall;
import net.impulsem.transport.rpc.CarrierErrors;
import net.impulsem.transport.rpc.ConnectionDeclarer;
import net.impulsem.transport.rpc.RpcCarrier;
import net.impulsem.transport.rpc.RpcStatusException;


/** The connection identity of one account slot while it is signed out. Thread-safe. */
public final class IdentityManager {

    /** Persists the identity token across restarts. */
    public interface Store {

        /** The persisted identity token, or null. */
        String load();

        void save(String identityToken);

        void clear();
    }


    public interface Listener {

        /** A different identity replaced the current one: the socket must reconnect with it and declare again. */
        void onIdentityChanged();

        /** The same identity got a renewed token: hand it to the live socket. */
        void onIdentityRenewed(String identityToken);
    }


    private static final long ExpiryMarginSeconds = 60L;
    private static final long MintTimeoutMillis = 30_000L;
    private static final long RenewTimeoutMillis = 30_000L;
    private static final long FirstRetryMillis = 1_000L;
    private static final long MaxRetryMillis = 60_000L;


    /** An identity token with the claims read from it; the sub is always present. */
    private static final class Identity {

        final String token;
        final JwtClaims claims;


        Identity(
            String token,
            JwtClaims claims
        ) {
            this.token = token;
            this.claims = claims;
        }
    }


    /** One mint on the wire. Callers that arrive while it runs wait on it and share its result. */
    private static final class Flight {

        final long epoch;
        final CountDownLatch done = new CountDownLatch(1);
        String token;
        IOException failure;


        Flight(long epoch) {
            this.epoch = epoch;
        }
    }


    private final Store store;
    private final RpcCarrier poolFleet;
    private final Clock clock;
    private final RefreshSchedule schedule;
    private final ConnectionDeclarer.Delayer delayer;
    private final Executor executor;

    private final Object lock = new Object();
    private Identity identity;
    private Flight mint;
    private long epoch;

    private volatile int apiId;
    private volatile RpcCarrier renewalCarrier;
    private volatile Listener listener;


    /** Loads the stored identity: a token still valid for more than 60 s becomes current, anything else is cleared. */
    public IdentityManager(
        Store store,
        RpcCarrier poolFleet,
        Clock clock,
        Random random,
        ConnectionDeclarer.Delayer delayer,
        Executor executor
    ) {
        this.store = store;
        this.poolFleet = poolFleet;
        this.clock = clock;
        this.schedule = new RefreshSchedule(random);
        this.delayer = delayer;
        this.executor = executor;
        this.identity = loadStored();
        if (identity != null) {
            scheduleRenewal(identity, epoch);
        }
    }


    /** Sent in every connectionIdentity request. */
    public void setApiId(int apiId) {
        this.apiId = apiId;
    }


    /** The update socket that renews the identity; renewal is skipped while it is null. */
    public void setRenewalCarrier(RpcCarrier socket) {
        renewalCarrier = socket;
    }


    public void setListener(Listener listener) {
        this.listener = listener;
    }


    /** The current token if it is valid for more than 60 s, else null. Never sends anything. */
    public String current() {
        synchronized (lock) {
            return identity != null && isUsable(identity, clock.nowMillis()) ? identity.token : null;
        }
    }


    /**
     * The current token, or a new one minted through the pool fleet. Blocking; concurrent callers share one mint.
     *
     * @throws RpcStatusException when the pool answers an error (e.g. API_ID_INVALID)
     * @throws IOException on a carrier fault, an unreadable reply, or a discard while minting
     */
    public String obtainBlocking() throws IOException {
        Flight flight;
        boolean leader;
        synchronized (lock) {
            if (identity != null && isUsable(identity, clock.nowMillis())) {
                return identity.token;
            }
            if (mint != null) {
                flight = mint;
                leader = false;
            } else {
                flight = new Flight(epoch);
                mint = flight;
                leader = true;
            }
        }
        if (leader) {
            return runMint(flight);
        }
        return awaitMint(flight);
    }


    /** Forgets the identity: memory and store, cancels the renewal. A mint in flight then fails. */
    public void discard() {
        synchronized (lock) {
            discardLocked();
        }
    }


    /** The server refused this token. If it is the current one: discard it and tell the listener. */
    public void refuse(String refusedToken) {
        synchronized (lock) {
            if (identity == null || !identity.token.equals(refusedToken)) {
                return;
            }
            discardLocked();
        }
        Listener target = listener;
        if (target != null) {
            target.onIdentityChanged();
        }
    }


    private Identity loadStored() {
        String stored = store.load();
        if (stored == null) {
            return null;
        }
        Identity loaded = readIdentity(stored);
        if (loaded != null && isUsable(loaded, clock.nowMillis())) {
            return loaded;
        }
        store.clear();
        return null;
    }


    private String runMint(Flight flight) throws IOException {
        String token = null;
        IOException failure = null;
        try {
            Identity minted = requestIdentity(poolFleet, "", MintTimeoutMillis);
            long installEpoch;
            synchronized (lock) {
                if (flight.epoch != epoch) {
                    throw new IOException("identity discarded while minting");
                }
                identity = minted;
                store.save(minted.token);
                installEpoch = epoch;
            }
            token = minted.token;
            scheduleRenewal(minted, installEpoch);
        } catch (IOException e) {
            failure = e;
        } catch (RuntimeException e) {
            failure = new IOException("connectionIdentity failed: " + e.getMessage(), e);
        } finally {
            synchronized (lock) {
                if (mint == flight) {
                    mint = null;
                }
            }
            flight.token = token;
            flight.failure = failure;
            flight.done.countDown();
        }
        if (failure != null) {
            throw failure;
        }
        return token;
    }


    private String awaitMint(Flight flight) throws IOException {
        try {
            flight.done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting for the identity");
        }
        if (flight.failure != null) {
            throw new IOException(flight.failure.getMessage(), flight.failure);
        }
        return flight.token;
    }


    /** One connectionIdentity call on the carrier; the token is empty for a mint and the current one for a renewal. */
    private Identity requestIdentity(
        RpcCarrier carrier,
        String identityToken,
        long timeoutMillis
    ) throws IOException {
        BlockingCall.Outcome outcome = BlockingCall.send(
            carrier,
            ImpulseRpcPaths.ConnectionIdentityMethod,
            ImpulseRpcPaths.encodeConnectionIdentity(identityToken, apiId),
            timeoutMillis,
            null
        );
        if (outcome.failure != null) {
            RpcError error = CarrierErrors.toRpcError(outcome.failure);
            if (error != null) {
                throw new RpcStatusException("connectionIdentity failed: " + error, error);
            }
            throw new IOException("connectionIdentity failed: " + outcome.failure.cause, outcome.failure.cause);
        }
        String token = ImpulseRpcPaths.decodeConnectionIdentity(outcome.reply);
        Identity reply = token == null ? null : readIdentity(token);
        if (reply == null || reply.claims.exp * 1000L <= clock.nowMillis()) {
            throw new IOException("connectionIdentity returned an invalid identity token");
        }
        return reply;
    }


    /** Schedules the renewal at a point drawn inside the refresh window of the identity. */
    private void scheduleRenewal(
        final Identity renewing,
        final long epochAtInstall
    ) {
        long now = clock.nowMillis();
        long point = schedule.drawRefreshPointMillis(renewing.claims.exp, now);
        delayer.schedule(
            new Runnable() {
                @Override
                public void run() {
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            renew(renewing, epochAtInstall, FirstRetryMillis);
                        }
                    });
                }
            },
            Math.max(0L, point - now)
        );
    }


    /**
     * Renews the identity on the socket. A renewal to the same identity hands the new token to the socket; a different
     * identity replaces the current one and asks the socket to reconnect. A failure retries with a doubling delay
     * while the retry lands before exp - 120 s.
     */
    private void renew(
        Identity renewing,
        long startedEpoch,
        long retryMillis
    ) {
        RpcCarrier carrier = renewalCarrier;
        if (carrier == null || !isCurrent(renewing, startedEpoch)) {
            return;
        }
        Identity next;
        try {
            next = requestIdentity(carrier, renewing.token, RenewTimeoutMillis);
        } catch (IOException e) {
            retryRenewal(renewing, startedEpoch, retryMillis);
            return;
        }
        boolean same = isSameIdentity(renewing, next);
        long installEpoch;
        synchronized (lock) {
            if (epoch != startedEpoch || identity != renewing) {
                return;
            }
            if (!same) {
                epoch++;
            }
            identity = next;
            store.save(next.token);
            installEpoch = epoch;
        }
        scheduleRenewal(next, installEpoch);
        Listener target = listener;
        if (target == null) {
            return;
        }
        if (same) {
            target.onIdentityRenewed(next.token);
        } else {
            target.onIdentityChanged();
        }
    }


    private void retryRenewal(
        Identity renewing,
        long startedEpoch,
        long retryMillis
    ) {
        long landing = clock.nowMillis() + retryMillis;
        if (landing >= (renewing.claims.exp - RefreshSchedule.WindowEndBeforeExpirySeconds) * 1000L) {
            return;
        }
        final long nextRetryMillis = Math.min(retryMillis * 2L, MaxRetryMillis);
        delayer.schedule(
            new Runnable() {
                @Override
                public void run() {
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            renew(renewing, startedEpoch, nextRetryMillis);
                        }
                    });
                }
            },
            retryMillis
        );
    }


    /** Web K's rule: a renewal keeps sub and orig_iat and moves iat. */
    private static boolean isSameIdentity(
        Identity before,
        Identity after
    ) {
        return after.claims.sub.equals(before.claims.sub)
            && after.claims.origIat == before.claims.origIat
            && after.claims.origIat != after.claims.iat;
    }


    private boolean isCurrent(
        Identity candidate,
        long expectedEpoch
    ) {
        synchronized (lock) {
            return epoch == expectedEpoch && identity == candidate;
        }
    }


    private void discardLocked() {
        epoch++;
        identity = null;
        mint = null;
        store.clear();
    }


    private static boolean isUsable(
        Identity candidate,
        long nowMillis
    ) {
        return candidate.claims.exp * 1000L - nowMillis > ExpiryMarginSeconds * 1000L;
    }


    /** The token with its claims, or null when the token is unreadable or carries no sub. */
    private static Identity readIdentity(String token) {
        JwtClaims claims = JwtClaims.parse(token);
        if (claims == null || claims.sub == null) {
            return null;
        }
        return new Identity(token, claims);
    }
}
