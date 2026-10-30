package org.telegram.tgnet.impulse;

import okhttp3.HttpUrl;


/** The ImpulseM transport addresses, derived from the build's IMPULSEM_RPC_URL. Each is null when the build has none. */
public final class ImpulseEndpoints {

    private static final String RealtimePath = "/connection/websocket?cf_ws_frame_ping_pong=true";
    private static final String PoolPath = "/pool/connection/websocket?cf_protocol=protobuf&cf_ws_frame_ping_pong=true";


    private ImpulseEndpoints() {
    }


    /** The transport host as an HTTP base URL, or null when the build has none. */
    public static HttpUrl rpcBaseUrl() {
        String rpcUrl = ImpulseConfig.rpcUrl();
        return rpcUrl == null ? null : HttpUrl.get(rpcUrl);
    }


    /** The Centrifugo WebSocket URL: wss://host/connection/websocket?cf_ws_frame_ping_pong=true, or null. */
    public static String realtimeUrl() {
        String base = websocketBase();
        return base == null ? null : base + RealtimePath;
    }


    /** The token-less pool fleet's WebSocket URL, on the same host as the realtime socket, or null. */
    public static String poolUrl() {
        String base = websocketBase();
        return base == null ? null : base + PoolPath;
    }


    /** True when the build names a transport host, so that a connection may be attempted at all. */
    public static boolean isConfigured() {
        return ImpulseConfig.rpcUrl() != null;
    }


    private static String websocketBase() {
        HttpUrl base = rpcBaseUrl();
        if (base == null) {
            return null;
        }
        String scheme = base.isHttps() ? "wss" : "ws";
        int defaultPort = base.isHttps() ? 443 : 80;
        String authority = base.host();
        if (base.port() != defaultPort) {
            authority += ":" + base.port();
        }
        return scheme + "://" + authority;
    }
}
