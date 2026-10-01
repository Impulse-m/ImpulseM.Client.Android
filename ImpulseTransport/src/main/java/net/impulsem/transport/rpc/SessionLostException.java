package net.impulsem.transport.rpc;

import java.io.IOException;
import net.impulsem.transport.errors.RpcError;


/** The session is unrecoverable; the caller must log out. */
public final class SessionLostException extends IOException {

    public final RpcError error;


    public SessionLostException(
        String message,
        RpcError error
    ) {
        super(message);
        this.error = error;
    }
}
