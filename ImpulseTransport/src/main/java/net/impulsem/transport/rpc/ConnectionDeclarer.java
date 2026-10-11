package net.impulsem.transport.rpc;

import java.io.IOException;
import java.util.concurrent.Executor;
import net.impulsem.transport.auth.ConnectionDeclaration;
import net.impulsem.transport.auth.ImpulseRpcPaths;
import net.impulsem.transport.auth.TokenManager;
import net.impulsem.transport.errors.RpcError;
import net.impulsem.transport.policy.RequestPolicy;


/**
 * Tells the server what upstream Telegram's initConnection tells it: the app, the device and the languages.
 *
 * <p>The declaration goes out when a session exists, when the session's bearer changes and when the
 * declaration changes (a language switch), never without a bearer and never twice for the same pair.
 * Every attempt runs on the injected executor, so a slow or failing declaration never delays another
 * request. A failure is retried with {@link RequestPolicy} for at most {@link #MaxAttempts} attempts,
 * then dropped until the next trigger; nothing is thrown to the caller.
 */
public final class ConnectionDeclarer {

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
    private final Executor executor;
    private final Delayer delayer;
    private final Listener listener;

    private final Object lock = new Object();
    private ConnectionDeclaration declaration;
    private ConnectionDeclaration sentDeclaration;
    private String sentBearer;
    private boolean running;
    private boolean rerun;
    private long generation;


    public ConnectionDeclarer(
        RpcClient rpc,
        TokenManager tokens,
        Executor executor,
        Delayer delayer,
        Listener listener
    ) {
        this.rpc = rpc;
        this.tokens = tokens;
        this.executor = executor;
        this.delayer = delayer;
        this.listener = listener;
        tokens.setSessionListener(new Runnable() {
            @Override
            public void run() {
                sessionChanged();
            }
        });
    }


    /** Sets the whole declaration, at start-up. Sends it when it is new and a session exists. */
    public void setDeclaration(ConnectionDeclaration value) {
        synchronized (lock) {
            declaration = value;
        }
        trigger();
    }


    public void setLangCode(String langCode) {
        synchronized (lock) {
            if (declaration == null) {
                return;
            }
            declaration = declaration.withLangCode(langCode);
        }
        trigger();
    }


    public void setSystemLangCode(String langCode) {
        synchronized (lock) {
            if (declaration == null) {
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
        String bearer = tokens.bearer();
        ConnectionDeclaration current;
        boolean skip;
        synchronized (lock) {
            current = declaration;
            skip = bearer == null || current == null || (bearer.equals(sentBearer) && current.equals(sentDeclaration));
        }
        if (skip) {
            finish();
            return;
        }
        try {
            rpc.callImpulse(ImpulseRpcPaths.InitConnection, ImpulseRpcPaths.encodeInitConnection(current));
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
            sentBearer = bearer;
            sentDeclaration = current;
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
