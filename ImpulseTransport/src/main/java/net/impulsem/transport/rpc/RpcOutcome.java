package net.impulsem.transport.rpc;

import net.impulsem.transport.errors.RpcError;


public final class RpcOutcome {

    /** TL result bytes; null on error. */
    public final byte[] tlResult;

    /** Null on success. */
    public final RpcError error;

    /** The session is gone and the app must log out. */
    public final boolean forceLogout;


    public RpcOutcome(
        byte[] tlResult,
        RpcError error,
        boolean forceLogout
    ) {
        this.tlResult = tlResult;
        this.error = error;
        this.forceLogout = forceLogout;
    }
}
