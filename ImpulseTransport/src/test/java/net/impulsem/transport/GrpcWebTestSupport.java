package net.impulsem.transport;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import okhttp3.mockwebserver.MockResponse;
import okio.Buffer;


/** Test helper that builds gRPC-Web responses for MockWebServer. */
public final class GrpcWebTestSupport {

    private GrpcWebTestSupport() {
    }


    public static byte[] frame(
        int flag,
        byte[] payload
    ) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(flag);
        out.write((payload.length >>> 24) & 0xFF);
        out.write((payload.length >>> 16) & 0xFF);
        out.write((payload.length >>> 8) & 0xFF);
        out.write(payload.length & 0xFF);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }


    public static MockResponse ok(byte[] proto) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = frame(0, proto);
        out.write(data, 0, data.length);
        byte[] trailer = frame(0x80, "grpc-status: 0\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(trailer, 0, trailer.length);
        return response(out.toByteArray());
    }


    /** Trailers-only error: status and metadata travel in HTTP headers. */
    public static MockResponse error(
        int grpcStatus,
        String errorCode,
        String... extraHeaders
    ) {
        MockResponse response = response(new byte[0]);
        response.addHeader("grpc-status", String.valueOf(grpcStatus));
        response.addHeader("error-code", errorCode);
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
            response.addHeader(extraHeaders[i], extraHeaders[i + 1]);
        }
        return response;
    }


    private static MockResponse response(byte[] body) {
        return new MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/grpc-web")
            .setBody(new Buffer().write(body));
    }
}
