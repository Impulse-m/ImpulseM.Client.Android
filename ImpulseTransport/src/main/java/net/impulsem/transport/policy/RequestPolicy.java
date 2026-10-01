package net.impulsem.transport.policy;

import net.impulsem.transport.errors.AuthErrorClass;
import net.impulsem.transport.errors.RpcError;
import net.impulsem.transport.errors.RpcErrors;


/**
 * The request retry and delivery rules of the former C++ connection layer, as pure functions.
 * Line references point at jni/tgnet/ConnectionsManager.cpp.
 */
public final class RequestPolicy {

    public static final int FLAG_ENABLE_UNAUTHORIZED = 1;
    public static final int FLAG_FAIL_ON_SERVER_ERRORS = 2;
    public static final int FLAG_WITHOUT_LOGIN = 8;
    public static final int FLAG_FORCE_DOWNLOAD = 32;
    public static final int FLAG_INVOKE_AFTER = 64;
    public static final int FLAG_DO_NOT_WAIT_FLOOD_WAIT = 1024;
    public static final int FLAG_LISTEN_AFTER_CANCEL = 2048;
    public static final int FLAG_FAIL_ON_SERVER_ERRORS_EXCEPT_FLOOD_WAIT = 65536;

    public static final int CONNECTION_TYPE_GENERIC = 1;
    public static final int CONNECTION_TYPE_DOWNLOAD = 2;
    public static final int CONNECTION_TYPE_UPLOAD = 4;

    public enum Action {
        DELIVER,
        RETRY_AFTER,
        LOGOUT_AND_DELIVER
    }


    public static final class Decision {

        public final Action action;
        public final long delayMillis;

        /** For DELIVER decisions that replace the failure with a synthetic error; 0 and null otherwise. */
        public final int errorCode;
        public final String errorText;

        /** True for RETRY_AFTER decisions caused by FLOOD_PREMIUM_WAIT_X; the app is told (1405-1408). */
        public final boolean premiumFloodWait;


        Decision(
            Action action,
            long delayMillis,
            int errorCode,
            String errorText
        ) {
            this(action, delayMillis, errorCode, errorText, false);
        }


        Decision(
            Action action,
            long delayMillis,
            int errorCode,
            String errorText,
            boolean premiumFloodWait
        ) {
            this.action = action;
            this.delayMillis = delayMillis;
            this.errorCode = errorCode;
            this.errorText = errorText;
            this.premiumFloodWait = premiumFloodWait;
        }
    }


    private static final Decision DeliverAsIs = new Decision(Action.DELIVER, 0L, 0, null);
    private static final Decision LogoutAndDeliver = new Decision(Action.LOGOUT_AND_DELIVER, 0L, 0, null);

    private static final long MaxBackoffSeconds = 10L;
    private static final long DefaultFloodWaitSeconds = 2L;
    private static final int DownloadRetryMax = 6;
    private static final int ForceDownloadRetryMax = 10;


    private RequestPolicy() {
    }


    /**
     * Decides what to do with a server error. {@code attempt} is the number of failures this request
     * has already had (the C++ serverFailureCount).
     */
    public static Decision onError(
        int flags,
        int connectionType,
        RpcError error,
        int attempt
    ) {
        int code = error.code;
        String text = error.text == null ? "" : error.text;

        // 1519-1535: a dead session logs out, SESSION_PASSWORD_NEEDED is ignored.
        if (code == 401 && text.contains("SESSION_PASSWORD_NEEDED")) {
            return DeliverAsIs;
        }
        if (RpcErrors.classify(error) == AuthErrorClass.FORCE_LOGOUT) {
            return LogoutAndDeliver;
        }

        // 1344-1350
        boolean processEvenFailed = code == 500 && text.contains("AUTH_RESTART");
        boolean isWorkerBusy = code == 500 && text.contains("WORKER_BUSY_TOO_LONG_RETRY");
        boolean failServerErrors = (flags & FLAG_FAIL_ON_SERVER_ERRORS) == 0 || processEvenFailed;
        boolean exceptFloodWait = (flags & FLAG_FAIL_ON_SERVER_ERRORS_EXCEPT_FLOOD_WAIT) != 0;
        boolean ignoreFloodWait = (flags & FLAG_DO_NOT_WAIT_FLOOD_WAIT) != 0;

        // 1368-1374. In the C++ this branch sits behind the code < 0 test and can never run; it is checked first here so -504 behaves as written.
        if (failServerErrors && code == -504) {
            return ignoreFloodWait ? DeliverAsIs : retryAfter(2000L);
        }

        // 1352-1367
        if (failServerErrors && (code == 500 || code < 0)) {
            if (text.contains("MSG_WAIT_FAILED")) {
                return retryAfter(0L);
            }
            if (isWorkerBusy) {
                return retryAfter(0L);
            }
            return retryAfter(Math.min(Math.max(attempt, 0), MaxBackoffSeconds) * 1000L);
        }

        // 1375-1412
        if ((failServerErrors || exceptFloodWait)
            && code == 420
            && !ignoreFloodWait
            && !text.contains("STORY_SEND_FLOOD")
        ) {
            if (text.contains("SLOWMODE_WAIT_")) {
                return DeliverAsIs;
            }
            long waitSeconds = DefaultFloodWaitSeconds;
            if (text.contains("FLOOD_PREMIUM_WAIT_")) {
                waitSeconds = parseWait(text, "FLOOD_PREMIUM_WAIT_");
                return new Decision(Action.RETRY_AFTER, waitSeconds * 1000L, 0, null, true);
            } else if (text.contains("FLOOD_WAIT_")) {
                waitSeconds = parseWait(text, "FLOOD_WAIT_");
            }
            return retryAfter(waitSeconds * 1000L);
        }

        // 1417-1424
        if (failServerErrors && code == 400
            && (text.contains("MSG_WAIT_TIMEOUT") || text.contains("MSG_WAIT_FAILED"))
        ) {
            return retryAfter(0L);
        }

        return DeliverAsIs;
    }


    /**
     * Decides what to do when the transport could not reach the server. {@code attempt} is the
     * number of the failure, starting at 1 (the C++ retryCount after its increment).
     */
    public static Decision onNetworkFailure(
        int flags,
        int connectionType,
        int attempt
    ) {
        // 2616-2635: only download connections give up, after 6 attempts or 10 with ForceDownload.
        if ((connectionType & CONNECTION_TYPE_DOWNLOAD) != 0) {
            int retryMax = (flags & FLAG_FORCE_DOWNLOAD) != 0 ? ForceDownloadRetryMax : DownloadRetryMax;
            if (attempt >= retryMax) {
                return new Decision(Action.DELIVER, 0L, -123, "RETRY_LIMIT");
            }
        }
        return retryAfter(Math.min(Math.max(attempt, 1), MaxBackoffSeconds) * 1000L);
    }


    /** 1901, 1932, 1960: without a logged-in user only WithoutLogin requests are sent. */
    public static boolean mustWaitForLogin(
        int flags,
        boolean loggedIn
    ) {
        return !loggedIn && (flags & FLAG_WITHOUT_LOGIN) == 0;
    }


    /**
     * Requests that wait for a login can go as soon as a user is set and a session exists. The
     * C++ released them from setUserId only (3051-3075); here the session can also arrive later.
     */
    public static boolean canReleaseLoginWaiters(
        long userId,
        boolean hasSession
    ) {
        return userId != 0L && hasSession;
    }


    /** 1519-1535: a dead session logs out only an account that is logged in (currentUserId != 0). */
    public static boolean shouldForceLogout(long userId) {
        return userId != 0L;
    }


    private static Decision retryAfter(long delayMillis) {
        return new Decision(Action.RETRY_AFTER, delayMillis, 0, null);
    }


    /** atoi() of the text after the prefix; non-positive or missing numbers give the 2 second default. */
    private static long parseWait(
        String text,
        String prefix
    ) {
        int start = text.indexOf(prefix) + prefix.length();
        int end = start;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end == start || end - start > 9) {
            return DefaultFloodWaitSeconds;
        }
        long value = Long.parseLong(text.substring(start, end));
        return value <= 0L ? DefaultFloodWaitSeconds : value;
    }
}
