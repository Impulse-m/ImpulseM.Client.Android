package net.impulsem.transport.rpc;

import net.impulsem.transport.errors.RpcError;


/** Maps a carrier failure to the Telegram error of the call, as Web K's fleetError.ts does. */
public final class CarrierErrors {

    public static final int TokenExpiredCode = 109;
    public static final int TokenInvalidCode = 103;
    public static final int MethodInvalidCode = 104;
    public static final int CancelledCode = 17;

    private static final int MigrateCode = 303;
    private static final int RefusalCode = 400;
    private static final int AppErrorDefaultCode = 400;
    private static final String MigrateDcMetadata = "migrate-dc";


    private CarrierErrors() {
    }


    /**
     * The Telegram error the call answered, or null when the failure is the carrier's own fault (a lost connection, a
     * timeout, a client-local SDK code) and the call should take the network-failure path.
     */
    public static RpcError toRpcError(CarrierFailure failure) {
        if (!failure.appType.isEmpty()) {
            int code = failure.metadata.containsKey(MigrateDcMetadata)
                ? MigrateCode
                : (failure.appCode != 0 ? failure.appCode : AppErrorDefaultCode);
            return new RpcError(code, failure.appType);
        }
        if (failure.sdkCode == TokenInvalidCode) {
            return new RpcError(RefusalCode, "AUTH_TOKEN_INVALID");
        }
        if (failure.sdkCode == TokenExpiredCode) {
            return new RpcError(RefusalCode, "AUTH_TOKEN_EXPIRED");
        }
        if (failure.sdkCode == MethodInvalidCode) {
            return new RpcError(RefusalCode, "METHOD_INVALID");
        }
        return null;
    }


    /** True when the call was cancelled locally (ReplyError code 17). */
    public static boolean isCancelled(CarrierFailure failure) {
        return failure.sdkCode == CancelledCode;
    }


    /** True when the server refused the token the connection or the call presented (codes 103 and 109). */
    public static boolean isTokenRefusal(CarrierFailure failure) {
        return failure.sdkCode == TokenInvalidCode || failure.sdkCode == TokenExpiredCode;
    }
}
