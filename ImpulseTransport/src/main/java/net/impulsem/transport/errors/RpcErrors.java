package net.impulsem.transport.errors;

import java.util.Map;
import net.impulsem.transport.grpcweb.GrpcWebResponse;


public final class RpcErrors {

    private static final String[] STATUS_NAMES = {
        "OK",
        "CANCELLED",
        "UNKNOWN",
        "INVALID_ARGUMENT",
        "DEADLINE_EXCEEDED",
        "NOT_FOUND",
        "ALREADY_EXISTS",
        "PERMISSION_DENIED",
        "RESOURCE_EXHAUSTED",
        "FAILED_PRECONDITION",
        "ABORTED",
        "OUT_OF_RANGE",
        "UNIMPLEMENTED",
        "INTERNAL",
        "UNAVAILABLE",
        "DATA_LOSS",
        "UNAUTHENTICATED"
    };

    private static final String[] FLOOD_PREFIXES = {
        "FLOOD_WAIT_",
        "FLOOD_PREMIUM_WAIT_",
        "SLOWMODE_WAIT_"
    };


    private RpcErrors() {
    }


    /** Returns null when the response is OK. */
    public static RpcError fromResponse(GrpcWebResponse response) {
        if (response.grpcStatus == 0) {
            return null;
        }
        Map<String, String> metadata = response.metadata;
        int code = mapCode(response.grpcStatus, metadata != null && metadata.containsKey("migrate-dc"));
        String text = metadata == null ? null : metadata.get("error-code");
        if (response.grpcStatus == 12) {
            // An unregistered method: Telegram answers 400 METHOD_INVALID. A server-sent error-code
            // other than METHOD_INVALID wins and keeps 501.
            if (isEmpty(text)) {
                text = "METHOD_INVALID";
            }
            if ("METHOD_INVALID".equals(text)) {
                code = 400;
            }
        }
        if (isEmpty(text)) {
            text = response.grpcMessage;
        }
        if (isEmpty(text)) {
            text = statusName(response.grpcStatus);
        }
        return new RpcError(code, text);
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


    private static int mapCode(
        int status,
        boolean hasMigrateDc
    ) {
        switch (status) {
            case 8:
                return 420;
            case 9:
                return hasMigrateDc ? 303 : 406;
            case 3:
                return 400;
            case 16:
                return 401;
            case 7:
                return 403;
            case 5:
                return 404;
            case 6:
                return 409;
            case 12:
                return 501;
            case 14:
                return 503;
            default:
                return 500;
        }
    }


    private static String statusName(int status) {
        if (status >= 0 && status < STATUS_NAMES.length) {
            return STATUS_NAMES[status];
        }
        return "UNKNOWN";
    }


    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
