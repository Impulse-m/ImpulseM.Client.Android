package net.impulsem.transport.rpc;

import java.io.IOException;
import net.impulsem.transport.errors.RpcError;


/** The server answered an impulse.* call with an error status; the error is kept so callers can apply a retry policy. */
public final class RpcStatusException extends IOException {

    public final RpcError error;


    public RpcStatusException(
        String message,
        RpcError error
    ) {
        super(message);
        this.error = error;
    }
}
