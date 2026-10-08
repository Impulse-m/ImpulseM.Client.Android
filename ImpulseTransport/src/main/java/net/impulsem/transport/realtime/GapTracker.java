package net.impulsem.transport.realtime;

import java.util.HashSet;
import java.util.Set;


/**
 * Decides when a (re)subscribe means the application missed updates. The user lane runs getDifference after a fresh
 * subscribe or a lost position, which replaces the old onSessionCreated, and skips it after a clean recovery (the
 * replay already delivered the missed updates). A channel lane does after a failed recovery, and after an unsubscribe push with
 * code 2502 (the server dropped the position, so the follow-up subscribe is not recovered).
 */
public final class GapTracker {

    /** The unsubscribe code after which the stored position is gone. */
    public static final int PositionDroppedCode = 2502;


    private final Set<String> positionDropped = new HashSet<String>();


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


    /** @return true when the application must run getDifference. */
    public synchronized boolean onSubscribed(
        String channel,
        boolean recovered,
        boolean wasRecovering
    ) {
        boolean dropped = positionDropped.remove(channel);
        if (channel.startsWith(PublicationRouter.UserPrefix)) {
            return dropped || !recovered;
        }
        return dropped || (wasRecovering && !recovered);
    }
}
