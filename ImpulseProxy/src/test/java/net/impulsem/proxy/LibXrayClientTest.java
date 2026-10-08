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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
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


    private static LibXrayClient queued(
        final LinkedList<String> responses,
        final List<String> sent
    ) {
        return new LibXrayClient(new XrayRuntime() {
            @Override
            public String invoke(String requestJson) {
                sent.add(requestJson);
                return responses.removeFirst();
            }
        });
    }


    private static String pingResponse(long... delays) {
        StringBuilder out = new StringBuilder("{\"success\":true,\"data\":{\"results\":[");
        for (int i = 0; i < delays.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"success\":true,\"delay\":").append(delays[i]).append('}');
        }
        return out.append("]}}").toString();
    }


    private static List<String> configs(int count) {
        List<String> list = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            list.add("c" + i);
        }
        return list;
    }


    private static List<String> sentConfigs(String request) {
        List<String> out = new ArrayList<String>();
        JsonArray array = JsonParser.parseString(request).getAsJsonObject()
            .getAsJsonObject("payload").getAsJsonArray("configs");
        for (int i = 0; i < array.size(); i++) {
            out.add(array.get(i).getAsJsonObject().get("xrayJson").getAsString());
        }
        return out;
    }


    private static LibXrayClient.PingChunkListener collecting(
        final List<Integer> offsets,
        final List<LibXrayClient.PingResult> all,
        final boolean proceed
    ) {
        return new LibXrayClient.PingChunkListener() {
            @Override
            public boolean onChunk(
                int offset,
                LibXrayClient.PingResult[] results
            ) {
                offsets.add(offset);
                all.addAll(Arrays.asList(results));
                return proceed;
            }
        };
    }


    @Test
    public void pingInChunksSplitsIntoBatchesOfFive() {
        LinkedList<String> responses = new LinkedList<String>(Arrays.asList(
            pingResponse(10, 11, 12, 13, 14),
            pingResponse(20, 21, 22, 23, 24),
            pingResponse(30, 31)
        ));
        List<String> sent = new ArrayList<String>();
        List<Integer> offsets = new ArrayList<Integer>();
        List<LibXrayClient.PingResult> all = new ArrayList<LibXrayClient.PingResult>();
        queued(responses, sent).pingInChunks(configs(12), "proxy", "https://example.com", 5, collecting(offsets, all, true));
        assertEquals(3, sent.size());
        assertEquals(Arrays.asList("c0", "c1", "c2", "c3", "c4"), sentConfigs(sent.get(0)));
        assertEquals(Arrays.asList("c5", "c6", "c7", "c8", "c9"), sentConfigs(sent.get(1)));
        assertEquals(Arrays.asList("c10", "c11"), sentConfigs(sent.get(2)));
        assertEquals(Arrays.asList(0, 5, 10), offsets);
        long[] expected = {10, 11, 12, 13, 14, 20, 21, 22, 23, 24, 30, 31};
        assertEquals(12, all.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], all.get(i).delay);
        }
    }


    @Test
    public void pingInChunksFailedChunkReportsErrorAndContinues() {
        LinkedList<String> responses = new LinkedList<String>(Arrays.asList(
            pingResponse(10, 11, 12, 13, 14),
            "{\"success\":false,\"error\":\"boom\"}",
            pingResponse(30, 31)
        ));
        List<String> sent = new ArrayList<String>();
        List<LibXrayClient.PingResult> all = new ArrayList<LibXrayClient.PingResult>();
        queued(responses, sent).pingInChunks(configs(12), "proxy", "https://example.com", 5, collecting(new ArrayList<Integer>(), all, true));
        assertEquals(3, sent.size());
        assertEquals(12, all.size());
        assertEquals(10L, all.get(0).delay);
        for (int i = 5; i < 10; i++) {
            assertEquals(-1L, all.get(i).delay);
            assertTrue(all.get(i).error.contains("boom"));
        }
        assertEquals(30L, all.get(10).delay);
        assertEquals(31L, all.get(11).delay);
    }


    @Test
    public void pingInChunksMalformedChunkReportsMinusOne() {
        LinkedList<String> responses = new LinkedList<String>(Arrays.asList(
            pingResponse(10, 11, 12, 13, 14),
            "not json",
            pingResponse(30, 31)
        ));
        List<String> sent = new ArrayList<String>();
        List<LibXrayClient.PingResult> all = new ArrayList<LibXrayClient.PingResult>();
        queued(responses, sent).pingInChunks(configs(12), "proxy", "https://example.com", 5, collecting(new ArrayList<Integer>(), all, true));
        assertEquals(12, all.size());
        assertEquals(-1L, all.get(7).delay);
        assertTrue(all.get(7).error != null);
        assertEquals(30L, all.get(10).delay);
    }


    @Test
    public void pingInChunksStopsWhenListenerReturnsFalse() {
        LinkedList<String> responses = new LinkedList<String>(Arrays.asList(pingResponse(10, 11, 12, 13, 14)));
        List<String> sent = new ArrayList<String>();
        queued(responses, sent).pingInChunks(
            configs(12),
            "proxy",
            "https://example.com",
            5,
            collecting(new ArrayList<Integer>(), new ArrayList<LibXrayClient.PingResult>(), false)
        );
        assertEquals(1, sent.size());
    }


    @Test
    public void pingInChunksWithChunkSizeOneMakesOneCallPerConfig() {
        LinkedList<String> responses = new LinkedList<String>(Arrays.asList(
            pingResponse(10),
            pingResponse(20),
            pingResponse(30)
        ));
        List<String> sent = new ArrayList<String>();
        List<Integer> offsets = new ArrayList<Integer>();
        queued(responses, sent).pingInChunks(
            configs(3),
            "proxy",
            "https://example.com",
            5,
            1,
            0L,
            collecting(offsets, new ArrayList<LibXrayClient.PingResult>(), true)
        );
        assertEquals(3, sent.size());
        assertEquals(Arrays.asList(0, 1, 2), offsets);
    }


    @Test
    public void pingInChunksPausesBetweenChunksOnly() {
        LinkedList<String> responses = new LinkedList<String>(Arrays.asList(
            pingResponse(10),
            pingResponse(20),
            pingResponse(30)
        ));
        List<String> sent = new ArrayList<String>();
        long started = System.nanoTime();
        queued(responses, sent).pingInChunks(
            configs(3),
            "proxy",
            "https://example.com",
            5,
            1,
            60L,
            collecting(new ArrayList<Integer>(), new ArrayList<LibXrayClient.PingResult>(), true)
        );
        long elapsedMillis = (System.nanoTime() - started) / 1000000L;
        assertEquals(3, sent.size());
        assertTrue("elapsed " + elapsedMillis, elapsedMillis >= 2 * 60L - 5L);
    }


    @Test
    public void pingInChunksClampsChunkSize() {
        LinkedList<String> responses = new LinkedList<String>(Arrays.asList(
            pingResponse(1, 2, 3, 4, 5),
            pingResponse(6, 7)
        ));
        List<String> sent = new ArrayList<String>();
        queued(responses, sent).pingInChunks(
            configs(7),
            "proxy",
            "https://example.com",
            5,
            50,
            0L,
            collecting(new ArrayList<Integer>(), new ArrayList<LibXrayClient.PingResult>(), true)
        );
        assertEquals(2, sent.size());
        assertEquals(5, sentConfigs(sent.get(0)).size());
        List<String> zeroSent = new ArrayList<String>();
        queued(new LinkedList<String>(Arrays.asList(pingResponse(1), pingResponse(2))), zeroSent).pingInChunks(
            configs(2),
            "proxy",
            "https://example.com",
            5,
            0,
            0L,
            collecting(new ArrayList<Integer>(), new ArrayList<LibXrayClient.PingResult>(), true)
        );
        assertEquals(2, zeroSent.size());
    }


    @Test
    public void pingBatchDetailedRejectsMoreThanFiveWithoutCallingRuntime() throws Exception {
        List<String> sent = new ArrayList<String>();
        try {
            queued(new LinkedList<String>(), sent).pingBatchDetailed(configs(6), "proxy", "https://example.com", 5);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(sent.isEmpty());
        }
    }


    @Test
    public void pingInChunksEmptyListMakesNoCall() {
        List<String> sent = new ArrayList<String>();
        List<Integer> offsets = new ArrayList<Integer>();
        queued(new LinkedList<String>(), sent).pingInChunks(
            configs(0),
            "proxy",
            "https://example.com",
            5,
            collecting(offsets, new ArrayList<LibXrayClient.PingResult>(), true)
        );
        assertTrue(sent.isEmpty());
        assertTrue(offsets.isEmpty());
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
