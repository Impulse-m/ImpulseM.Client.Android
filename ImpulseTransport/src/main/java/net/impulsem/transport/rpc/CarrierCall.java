package net.impulsem.transport.rpc;


/** A call a carrier has accepted. */
public interface CarrierCall {

    /** Asks the carrier to drop the call; its callback still runs once, with a failure. */
    void cancel();
}
