package net.impulsem.transport.grpcweb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;


public final class GrpcWebClient {

    private static final MediaType CONTENT_TYPE = MediaType.get("application/grpc-web+proto");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int TRAILER_FLAG = 0x80;

    private final OkHttpClient http;
    private final HttpUrl baseUrl;


    public GrpcWebClient(
        OkHttpClient http,
        HttpUrl baseUrl
    ) {
        this.http = http;
        this.baseUrl = baseUrl;
    }


    public Call newCall(
        String path,
        byte[] protoMessage,
        Map<String, String> headers
    ) {
        byte[] framed = new byte[5 + protoMessage.length];
        framed[0] = 0;
        framed[1] = (byte) (protoMessage.length >>> 24);
        framed[2] = (byte) (protoMessage.length >>> 16);
        framed[3] = (byte) (protoMessage.length >>> 8);
        framed[4] = (byte) protoMessage.length;
        System.arraycopy(protoMessage, 0, framed, 5, protoMessage.length);

        String relative = path.startsWith("/") ? path.substring(1) : path;
        HttpUrl url = baseUrl.newBuilder().addEncodedPathSegments(relative).build();
        Request.Builder builder = new Request.Builder()
            .url(url)
            .post(RequestBody.create(framed, CONTENT_TYPE))
            .header("x-grpc-web", "1")
            .header("te", "trailers")
            .header("accept", "application/grpc-web+proto");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }
        return http.newCall(builder.build());
    }


    public GrpcWebResponse execute(Call call) throws IOException {
        try (Response response = call.execute()) {
            if (response.code() != 200) {
                throw new IOException("HTTP " + response.code() + " " + response.message());
            }
            Map<String, String> metadata = new HashMap<String, String>();
            putAll(metadata, response.headers());

            ResponseBody responseBody = response.body();
            byte[] raw = responseBody == null ? new byte[0] : responseBody.bytes();
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            parseFrames(raw, data, metadata);

            try {
                putAll(metadata, response.trailers());
            } catch (IOException | RuntimeException e) {
                // HTTP/1.1 has no trailers; nothing to merge.
            }

            String statusText = metadata.get("grpc-status");
            if (statusText == null) {
                throw new IOException("Missing grpc-status in response");
            }
            int status;
            try {
                status = Integer.parseInt(statusText.trim());
            } catch (NumberFormatException e) {
                throw new IOException("Invalid grpc-status: " + statusText);
            }
            String message = metadata.get("grpc-message");
            if (message != null) {
                message = percentDecode(message);
            }
            return new GrpcWebResponse(status, message, metadata, data.toByteArray());
        }
    }


    private static void putAll(
        Map<String, String> metadata,
        Headers headers
    ) {
        for (int i = 0; i < headers.size(); i++) {
            put(metadata, headers.name(i).toLowerCase(Locale.ROOT), headers.value(i));
        }
    }


    /**
     * A trailer frame is not required to be last: it is merged into the metadata and parsing
     * continues, so data frames that follow it are still appended to the body.
     */
    private static void parseFrames(
        byte[] raw,
        ByteArrayOutputStream data,
        Map<String, String> metadata
    ) throws IOException {
        int pos = 0;
        while (pos < raw.length) {
            if (raw.length - pos < 5) {
                throw new IOException("Truncated gRPC-Web frame header");
            }
            int flag = raw[pos] & 0xFF;
            long length = ((raw[pos + 1] & 0xFFL) << 24)
                | ((raw[pos + 2] & 0xFFL) << 16)
                | ((raw[pos + 3] & 0xFFL) << 8)
                | (raw[pos + 4] & 0xFFL);
            pos += 5;
            if (length > raw.length - pos) {
                throw new IOException("Truncated gRPC-Web frame payload");
            }
            int len = (int) length;
            if ((flag & TRAILER_FLAG) != 0) {
                parseTrailerFrame(new String(raw, pos, len, UTF8), metadata);
            } else {
                data.write(raw, pos, len);
            }
            pos += len;
        }
    }


    private static void parseTrailerFrame(
        String text,
        Map<String, String> metadata
    ) {
        String[] lines = text.split("\r?\n");
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            put(metadata, key, value);
        }
    }


    /**
     * Stores a metadata entry. Error wins: a grpc-status of 0 never overwrites a non-zero
     * grpc-status that came from an earlier source. Sources are merged in the order HTTP headers,
     * trailer frame, HTTP/2 trailers, so a later non-zero value overrides an earlier non-zero one.
     */
    private static void put(
        Map<String, String> metadata,
        String key,
        String value
    ) {
        if ("grpc-status".equals(key) && isZeroStatus(value) && isNonZeroStatus(metadata.get(key))) {
            return;
        }
        metadata.put(key, value);
    }


    private static boolean isZeroStatus(String value) {
        return value != null && value.trim().equals("0");
    }


    private static boolean isNonZeroStatus(String value) {
        if (value == null) {
            return false;
        }
        try {
            return Integer.parseInt(value.trim()) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }


    private static String percentDecode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = value.getBytes(UTF8);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '%' && i + 2 < bytes.length && isHex(bytes[i + 1]) && isHex(bytes[i + 2])) {
                out.write(Character.digit(bytes[i + 1], 16) * 16 + Character.digit(bytes[i + 2], 16));
                i += 2;
            } else {
                out.write(bytes[i]);
            }
        }
        return new String(out.toByteArray(), UTF8);
    }


    private static boolean isHex(byte b) {
        return Character.digit(b, 16) >= 0;
    }
}
