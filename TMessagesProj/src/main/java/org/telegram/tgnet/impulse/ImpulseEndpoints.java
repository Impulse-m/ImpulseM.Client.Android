package org.telegram.tgnet.impulse;

import org.telegram.messenger.BuildConfig;

import okhttp3.HttpUrl;


/** The ImpulseM backend addresses, derived from the IMPULSEM_ENDPOINT build property. */
public final class ImpulseEndpoints {

    private static final String RealtimePath = "/connection/websocket?cf_ws_frame_ping_pong=true";
    private static final String PoolPath = "/pool/connection/websocket?cf_protocol=protobuf&cf_ws_frame_ping_pong=true";


    private ImpulseEndpoints() {
    }


    public static HttpUrl rpcBaseUrl() {
        return HttpUrl.get(BuildConfig.IMPULSEM_ENDPOINT);
    }


    /** The Centrifugo WebSocket URL: wss://host/connection/websocket?cf_ws_frame_ping_pong=true. */
    public static String realtimeUrl() {
        return websocketBase() + RealtimePath;
    }


    /** The token-less pool fleet's WebSocket URL, on the same host as the realtime socket. */
    public static String poolUrl() {
        return websocketBase() + PoolPath;
    }


    private static String websocketBase() {
        HttpUrl base = rpcBaseUrl();
        String scheme = base.isHttps() ? "wss" : "ws";
        int defaultPort = base.isHttps() ? 443 : 80;
        String authority = base.host();
        if (base.port() != defaultPort) {
            authority += ":" + base.port();
        }
        return scheme + "://" + authority;
    }
}
