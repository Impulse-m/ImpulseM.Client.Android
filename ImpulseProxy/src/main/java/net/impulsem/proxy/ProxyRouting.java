package net.impulsem.proxy;


/** Kill switch: with the proxy setting on, traffic goes through the core or nowhere. */
public final class ProxyRouting {

    public enum Route {
        DIRECT,
        PROXY,
        BLOCKED
    }


    private ProxyRouting() {
    }


    public static Route decide(
        boolean enabled,
        boolean running
    ) {
        if (!enabled) {
            return Route.DIRECT;
        }
        return running ? Route.PROXY : Route.BLOCKED;
    }


    /**
     * Whether a call must be relayed through the tunnel. Deliberately independent of whether the core is running:
     * with the core down the tunnelled call fails closed instead of going direct.
     */
    public static boolean tunnelCalls(
        boolean vlessFeature,
        boolean enabled,
        boolean useForCalls
    ) {
        return vlessFeature && enabled && useForCalls;
    }
}
