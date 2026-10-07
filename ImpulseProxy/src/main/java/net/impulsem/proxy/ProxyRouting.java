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
}
