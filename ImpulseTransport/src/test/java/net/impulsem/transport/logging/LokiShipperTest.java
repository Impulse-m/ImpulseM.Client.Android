package net.impulsem.transport.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import net.impulsem.transport.realtime.Backoff;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;


public class LokiShipperTest {

    private static final long AwaitSeconds = 10L;

    private MockWebServer server;
    private LokiShipper shipper;


    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("service_name", "ImpulseM.Android");
        labels.put("app_version", "12.10.6");
        labels.put("device_model", "Pixel 8");
        labels.put("install_id", "install-1");
        Callable<OkHttpClient> http = new Callable<OkHttpClient>() {
            @Override
            public OkHttpClient call() {
                return new OkHttpClient();
            }
        };
        shipper = new LokiShipper(
            http,
            LokiShipper.pushUrlFor(server.url("/")),
            labels,
            new Backoff(100L, 200L, 0.0, new Random(1L))
        );
    }


    @After
    public void tearDown() throws Exception {
        shipper.setEnabled(false);
        server.shutdown();
    }


    @Test
    public void aFullBatchIsPushedAtOnceWithStreamLabelsPerComponentAndLevel() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        for (int i = 0; i < LokiShipper.BatchSize; i++) {
            shipper.append(trace(i % 2 == 0 ? "calls" : "send", LogLevel.INFO, "EVENT_" + i, "callId", 5000000000L + i));
        }

        RecordedRequest request = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS);
        assertNotNull("20 records must be pushed without waiting for the 2 s timer", request);
        assertEquals("/loki/api/v1/push", request.getPath());
        JsonArray streams = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject().getAsJsonArray("streams");
        assertEquals(2, streams.size());
        int lines = 0;
        for (JsonElement element : streams) {
            JsonObject stream = element.getAsJsonObject().getAsJsonObject("stream");
            assertEquals("ImpulseM.Android", stream.get("service_name").getAsString());
            assertEquals("info", stream.get("level").getAsString());
            assertEquals("install-1", stream.get("install_id").getAsString());
            assertTrue(stream.has("component"));
            assertTrue(stream.has("app_version"));
            assertTrue(stream.has("device_model"));
            lines += element.getAsJsonObject().getAsJsonArray("values").size();
        }
        assertEquals(LokiShipper.BatchSize, lines);
    }


    @Test
    public void aFewRecordsArePushedByTheFlushTimer() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        shipper.append(trace("push", LogLevel.INFO, "PUSH_RECEIVED", "locKey", "PHONE_CALL_REQUEST"));

        RecordedRequest request = server.takeRequest(LokiShipper.FlushIntervalMillis + AwaitSeconds * 1000L, TimeUnit.MILLISECONDS);
        assertNotNull(request);
        JsonObject line = firstLine(request);
        assertEquals("PUSH_RECEIVED", line.get("event").getAsString());
        assertEquals("PHONE_CALL_REQUEST", line.getAsJsonObject("fields").get("locKey").getAsString());
        assertTrue(line.get("ts").getAsString().endsWith("Z"));
    }


    @Test
    public void aFailedPushIsRequeuedAndDeliveredInOrderOnRetry() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(204));

        shipper.append(trace("sync", LogLevel.INFO, "FIRST", "pts", 1));
        shipper.append(trace("sync", LogLevel.INFO, "SECOND", "pts", 2));
        shipper.flush();

        RecordedRequest failed = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS);
        RecordedRequest retried = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS);
        assertNotNull(failed);
        assertNotNull("a 503 must be retried, not dropped", retried);
        List<String> events = events(retried);
        assertEquals(2, events.size());
        assertEquals("FIRST", events.get(0));
        assertEquals("SECOND", events.get(1));
    }


    @Test
    public void aRejectedBatchIsNotRetriedAndItsLossIsReportedInTheNextPush() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400));
        server.enqueue(new MockResponse().setResponseCode(204));

        shipper.append(trace("sync", LogLevel.INFO, "POISON", "pts", 1));
        assertTrue(shipper.flushAndWait(AwaitSeconds * 1000L));
        assertNotNull(server.takeRequest(AwaitSeconds, TimeUnit.SECONDS));

        shipper.append(trace("sync", LogLevel.INFO, "NEXT", "pts", 2));
        assertTrue(shipper.flushAndWait(AwaitSeconds * 1000L));

        List<String> events = events(server.takeRequest(AwaitSeconds, TimeUnit.SECONDS));
        assertEquals("REMOTE_LOG_RECORDS_LOST", events.get(0));
        assertEquals("NEXT", events.get(1));
        assertFalse(events.contains("POISON"));
    }


    @Test
    public void theBufferIsBoundedWhileTheEndpointIsDown() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));

        for (int i = 0; i < LokiShipper.MaxBufferedRecords + 50; i++) {
            shipper.append(trace("app", LogLevel.DEBUG, "E" + i, "i", i));
        }

        assertTrue(shipper.bufferedCount() <= LokiShipper.MaxBufferedRecords);
    }


    @Test
    public void personalDataInAFreeTextLineNeverReachesTheWire() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        shipper.append(new LogRecord(
            System.currentTimeMillis(),
            LogLevel.ERROR,
            "push",
            null,
            "error in loc_key = MESSAGE_TEXT json {\"loc_args\":[\"Alice\",\"meet at noon\"]} phone=+79991234567",
            new IllegalStateException("token: abcdefabcdefabcdefabcdef0123456789"),
            "main",
            null
        ));
        assertTrue(shipper.flushAndWait(AwaitSeconds * 1000L));

        String body = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS).getBody().readUtf8();
        assertFalse(body, body.contains("Alice"));
        assertFalse(body, body.contains("meet at noon"));
        assertFalse(body, body.contains("9991234567"));
        assertFalse(body, body.contains("abcdefabcdefabcdefabcdef0123456789"));
        assertTrue(body, body.contains("MESSAGE_TEXT"));
        assertTrue(body, body.contains("IllegalStateException"));
    }


    @Test
    public void nothingIsQueuedWhileDisabled() {
        shipper.setEnabled(false);

        shipper.append(trace("app", LogLevel.INFO, "IGNORED", "i", 1));

        assertEquals(0, shipper.bufferedCount());
    }


    private static LogRecord trace(
        String component,
        LogLevel level,
        String event,
        Object... fields
    ) {
        return new LogRecord(System.currentTimeMillis(), level, component, event, null, null, "test", LogRecord.fieldsOf(fields));
    }


    private static JsonObject firstLine(RecordedRequest request) {
        JsonArray streams = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject().getAsJsonArray("streams");
        String line = streams.get(0).getAsJsonObject().getAsJsonArray("values").get(0).getAsJsonArray().get(1).getAsString();
        return JsonParser.parseString(line).getAsJsonObject();
    }


    private static List<String> events(RecordedRequest request) {
        List<String> events = new ArrayList<String>();
        JsonArray streams = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject().getAsJsonArray("streams");
        for (JsonElement stream : streams) {
            for (JsonElement value : stream.getAsJsonObject().getAsJsonArray("values")) {
                String line = value.getAsJsonArray().get(1).getAsString();
                events.add(JsonParser.parseString(line).getAsJsonObject().get("event").getAsString());
            }
        }
        return events;
    }
}
