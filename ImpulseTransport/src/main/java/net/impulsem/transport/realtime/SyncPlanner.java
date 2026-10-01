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


    private long laneUserId;
    private boolean wasOnline = true;


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
        if (!loggedIn) {
            return new Plan(drop, true, true, 0L, false);
        }
        if (!online) {
            return new Plan(drop, drop != 0L, true, 0L, false);
        }
        // A returning network drops the socket first so the reconnect backoff is skipped.
        return new Plan(drop, drop != 0L, cameBack, userId, true);
    }
}
