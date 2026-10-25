package net.impulsem.transport.rpc;


/** Sends one rpc over a Centrifugo connection. */
public interface RpcCarrier {

    /**
     * Sends {@code data} as an rpc named {@code method}. The callback runs exactly once, on the carrier's own thread:
     * onReply with the reply bytes (empty when the reply carried none), or onFailure.
     */
    CarrierCall call(
        String method,
        byte[] data,
        long timeoutMillis,
        CarrierCallback callback
    );
}
