package net.impulsem.transport.rpc;


/** How a carrier settles a call: exactly one of the two methods runs, once. */
public interface CarrierCallback {

    void onReply(byte[] data);

    void onFailure(CarrierFailure failure);
}
