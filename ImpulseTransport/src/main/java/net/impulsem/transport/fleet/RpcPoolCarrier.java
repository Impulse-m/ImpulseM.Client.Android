package net.impulsem.transport.fleet;

import io.github.centrifugal.centrifuge.ConnectionTokenGetter;
import io.github.centrifugal.centrifuge.RPCResult;
import io.github.centrifugal.centrifuge.ResultCallback;
import io.github.centrifugal.centrifuge.RpcPool;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.impulsem.transport.rpc.CarrierCall;
import net.impulsem.transport.rpc.CarrierCallback;
import net.impulsem.transport.rpc.CarrierFailure;
import net.impulsem.transport.rpc.RpcCarrier;
import okhttp3.OkHttpClient;


/** One SDK RpcPool on one endpoint, as an RpcCarrier. */
public final class RpcPoolCarrier implements RpcCarrier {

    /** The methods the token-less pool fleet serves. */
    public static final Set<String> PoolMethods = Collections.unmodifiableSet(
        new LinkedHashSet<String>(Arrays.asList("connectionIdentity", "refreshSession"))
    );

    private static final Logger Log = Logger.getLogger(RpcPoolCarrier.class.getName());

    private static final String ClientName = "impulsem-android";
    private static final long IdleTimeoutMillis = 60000L;
    private static final int PoolConnections = 1;
    private static final int PoolInflight = 1;
    private static final int PoolMaxQueued = 64;
    private static final long PoolTimeoutMillis = 30000L;


    private final RpcPool pool;
    private final long carrierTimeoutMillis;
    /** Null when the fleet serves any method. */
    private final Set<String> allowedMethods;
    /** False when the build gave no endpoint: every call then fails at once and nothing is dialled. */
    private final boolean configured;


    public RpcPoolCarrier(
        OkHttpClient http,
        String endpoint,
        int connectionsPerEndpoint,
        int inflightPerConnection,
        int maxQueued,
        long timeoutMillis,
        ConnectionTokenGetter tokenGetter,
        Set<String> allowedMethods
    ) {
        this.carrierTimeoutMillis = timeoutMillis;
        this.allowedMethods = allowedMethods == null
            ? null
            : Collections.unmodifiableSet(new LinkedHashSet<String>(allowedMethods));
        this.configured = endpoint != null;
        RpcPool.EndpointResolver resolver = new RpcPool.EndpointResolver() {
            @Override
            public List<String> endpoints(String endpointKey) {
                return endpoint == null ? Collections.<String>emptyList() : Collections.singletonList(endpoint);
            }
        };
        RpcPool.Builder builder = new RpcPool.Builder(resolver)
            .okHttpClient(http)
            .maxConnectionsPerEndpoint(connectionsPerEndpoint)
            .maxInflightPerConnection(inflightPerConnection)
            .maxQueued(maxQueued)
            .timeout((int) timeoutMillis)
            .idleTimeout(IdleTimeoutMillis)
            .name(ClientName);
        if (tokenGetter != null) {
            builder.tokenGetter(tokenGetter);
        }
        this.pool = builder.build();
    }


    /** The token-less pool fleet: 1 connection, 1 call in flight, 64 queued, 30 s, PoolMethods only. */
    public static RpcPoolCarrier poolFleet(
        OkHttpClient http,
        String endpoint
    ) {
        return new RpcPoolCarrier(
            http,
            endpoint,
            PoolConnections,
            PoolInflight,
            PoolMaxQueued,
            PoolTimeoutMillis,
            null,
            PoolMethods
        );
    }


    /**
     * Sends the rpc through the pool. A deadline above the carrier timeout is cut to it; a timeout of zero or less means
     * the carrier timeout.
     */
    @Override
    public CarrierCall call(
        final String method,
        byte[] data,
        long timeoutMillis,
        final CarrierCallback callback
    ) {
        if (allowedMethods != null && !allowedMethods.contains(method)) {
            throw new IllegalArgumentException("method " + method + " is not served by this fleet");
        }
        if (!configured) {
            callback.onFailure(CarrierFailure.of(new IOException("no transport endpoint is configured for " + method)));
            return new CarrierCall() {
                @Override
                public void cancel() {
                }
            };
        }
        long deadline = timeoutMillis <= 0L ? carrierTimeoutMillis : Math.min(timeoutMillis, carrierTimeoutMillis);
        final RpcPool.Call poolCall = pool.rpc(
            method,
            data,
            RpcPool.CallOptions.builder().timeout(deadline).build(),
            new ResultCallback<RPCResult>() {
                @Override
                public void onDone(
                    Throwable error,
                    RPCResult result
                ) {
                    try {
                        if (error != null) {
                            callback.onFailure(CarrierFailure.of(error));
                        } else {
                            byte[] reply = result.getData();
                            callback.onReply(reply == null ? new byte[0] : reply);
                        }
                    } catch (RuntimeException ex) {
                        // An exception escaping an SDK callback disconnects the socket, so it is logged here.
                        Log.log(Level.WARNING, "rpc callback of " + method + " threw", ex);
                    }
                }
            }
        );
        return new CarrierCall() {
            @Override
            public void cancel() {
                poolCall.cancel();
            }
        };
    }


    /** Closes every connection; calls in flight fail. */
    public void close() {
        pool.close();
    }
}
