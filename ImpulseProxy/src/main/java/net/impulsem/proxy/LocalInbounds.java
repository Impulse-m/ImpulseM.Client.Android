package net.impulsem.proxy;


/** Loopback ports and per-start credentials of the running core. */
public final class LocalInbounds {
    public final int httpPort;
    public final int socksPort;
    public final String user;
    public final String password;


    public LocalInbounds(
        int httpPort,
        int socksPort,
        String user,
        String password
    ) {
        this.httpPort = httpPort;
        this.socksPort = socksPort;
        this.user = user;
        this.password = password;
    }
}
