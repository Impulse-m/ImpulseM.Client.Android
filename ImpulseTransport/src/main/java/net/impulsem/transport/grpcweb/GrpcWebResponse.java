package net.impulsem.transport.grpcweb;

import java.util.Map;


public final class GrpcWebResponse {

    /** 0 means OK. */
    public final int grpcStatus;

    /** May be null. */
    public final String grpcMessage;

    /** Lower-case keys; HTTP headers, trailer frame and HTTP/2 trailers merged. */
    public final Map<String, String> metadata;

    /** Concatenated data frames; empty on trailers-only responses. */
    public final byte[] body;


    public GrpcWebResponse(
        int grpcStatus,
        String grpcMessage,
        Map<String, String> metadata,
        byte[] body
    ) {
        this.grpcStatus = grpcStatus;
        this.grpcMessage = grpcMessage;
        this.metadata = metadata;
        this.body = body;
    }
}
