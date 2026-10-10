package net.impulsem.transport.logging;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;


/**
 * The allowlist every shipped field passes through. Free text has no path to Loki, so nothing is scrubbed here: a value
 * ships only when its type or its exact value cannot carry personal data, and everything else is dropped.
 */
public final class LogRedactor {

    private static final Pattern ErrorCode = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Set<String> KnownTokens = new HashSet<String>(Arrays.asList(
        "token_fetch",
        "silent_socket", "backoff_skipped", "connect_failed",
        "user", "channel",
        "subscribe_position_dropped", "subscribe_not_recovered", "publication_requests_difference",
        "publication_unexpected_constructor", "publication_decode_failed",
        "short_message_unknown_user", "short_message_missing_peer", "short_message_pts_gap", "min_channel",
        "unresolved_peer_in_update", "pts_gap", "qts_gap", "seq_gap", "updates_too_long",
        "send_multi_response", "send_response", "getChannelDifference", "getDifference",
        "storage_rename", "temp_row_deleted_server_row_kept", "temp_bubble_removed_server_bubble_kept", "chat_view",
        "no_random_row", "scheduled_sent_old_deleted", "temp_row_not_found", "same_id_date_only", "renamed",
        "fcm", "hcm",
        "auth_key_id_mismatch", "msg_key_mismatch",
        "already_pending",
        "too_old", "system_notifications_disabled", "duplicate_request", "same_device_other_account",
        "voip_service_running", "another_call_starting", "gsm_call_not_idle",
        "pre_notification", "foreground_service", "service", "voip_service", "pre_notification_dismiss", "starting_service",
        "peer", "from", "fwd_from", "mention",
        "no_state_load_current_state", "already_in_flight", "empty", "too_long", "slice", "difference", "error"
    ));


    private LogRedactor() {
    }


    /**
     * The value to ship for one structured field, or null when the field is dropped. Booleans, finite numbers, enum
     * constants and class types ship as they are; a string ships only when it is a known token or an error code.
     * Throwables are rendered by the payload, so they never reach this method.
     */
    public static Object admit(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number) {
            return finiteNumber((Number) value);
        }
        if (value instanceof Enum<?>) {
            return ((Enum<?>) value).name();
        }
        if (value instanceof Class<?>) {
            return ((Class<?>) value).getSimpleName();
        }
        if (value instanceof String && isEnumToken((String) value)) {
            return value;
        }
        return null;
    }


    /** A known token from the registry or an upper-snake error code of the Telegram catalog shape. */
    public static boolean isEnumToken(String text) {
        return text != null && (KnownTokens.contains(text) || ErrorCode.matcher(text).matches());
    }


    private static Object finiteNumber(Number number) {
        if (number instanceof Double || number instanceof Float) {
            double value = number.doubleValue();
            return Double.isNaN(value) || Double.isInfinite(value) ? null : number;
        }
        return number;
    }
}
