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
        return decide(enabled, true, running);
    }


    /** With the transport switched off the main connection goes direct, whatever the core is doing. */
    public static Route decide(
        boolean enabled,
        boolean useForTransport,
        boolean running
    ) {
        if (!enabled || !useForTransport) {
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
