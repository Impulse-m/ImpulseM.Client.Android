package net.impulsem.transport.errors;

public final class RpcErrors {

    private static final String[] FLOOD_PREFIXES = {
        "FLOOD_WAIT_",
        "FLOOD_PREMIUM_WAIT_",
        "SLOWMODE_WAIT_"
    };


    private RpcErrors() {
    }


    public static AuthErrorClass classify(RpcError error) {
        if (error == null || error.text == null) {
            return AuthErrorClass.NONE;
        }
        switch (error.text) {
            case "AUTH_KEY_UNREGISTERED":
                return AuthErrorClass.REFRESH_AND_RETRY;
            case "SESSION_REVOKED":
            case "USER_DEACTIVATED":
            case "SESSION_EXPIRED":
            case "AUTH_TOKEN_EXPIRED":
            case "AUTH_KEY_INVALID":
                return AuthErrorClass.FORCE_LOGOUT;
            default:
                return AuthErrorClass.NONE;
        }
    }


    /** Seconds from FLOOD_WAIT_X, FLOOD_PREMIUM_WAIT_X or SLOWMODE_WAIT_X; -1 otherwise. */
    public static int floodWaitSeconds(RpcError error) {
        if (error == null || error.text == null) {
            return -1;
        }
        for (String prefix : FLOOD_PREFIXES) {
            if (error.text.startsWith(prefix)) {
                try {
                    return Integer.parseInt(error.text.substring(prefix.length()));
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }


}
