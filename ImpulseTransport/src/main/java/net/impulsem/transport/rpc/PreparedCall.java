package net.impulsem.transport.rpc;

import net.impulsem.transport.codec.EncodedRequest;


/** A TL call encoded for the carriers. Run it through RpcClient.execute; cancel() may come from any thread. */
public final class PreparedCall {

    final EncodedRequest encoded;
    private CarrierCall current;
    private boolean canceled;


    PreparedCall(EncodedRequest encoded) {
        this.encoded = encoded;
    }


    /** The canon method name of the call. */
    public String method() {
        return encoded.method;
    }


    public void cancel() {
        CarrierCall target;
        synchronized (this) {
            canceled = true;
            target = current;
        }
        if (target != null) {
            target.cancel();
        }
    }


    public synchronized boolean isCanceled() {
        return canceled;
    }


    /** Makes this carrier call the target of cancel(); cancels it at once when cancel() already happened. */
    void attach(CarrierCall call) {
        boolean cancelNow;
        synchronized (this) {
            current = call;
            cancelNow = canceled;
        }
        if (cancelNow) {
            call.cancel();
        }
    }
}
