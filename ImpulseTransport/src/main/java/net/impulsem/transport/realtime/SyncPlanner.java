package net.impulsem.transport.realtime;


/**
 * Turns the observed login and network state into the connection actions to take. The remembered state (the user the
 * lane belongs to, the last online flag) changes inside one synchronized call, so the decision and the mutation are
 * atomic even if callers overlap.
 */
public final class SyncPlanner {

    public static final class Plan {

        /** User whose lane must be dropped first, or 0. */
        public final long dropUserLane;
        /** Stop and clear the channel lanes. */
        public final boolean stopLanes;
        public final boolean disconnect;
        /** User whose lane must be subscribed, or 0. */
        public final long subscribeUserLane;
        public final boolean connect;


        Plan(
            long dropUserLane,
            boolean stopLanes,
            boolean disconnect,
            long subscribeUserLane,
            boolean connect
        ) {
            this.dropUserLane = dropUserLane;
            this.stopLanes = stopLanes;
            this.disconnect = disconnect;
            this.subscribeUserLane = subscribeUserLane;
            this.connect = connect;
        }
    }


    /** The credential the socket presents: the identity token when signed out, the session's access token otherwise. */
    private enum SocketKind {
        IDENTITY,
        SESSION
    }


    private long laneUserId;
    private boolean wasOnline = true;
    /** The kind the socket last connected with; null until the first online plan. */
    private SocketKind connectedKind;


    public synchronized Plan plan(
        long userId,
        boolean sessionPresent,
        boolean online
    ) {
        boolean loggedIn = userId != 0L && sessionPresent;
        boolean cameBack = online && !wasOnline;
        wasOnline = online;
        long previous = laneUserId;
        laneUserId = loggedIn ? userId : 0L;
        long drop = previous != 0L && previous != laneUserId ? previous : 0L;
        boolean stopLanes = !loggedIn || drop != 0L;
        if (!online) {
            return new Plan(drop, stopLanes, true, 0L, false);
        }
        SocketKind kind = sessionPresent ? SocketKind.SESSION : SocketKind.IDENTITY;
        boolean kindChanged = connectedKind != null && connectedKind != kind;
        connectedKind = kind;
        // A returning network, a switched user or a new credential kind drops the socket first: the backoff is skipped
        // and the old token is not kept. The very first connect has nothing to drop.
        boolean rekey = cameBack || drop != 0L || kindChanged;
        return new Plan(drop, stopLanes, rekey, loggedIn ? userId : 0L, true);
    }
}
