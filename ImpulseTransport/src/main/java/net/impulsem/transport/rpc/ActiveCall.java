package net.impulsem.transport.rpc;

import java.io.IOException;
import net.impulsem.transport.codec.EncodedRequest;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okio.Timeout;


/**
 * The caller-facing handle returned by {@link RpcClient#prepare}. It forwards cancel() to whichever
 * HTTP attempt is currently in flight, including rebuilt and retried ones. Run it only through
 * {@link RpcClient#execute}.
 */
final class ActiveCall implements Call {

    final EncodedRequest encoded;

    private Call current;
    private boolean canceled;


    ActiveCall(
        EncodedRequest encoded,
        Call initial
    ) {
        this.encoded = encoded;
        this.current = initial;
    }


    synchronized Call current() {
        return current;
    }


    /** Makes the attempt the target of cancel(); cancels it at once when cancel() already happened. */
    Call attach(Call attempt) {
        boolean cancelNow;
        synchronized (this) {
            current = attempt;
            cancelNow = canceled;
        }
        if (cancelNow) {
            attempt.cancel();
        }
        return attempt;
    }


    @Override
    public Request request() {
        return current().request();
    }


    @Override
    public okhttp3.Response execute() throws IOException {
        throw new UnsupportedOperationException("run through RpcClient.execute");
    }


    @Override
    public void enqueue(Callback responseCallback) {
        throw new UnsupportedOperationException("run through RpcClient.execute");
    }


    @Override
    public void cancel() {
        Call target;
        synchronized (this) {
            canceled = true;
            target = current;
        }
        target.cancel();
    }


    @Override
    public synchronized boolean isCanceled() {
        return canceled;
    }


    @Override
    public boolean isExecuted() {
        return current().isExecuted();
    }


    @Override
    public Timeout timeout() {
        return current().timeout();
    }


    @Override
    public Call clone() {
        return new ActiveCall(encoded, current().clone());
    }
}
