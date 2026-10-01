package net.impulsem.transport.grpcweb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class GrpcWebClientTest {

    private MockWebServer server;
    private GrpcWebClient client;


    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new GrpcWebClient(new OkHttpClient(), server.url("/"));
    }


    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }


    private static byte[] frame(
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


    private static byte[] trailer(String text) {
        return frame(0x80, text.getBytes(StandardCharsets.UTF_8));
    }


    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }


    private void enqueue(
        int httpCode,
        byte[] body,
        String... headers
    ) {
        MockResponse response = new MockResponse()
            .setResponseCode(httpCode)
            .addHeader("Content-Type", "application/grpc-web")
            .setBody(new Buffer().write(body));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            response.addHeader(headers[i], headers[i + 1]);
        }
        server.enqueue(response);
    }


    private GrpcWebResponse run(byte[] request) throws IOException {
        return client.execute(client.newCall("/svc/Method", request, new HashMap<String, String>()));
    }


    @Test
    public void successDataAndTrailerFrame() throws Exception {
        byte[] payload = {1, 2, 3, 4, 5};
        enqueue(200, concat(frame(0, payload), trailer("grpc-status: 0\r\n")));
        GrpcWebResponse response = run(new byte[0]);
        assertEquals(0, response.grpcStatus);
        assertArrayEquals(payload, response.body);
        assertEquals("0", response.metadata.get("grpc-status"));
    }


    @Test
    public void trailersOnlyErrorInHttpHeaders() throws Exception {
        enqueue(
            200,
            new byte[0],
            "grpc-status", "16",
            "Error-Code", "AUTH_KEY_UNREGISTERED",
            "grpc-message", "nope"
        );
        GrpcWebResponse response = run(new byte[0]);
        assertEquals(16, response.grpcStatus);
        assertEquals("nope", response.grpcMessage);
        assertEquals("AUTH_KEY_UNREGISTERED", response.metadata.get("error-code"));
        assertEquals(0, response.body.length);
    }


    @Test
    public void errorInTrailerFrame() throws Exception {
        enqueue(200, trailer("grpc-status: 8\r\nError-Code: FLOOD_WAIT_7\r\nretry-after: 7\r\n"));
        GrpcWebResponse response = run(new byte[0]);
        assertEquals(8, response.grpcStatus);
        assertEquals("FLOOD_WAIT_7", response.metadata.get("error-code"));
        assertEquals("7", response.metadata.get("retry-after"));
    }


    @Test
    public void twoDataFramesAreConcatenated() throws Exception {
        enqueue(
            200,
            concat(frame(0, new byte[] {1, 2}), frame(0, new byte[] {3}), trailer("grpc-status: 0\r\n"))
        );
        assertArrayEquals(new byte[] {1, 2, 3}, run(new byte[0]).body);
    }


    @Test(expected = IOException.class)
    public void truncatedPayloadThrows() throws Exception {
        byte[] full = frame(0, new byte[] {1, 2, 3, 4});
        byte[] cut = new byte[full.length - 2];
        System.arraycopy(full, 0, cut, 0, cut.length);
        enqueue(200, cut);
        run(new byte[0]);
    }


    @Test(expected = IOException.class)
    public void truncatedHeaderThrows() throws Exception {
        enqueue(200, new byte[] {0, 0, 0});
        run(new byte[0]);
    }


    @Test(expected = IOException.class)
    public void http502Throws() throws Exception {
        enqueue(502, "bad gateway".getBytes(StandardCharsets.UTF_8));
        run(new byte[0]);
    }


    @Test(expected = IOException.class)
    public void missingGrpcStatusThrows() throws Exception {
        enqueue(200, frame(0, new byte[] {1}));
        run(new byte[0]);
    }


    @Test
    public void requestFramingAndHeaders() throws Exception {
        enqueue(200, trailer("grpc-status: 0\r\n"));
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("authorization", "Bearer tok");
        headers.put("x-takeout-id", "5");
        byte[] message = {9, 8, 7};
        client.execute(client.newCall("/impulse.sync.SyncService/GetCentrifugoToken", message, headers));
        RecordedRequest recorded = server.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/impulse.sync.SyncService/GetCentrifugoToken", recorded.getPath());
        assertEquals("application/grpc-web+proto", recorded.getHeader("content-type"));
        assertEquals("1", recorded.getHeader("x-grpc-web"));
        assertEquals("trailers", recorded.getHeader("te"));
        assertEquals("Bearer tok", recorded.getHeader("authorization"));
        assertEquals("5", recorded.getHeader("x-takeout-id"));
        assertArrayEquals(frame(0, message), recorded.getBody().readByteArray());
    }


    @Test
    public void emptyRequestStillHasFivePrefixBytes() throws Exception {
        enqueue(200, trailer("grpc-status: 0\r\n"));
        run(new byte[0]);
        assertArrayEquals(new byte[] {0, 0, 0, 0, 0}, server.takeRequest().getBody().readByteArray());
    }


    @Test
    public void grpcMessageNullWhenAbsent() throws Exception {
        enqueue(200, trailer("grpc-status: 0\r\n"));
        assertNull(run(new byte[0]).grpcMessage);
    }


    @Test
    public void grpcMessagePercentDecoding() throws Exception {
        enqueue(200, new byte[0], "grpc-status", "13", "grpc-message", "bad%21");
        assertEquals("bad!", run(new byte[0]).grpcMessage);
        enqueue(200, new byte[0], "grpc-status", "13", "grpc-message", "a%20b%21c");
        assertEquals("a b!c", run(new byte[0]).grpcMessage);
    }


    @Test
    public void trailerFrameErrorOverridesHeaderOk() throws Exception {
        enqueue(
            200,
            trailer("grpc-status: 16\r\nerror-code: AUTH_KEY_UNREGISTERED\r\n"),
            "grpc-status", "0"
        );
        GrpcWebResponse response = run(new byte[0]);
        assertEquals(16, response.grpcStatus);
        assertEquals("AUTH_KEY_UNREGISTERED", response.metadata.get("error-code"));
    }


    @Test
    public void headerErrorNotOverwrittenByTrailerFrameOk() throws Exception {
        enqueue(200, trailer("grpc-status: 0\r\n"), "grpc-status", "8", "error-code", "FLOOD_WAIT_1");
        GrpcWebResponse response = run(new byte[0]);
        assertEquals(8, response.grpcStatus);
        assertEquals("FLOOD_WAIT_1", response.metadata.get("error-code"));
    }


    @Test
    public void trailerFrameNonZeroWinsOverHeaderNonZero() throws Exception {
        enqueue(200, trailer("grpc-status: 16\r\n"), "grpc-status", "8");
        assertEquals(16, run(new byte[0]).grpcStatus);
    }


    @Test
    public void trailerFrameFollowedByDataFrameStillParses() throws Exception {
        enqueue(
            200,
            concat(frame(0, new byte[] {1}), trailer("grpc-status: 0\r\n"), frame(0, new byte[] {2}))
        );
        GrpcWebResponse response = run(new byte[0]);
        assertEquals(0, response.grpcStatus);
        assertArrayEquals(new byte[] {1, 2}, response.body);
    }


    @Test(expected = IOException.class)
    public void nonNumericGrpcStatusThrows() throws Exception {
        enqueue(200, new byte[0], "grpc-status", "abc");
        run(new byte[0]);
    }
}
