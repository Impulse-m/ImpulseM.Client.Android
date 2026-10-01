package org.telegram.tgnet.impulse;


/** One serialized request handed from ConnectionsManager to the ImpulseM transport. */
public final class RequestEntry {

    public final int token;
    public final byte[] data;
    public final int flags;
    public final int datacenterId;
    public final int connectionType;
    public final boolean immediate;
    public final boolean hasCallback;


    public RequestEntry(
        int token,
        byte[] data,
        int flags,
        int datacenterId,
        int connectionType,
        boolean immediate,
        boolean hasCallback
    ) {
        this.token = token;
        this.data = data;
        this.flags = flags;
        this.datacenterId = datacenterId;
        this.connectionType = connectionType;
        this.immediate = immediate;
        this.hasCallback = hasCallback;
    }
}
