package net.impulsem.proxy;


/** What the periodic core health check does next. */
public final class HealthPolicy {

    public enum Action {
        /** The check is stale (newer generation or no longer running): do nothing and stop re-arming. */
        STOP,
        /** The core is healthy: clear the retry allowance and schedule the next check. */
        RECHECK,
        /** First failure: restart the core once. */
        RESTART,
        /** The core died again after a restart: give up. */
        FAIL
    }


    private HealthPolicy() {
    }


    public static Action decide(
        boolean current,
        boolean alreadyRetried,
        boolean alive
    ) {
        if (!current) {
            return Action.STOP;
        }
        if (alive) {
            return Action.RECHECK;
        }
        return alreadyRetried ? Action.FAIL : Action.RESTART;
    }
}
