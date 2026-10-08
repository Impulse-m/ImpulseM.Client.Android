package net.impulsem.proxy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;


public class LibXrayClientTest {

    private static LibXrayClient client(final String response, final AtomicReference<String> sent) {
        return new LibXrayClient(new XrayRuntime() {
            @Override
            public String invoke(String requestJson) {
                sent.set(requestJson);
                return response;
            }
        });
    }


    @Test
    public void wrapsRequestInApiVersionEnvelope() throws Exception {
        AtomicReference<String> sent = new AtomicReference<String>();
        client("{\"success\":true,\"data\":{\"outbounds\":[]}}", sent).convertShareLinks("vless://x");
        JsonObject request = JsonParser.parseString(sent.get()).getAsJsonObject();
        assertEquals(3, request.get("apiVersion").getAsInt());
        assertEquals("convertShareLinksToXrayJson", request.get("method").getAsString());
        assertEquals("vless://x", request.getAsJsonObject("payload").get("text").getAsString());
    }


    @Test
    public void returnsOutbounds() throws Exception {
        AtomicReference<String> sent = new AtomicReference<String>();
        JsonArray outbounds = client(
            "{\"success\":true,\"data\":{\"outbounds\":[{\"protocol\":\"vless\",\"tag\":\"a\"}]}}",
            sent
        ).convertShareLinks("vless://x");
        assertEquals(1, outbounds.size());
    }


    @Test
    public void failureCarriesMethodAndError() {
        AtomicReference<String> sent = new AtomicReference<String>();
        try {
            client("{\"success\":false,\"error\":\"no valid outbound found\"}", sent).convertShareLinks("junk");
            fail();
        } catch (XrayException e) {
            assertEquals("convertShareLinksToXrayJson: no valid outbound found", e.getMessage());
        }
    }


    @Test
    public void pingBatchMapsFailuresToMinusOne() throws Exception {
        AtomicReference<String> sent = new AtomicReference<String>();
        long[] delays = client(
            "{\"success\":true,\"data\":{\"results\":[{\"success\":true,\"delay\":120},{\"success\":false,\"delay\":0,\"error\":\"timeout\"}]}}",
            sent
        ).pingBatch(Arrays.asList("{\"outbounds\":[]}", "{\"outbounds\":[]}"), "proxy", "https://example.com", 5);
        assertArrayEquals(new long[] {120L, -1L}, delays);
        JsonObject payload = JsonParser.parseString(sent.get()).getAsJsonObject().getAsJsonObject("payload");
        assertEquals(5, payload.get("timeout").getAsInt());
        assertEquals("proxy", payload.getAsJsonArray("configs").get(0).getAsJsonObject().get("outboundTag").getAsString());
    }


    @Test
    public void stateAndPorts() throws Exception {
        AtomicReference<String> sent = new AtomicReference<String>();
        assertTrue(client("{\"success\":true,\"data\":{\"running\":true}}", sent).isRunning());
        assertFalse(client("{\"success\":true,\"data\":{\"running\":false}}", sent).isRunning());
        assertArrayEquals(
            new int[] {40001, 40002},
            client("{\"success\":true,\"data\":{\"ports\":[40001,40002]}}", sent).freePorts(2)
        );
    }


    @Test(expected = XrayException.class)
    public void freePortsWithoutDataThrows() throws Exception {
        AtomicReference<String> sent = new AtomicReference<String>();
        client("{\"success\":true}", sent).freePorts(2);
    }


    @Test(expected = XrayException.class)
    public void freePortsWithNonNumericPortThrows() throws Exception {
        AtomicReference<String> sent = new AtomicReference<String>();
        client("{\"success\":true,\"data\":{\"ports\":[\"x\"]}}", sent).freePorts(1);
    }


    @Test
    public void isRunningWithoutRunningFieldNamesMethod() {
        AtomicReference<String> sent = new AtomicReference<String>();
        try {
            client("{\"success\":true,\"data\":{}}", sent).isRunning();
            fail();
        } catch (XrayException e) {
            assertEquals("getXrayState: malformed response", e.getMessage());
        }
    }


    @Test(expected = XrayException.class)
    public void nonBooleanSuccessThrows() throws Exception {
        AtomicReference<String> sent = new AtomicReference<String>();
        client("{\"success\":\"yes\"}", sent).convertShareLinks("vless://x");
    }


    @Test(expected = XrayException.class)
    public void nullRuntimeResponseThrows() throws Exception {
        AtomicReference<String> sent = new AtomicReference<String>();
        client(null, sent).convertShareLinks("vless://x");
    }


    @Test(expected = XrayException.class)
    public void pingBatchWithoutResultsThrows() throws Exception {
        AtomicReference<String> sent = new AtomicReference<String>();
        client("{\"success\":true,\"data\":{}}", sent).pingBatch(Arrays.asList("{}"), "proxy", "https://example.com", 5);
    }


    @Test
    public void pingBatchMissingEntriesAreMinusOne() throws Exception {
        AtomicReference<String> sent = new AtomicReference<String>();
        long[] delays = client(
            "{\"success\":true,\"data\":{\"results\":[{\"success\":true,\"delay\":120}]}}",
            sent
        ).pingBatch(Arrays.asList("{}", "{}"), "proxy", "https://example.com", 5);
        assertArrayEquals(new long[] {120L, -1L}, delays);
    }


    @Test
    public void pingBatchDetailedCarriesPerItemErrors() throws Exception {
        AtomicReference<String> sent = new AtomicReference<String>();
        LibXrayClient.PingResult[] results = client(
            "{\"success\":true,\"data\":{\"results\":[{\"success\":true,\"delay\":120},{\"success\":false,\"delay\":0,\"error\":\"timeout\"},{\"success\":false,\"error\":null}]}}",
            sent
        ).pingBatchDetailed(Arrays.asList("{}", "{}", "{}"), "proxy", "https://example.com", 5);
        assertEquals(3, results.length);
        assertEquals(120L, results[0].delay);
        assertNull(results[0].error);
        assertEquals(-1L, results[1].delay);
        assertEquals("timeout", results[1].error);
        assertEquals(-1L, results[2].delay);
        assertNull(results[2].error);
    }


    @Test
    public void pingBatchDetailedShortResultsAreMinusOneWithoutError() throws Exception {
        AtomicReference<String> sent = new AtomicReference<String>();
        LibXrayClient.PingResult[] results = client(
            "{\"success\":true,\"data\":{\"results\":[{\"success\":true,\"delay\":50}]}}",
            sent
        ).pingBatchDetailed(Arrays.asList("{}", "{}"), "proxy", "https://example.com", 5);
        assertEquals(2, results.length);
        assertEquals(50L, results[0].delay);
        assertEquals(-1L, results[1].delay);
        assertNull(results[1].error);
    }
}
