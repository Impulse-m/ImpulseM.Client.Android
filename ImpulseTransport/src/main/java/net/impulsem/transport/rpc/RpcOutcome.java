package net.impulsem.transport.rpc;

import net.impulsem.transport.errors.RpcError;


public final class RpcOutcome {

    /** TL result bytes; null on error. */
    public final byte[] tlResult;

    /** Null on success. */
    public final RpcError error;

    /** The session is gone and the app must log out. */
    public final boolean forceLogout;

    /** For TRANSCODE_FAILED: the rpc path and why its response broke the contract; null otherwise. */
    public final String transcodeDetail;


    public RpcOutcome(
        byte[] tlResult,
        RpcError error,
        boolean forceLogout
    ) {
        this(tlResult, error, forceLogout, null);
    }


    public RpcOutcome(
        byte[] tlResult,
        RpcError error,
        boolean forceLogout,
        String transcodeDetail
    ) {
        this.tlResult = tlResult;
        this.error = error;
        this.forceLogout = forceLogout;
        this.transcodeDetail = transcodeDetail;
    }
}
