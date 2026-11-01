package net.impulsem.transport.realtime;

import java.util.HashSet;
import java.util.Set;


/**
 * Decides when a (re)subscribe means the application missed updates. The user lane runs getDifference after a fresh
 * subscribe or a lost position, which replaces the old onSessionCreated, and skips it after a clean recovery (the
 * replay already delivered the missed updates). It also runs once after every reconnect of the socket: the session
 * lane has no recovery, so updates published while the socket was down are gone unless they are asked for. A channel
 * lane does after a failed recovery, and after an unsubscribe push with code 2502 (the server dropped the position, so
 * the follow-up subscribe is not recovered).
 */
public final class GapTracker {

    /** The unsubscribe code after which the stored position is gone. */
    public static final int PositionDroppedCode = 2502;

    /** The getDifference reason for a subscribe after the position was dropped. */
    public static final String ReasonPositionDropped = "subscribe_position_dropped";

    /** The getDifference reason for a subscribe that could not recover the position. */
    public static final String ReasonNotRecovered = "subscribe_not_recovered";

    /** The getDifference reason when the only cause is a socket reconnect that the user lane recovered from. */
    public static final String ReasonSocketReconnected = "socket_reconnected";


    private final Set<String> positionDropped = new HashSet<String>();
    private boolean connectedBefore;
    private boolean reconnectPending;


    /** Called on every connected reply; a reply that follows an earlier one is a reconnect. */
    public synchronized void onConnected() {
        if (connectedBefore) {
            reconnectPending = true;
        }
        connectedBefore = true;
    }


    public synchronized void onUnsubscribed(
        String channel,
        int code
    ) {
        if (code == PositionDroppedCode) {
            positionDropped.add(channel);
        } else {
            positionDropped.remove(channel);
        }
    }


    /**
     * The pending reconnect is spent by the first user lane subscribe after it, so a socket reconnect costs one
     * getDifference even when the user lane recovers.
     *
     * @return the reason the application must run getDifference, or null when the subscribe needs none.
     */
    public synchronized String onSubscribed(
        String channel,
        boolean recovered,
        boolean wasRecovering
    ) {
        boolean dropped = positionDropped.remove(channel);
        if (channel.startsWith(PublicationRouter.UserPrefix)) {
            boolean reconnect = reconnectPending;
            reconnectPending = false;
            if (dropped) {
                return ReasonPositionDropped;
            }
            if (!recovered) {
                return ReasonNotRecovered;
            }
            return reconnect ? ReasonSocketReconnected : null;
        }
        if (dropped) {
            return ReasonPositionDropped;
        }
        if (wasRecovering && !recovered) {
            return ReasonNotRecovered;
        }
        return null;
    }
}
