package net.impulsem.proxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/** A subscription URL and the servers it produced on the last successful refresh. */
public final class Subscription {
    public final String id;
    public final String url;
    public final SubscriptionMeta meta;
    public final long updatedAt;
    public final String lastError;
    public final int skipped;
    public final List<ProxyServer> servers;


    public Subscription(
        String id,
        String url,
        SubscriptionMeta meta,
        long updatedAt,
        String lastError,
        int skipped,
        List<ProxyServer> servers
    ) {
        this.id = id;
        this.url = url;
        this.meta = meta;
        this.updatedAt = updatedAt;
        this.lastError = lastError;
        this.skipped = skipped;
        this.servers = Collections.unmodifiableList(new ArrayList<ProxyServer>(servers));
    }
}
