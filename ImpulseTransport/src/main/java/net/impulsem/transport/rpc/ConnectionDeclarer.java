package net.impulsem.transport.rpc;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import net.impulsem.transport.auth.ConnectionDeclaration;
import net.impulsem.transport.auth.Credentials;
import net.impulsem.transport.auth.ImpulseRpcPaths;
import net.impulsem.transport.auth.TokenManager;
import net.impulsem.transport.errors.RpcError;
import net.impulsem.transport.policy.RequestPolicy;


/**
 * Tells the server what upstream Telegram's initConnection tells it: the app, the device and the languages.
 *
 * <p>The declaration is keyed by the credential the update socket presents: the access token with a session,
 * the connection identity without one. It goes out when that credential changes and when the declaration
 * changes (a language switch), never without a credential and never twice for the same pair. Every attempt
 * runs on the injected executor, so a slow or failing declaration never delays another request. A failure
 * is retried with {@link RequestPolicy} for at most {@link #MaxAttempts} attempts, then dropped until the
 * next trigger; nothing is thrown to the caller.
 */
public final class ConnectionDeclarer implements RpcClient.DeclarationGate {

    public static final String OutcomeOk = "ok";
    public static final String OutcomeRetry = "retry";
    public static final String OutcomeGaveUp = "gave_up";
    public static final String OutcomeSessionLost = "session_lost";

    public static final int MaxAttempts = 3;

    private static final int NoFlags = 0;


    /** Runs a task after a delay. */
    public interface Delayer {

        void schedule(
            Runnable task,
            long delayMillis
        );
    }


    /** Receives the outcome of each attempt: the outcome name, the attempt number and the error code or 0. No values. */
    public interface Listener {

        void onResult(
            String outcome,
            int attempt,
            int code
        );
    }


    private final RpcClient rpc;
    private final TokenManager tokens;
    private final Credentials credentials;
    private final Executor executor;
    private final Delayer delayer;
    private final Listener listener;

    private final Object lock = new Object();
    private ConnectionDeclaration declaration;
    private String earlyLangCode;
    private String earlySystemLangCode;
    private ConnectionDeclaration sentDeclaration;
    private String sentCredential;
    private boolean running;
    private boolean rerun;
    private long generation;
    /**
     * Runs that ended under the lock: a finished attempt or a scheduled retry. awaitDeclared stops waiting when this
     * grows without the pair matching and no run pending, so a run that gave up or left a retry is not waited for.
     */
    private long finishedRuns;


    public ConnectionDeclarer(
        RpcClient rpc,
        TokenManager tokens,
        Credentials credentials,
        Executor executor,
        Delayer delayer,
        Listener listener
    ) {
        this.rpc = rpc;
        this.tokens = tokens;
        this.credentials = credentials;
        this.executor = executor;
        this.delayer = delayer;
        this.listener = listener;
        tokens.addSessionListener(new Runnable() {
            @Override
            public void run() {
                sessionChanged();
            }
        });
    }


    /** Sets the whole declaration, at start-up. Sends it when it is new and a session exists. */
    public void setDeclaration(ConnectionDeclaration value) {
        synchronized (lock) {
            ConnectionDeclaration result = value;
            // A language set before the declaration arrived is newer than the one inside it.
            if (earlyLangCode != null) {
                result = result.withLangCode(earlyLangCode);
                earlyLangCode = null;
            }
            if (earlySystemLangCode != null) {
                result = result.withSystemLangCode(earlySystemLangCode);
                earlySystemLangCode = null;
            }
            declaration = result;
        }
        trigger();
    }


    public void setLangCode(String langCode) {
        synchronized (lock) {
            if (declaration == null) {
                earlyLangCode = langCode;
                return;
            }
            declaration = declaration.withLangCode(langCode);
        }
        trigger();
    }


    public void setSystemLangCode(String langCode) {
        synchronized (lock) {
            if (declaration == null) {
                earlySystemLangCode = langCode;
                return;
            }
            declaration = declaration.withSystemLangCode(langCode);
        }
        trigger();
    }


    /** The session was established or its tokens were replaced. */
    public void sessionChanged() {
        trigger();
    }


    /** The socket now presents another credential: a new identity, or a sign-out or sign-in. */
    public void credentialChanged() {
        trigger();
    }


    /** The server lost the declaration (CONNECTION_NOT_INITED): forget the sent pair and declare again. */
    @Override
    public void invalidate() {
        synchronized (lock) {
            sentCredential = null;
            sentDeclaration = null;
            lock.notifyAll();
        }
        trigger();
    }


    /**
     * Waits until the sent pair equals the current credential and declaration, at most timeoutMillis. A wait that
     * finds the pair unsent starts a run, then waits on the lock monitor; it never runs an attempt on this thread.
     *
     * <p>The wait ends with false when a run finishes without the pair matching and no run is pending (a run that
     * gave up, or one that scheduled a retry), or when the timeout passes. The run count is compared under the lock
     * so a run that ends between two checks is never missed. Every change of the pair and every run end notifies.
     */
    @Override
    public boolean awaitDeclared(long timeoutMillis) throws IOException {
        String credential = credentials.current();
        boolean start;
        synchronized (lock) {
            if (isDeclaredLocked(credential)) {
                return true;
            }
            // A run in flight is waited for, not doubled: credentialChanged() already re-triggers for a new credential.
            start = !running;
        }
        if (start) {
            trigger();
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (lock) {
            long runsAtStart = finishedRuns;
            while (true) {
                credential = credentials.current();
                if (isDeclaredLocked(credential)) {
                    return true;
                }
                if (finishedRuns != runsAtStart && !running && !rerun) {
                    return false;
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lock, remainingNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted while waiting for the connection declaration");
                }
            }
        }
    }


    /** True when the credential and declaration are both known and equal the sent pair. Call under the lock. */
    private boolean isDeclaredLocked(String credential) {
        return credential != null
            && declaration != null
            && credential.equals(sentCredential)
            && declaration.equals(sentDeclaration);
    }


    private void trigger() {
        long started;
        synchronized (lock) {
            generation++;
            if (running) {
                rerun = true;
                return;
            }
            running = true;
            started = generation;
        }
        executor.execute(attemptTask(1, started, true));
    }


    private Runnable attemptTask(
        final int attempt,
        final long forGeneration,
        final boolean holdsRunning
    ) {
        return new Runnable() {
            @Override
            public void run() {
                if (!holdsRunning) {
                    synchronized (lock) {
                        if (generation != forGeneration || running) {
                            return;
                        }
                        running = true;
                    }
                }
                try {
                    attempt(attempt, forGeneration);
                } catch (RuntimeException e) {
                    report(OutcomeGaveUp, attempt, 0);
                    finish();
                }
            }
        };
    }


    private void attempt(
        int attempt,
        long forGeneration
    ) {
        String credential = credentials.current();
        ConnectionDeclaration current;
        boolean skip;
        synchronized (lock) {
            current = declaration;
            skip = credential == null
                || current == null
                || (credential.equals(sentCredential) && current.equals(sentDeclaration));
        }
        if (skip) {
            finish();
            return;
        }
        try {
            rpc.callNamed(ImpulseRpcPaths.InitConnectionMethod, ImpulseRpcPaths.encodeInitConnection(current));
        } catch (SessionLostException e) {
            report(OutcomeSessionLost, attempt, e.error == null ? 0 : e.error.code);
            finish();
            return;
        } catch (RpcStatusException e) {
            failed(attempt, forGeneration, e.error);
            return;
        } catch (IOException e) {
            failed(attempt, forGeneration, null);
            return;
        }
        synchronized (lock) {
            sentCredential = credential;
            sentDeclaration = current;
            lock.notifyAll();
        }
        report(OutcomeOk, attempt, 0);
        finish();
    }


    private void failed(
        int attempt,
        final long forGeneration,
        RpcError error
    ) {
        RequestPolicy.Decision decision = error == null
            ? RequestPolicy.onNetworkFailure(NoFlags, RequestPolicy.CONNECTION_TYPE_GENERIC, attempt)
            : RequestPolicy.onError(NoFlags, RequestPolicy.CONNECTION_TYPE_GENERIC, error, attempt);
        int code = error == null ? 0 : error.code;
        if (decision.action != RequestPolicy.Action.RETRY_AFTER || attempt >= MaxAttempts) {
            report(OutcomeGaveUp, attempt, code);
            finish();
            return;
        }
        final Runnable retry = attemptTask(attempt + 1, forGeneration, false);
        synchronized (lock) {
            running = false;
            finishedRuns++;
            lock.notifyAll();
        }
        delayer.schedule(
            new Runnable() {
                @Override
                public void run() {
                    executor.execute(retry);
                }
            },
            decision.delayMillis
        );
        // Reported once the retry is scheduled, so an observer of "retry" can rely on it.
        report(OutcomeRetry, attempt, code);
        restartIfTriggered();
    }


    private void finish() {
        synchronized (lock) {
            running = false;
            finishedRuns++;
            lock.notifyAll();
        }
        restartIfTriggered();
    }


    /** A trigger that arrived while an attempt was in flight starts a fresh run now. */
    private void restartIfTriggered() {
        long started;
        synchronized (lock) {
            if (!rerun || running) {
                return;
            }
            rerun = false;
            running = true;
            started = generation;
        }
        executor.execute(attemptTask(1, started, true));
    }


    private void report(
        String outcome,
        int attempt,
        int code
    ) {
        try {
            listener.onResult(outcome, attempt, code);
        } catch (RuntimeException ignored) {
            // A failing log hook must not affect the transport.
        }
    }
}
