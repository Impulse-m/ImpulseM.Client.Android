package net.impulsem.transport.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
    private static final long FixedTimestampMillis = 1700000000000L;

    private MockWebServer server;
    private LokiShipper shipper;
    private final List<LokiShipper> created = new ArrayList<LokiShipper>();


    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        shipper = create(realClock(), noToken(), LokiShipper.MaxBodyBytes);
    }


    private LokiShipper create(
        LokiShipper.Clock clock,
        LokiShipper.TokenSource tokens,
        int maxBodyBytes
    ) {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("service_name", "ImpulseM.Android");
        Map<String, String> context = new LinkedHashMap<String, String>();
        context.put("app", "12.10.6");
        context.put("device", "Pixel 8");
        context.put("install", "install-1");
        Callable<OkHttpClient> http = new Callable<OkHttpClient>() {
            @Override
            public OkHttpClient call() {
                return new OkHttpClient();
            }
        };
        LokiShipper made = new LokiShipper(
            http,
            LokiShipper.pushUrlFor(server.url("/")),
            labels,
            context,
            tokens,
            new Backoff(100L, 200L, 0.0, new Random(1L)),
            clock,
            maxBodyBytes
        );
        made.setEnabled(true);
        created.add(made);
        return made;
    }


    @After
    public void tearDown() throws Exception {
        for (LokiShipper made : created) {
            made.setEnabled(false);
        }
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
            assertEquals("a user id, install id or version must never be a label", 3, stream.size());
            assertTrue(stream.has("component"));
            assertFalse(stream.has("install_id"));
            assertFalse(stream.has("app_version"));
            assertFalse(stream.has("device_model"));
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
        assertFalse("a free-text message is never shipped: " + body, body.contains("error in loc_key"));
        assertTrue(body, body.contains("IllegalStateException"));
    }


    @Test
    public void aFreeTextDebugLineIsNotShippedAtAll() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        shipper.append(freeText(LogLevel.DEBUG, "Dinner tomorrow with Bob at Luigi's"));
        shipper.append(trace("app", LogLevel.INFO, "MARKER", "i", 1));
        assertTrue(shipper.flushAndWait(AwaitSeconds * 1000L));

        String body = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS).getBody().readUtf8();
        assertFalse(body, body.contains("Dinner tomorrow"));
        assertFalse(body, body.contains("Luigi"));
        assertTrue(body, body.contains("MARKER"));
    }


    @Test
    public void aLoginCodeInAFreeTextLineNeverReachesTheWire() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        shipper.append(freeText(LogLevel.DEBUG, "login code 48213 from 777000"));
        shipper.append(trace("app", LogLevel.INFO, "MARKER", "i", 1));
        assertTrue(shipper.flushAndWait(AwaitSeconds * 1000L));

        String body = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS).getBody().readUtf8();
        assertFalse(body, body.contains("48213"));
    }


    @Test
    public void aDottedPhoneNumberInAFreeTextLineNeverReachesTheWire() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        shipper.append(freeText(LogLevel.DEBUG, "call +7.999.123.45.67 back"));
        shipper.append(trace("app", LogLevel.INFO, "MARKER", "i", 1));
        assertTrue(shipper.flushAndWait(AwaitSeconds * 1000L));

        String body = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS).getBody().readUtf8();
        assertFalse(body, body.contains("999"));
        assertFalse(body, body.contains("123"));
    }


    @Test
    public void aTlUserDumpWithUsernameAndPhoneNeverReachesTheWire() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        shipper.append(freeText(
            LogLevel.DEBUG,
            "req -> TL_user {id=7, username=alice_99, first_name=Alice Smith, phone=79991234567}"
        ));
        shipper.append(trace("app", LogLevel.INFO, "MARKER", "i", 1));
        assertTrue(shipper.flushAndWait(AwaitSeconds * 1000L));

        String body = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS).getBody().readUtf8();
        assertFalse(body, body.contains("alice_99"));
        assertFalse(body, body.contains("Alice"));
        assertFalse(body, body.contains("Smith"));
        assertFalse(body, body.contains("79991234567"));
    }


    @Test
    public void anExceptionShipsItsClassAndFramesButNotItsMessage() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        IllegalStateException failure = new IllegalStateException("contact +7.999.123.45.67");
        shipper.append(new LogRecord(FixedTimestampMillis, LogLevel.ERROR, "push", null, null, failure, "test", null));
        assertTrue(shipper.flushAndWait(AwaitSeconds * 1000L));

        String body = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS).getBody().readUtf8();
        assertFalse(body, body.contains("999.123"));
        JsonObject error = parseFirstLine(body).getAsJsonObject("error");
        assertEquals(IllegalStateException.class.getName(), error.get("type").getAsString());
        assertFalse("the exception message is never shipped: " + body, error.has("message"));
        assertTrue(body, error.get("stack").getAsString().contains("LokiShipperTest"));
    }


    @Test
    public void aStructuredEventWithAnIntegerIdStillShips() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        shipper.append(trace("calls", LogLevel.INFO, "PHONE_CALL_UPDATE", "callId", 5000000001L, "state", "PHONE_CALL_REQUEST"));
        assertTrue(shipper.flushAndWait(AwaitSeconds * 1000L));

        String body = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS).getBody().readUtf8();
        JsonObject line = parseFirstLine(body);
        assertEquals("PHONE_CALL_UPDATE", line.get("event").getAsString());
        assertEquals(5000000001L, line.getAsJsonObject("fields").get("callId").getAsLong());
    }


    @Test
    public void aStructuredStringThatIsNotAKnownEnumIsDropped() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        shipper.append(trace("sync", LogLevel.INFO, "GET_DIFFERENCE_END", "during", "Alice Smith"));
        assertTrue(shipper.flushAndWait(AwaitSeconds * 1000L));

        String body = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS).getBody().readUtf8();
        assertFalse(body, body.contains("Alice"));
        assertTrue(body, body.contains("GET_DIFFERENCE_END"));
    }


    @Test
    public void aKnownEnumStringStillShips() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        shipper.append(trace("sync", LogLevel.INFO, "GET_DIFFERENCE_START", "during", "token_fetch"));
        assertTrue(shipper.flushAndWait(AwaitSeconds * 1000L));

        JsonObject line = parseFirstLine(server.takeRequest(AwaitSeconds, TimeUnit.SECONDS).getBody().readUtf8());
        assertEquals("token_fetch", line.getAsJsonObject("fields").get("during").getAsString());
    }


    @Test
    public void aRecordFromBeforeAnOptOutNeverShipsEvenAfterReEnabling() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setHeadersDelay(800L, TimeUnit.MILLISECONDS));
        server.enqueue(new MockResponse().setResponseCode(204));

        shipper.append(trace("app", LogLevel.INFO, "BEFORE_OPT_OUT", "i", 1));
        shipper.flush();
        assertNotNull(server.takeRequest(AwaitSeconds, TimeUnit.SECONDS));
        shipper.setEnabled(false);
        shipper.setEnabled(true);
        Thread.sleep(1500L);
        shipper.flush();

        assertEquals("the failed batch must not come back after the opt-out", 0, shipper.bufferedCount());
        assertNull(server.takeRequest(700L, TimeUnit.MILLISECONDS));
    }


    @Test
    public void a401OnTheAnonymousLaneDropsTheBatchWithoutARetryLoop() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(204));

        shipper.append(trace("app", LogLevel.INFO, "ANONYMOUS", "i", 1));
        assertTrue(shipper.flushAndWait(AwaitSeconds * 1000L));

        RecordedRequest request = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS);
        assertNull(request.getHeader("Authorization"));
        assertNull("a rejected anonymous batch is not retried", server.takeRequest(1200L, TimeUnit.MILLISECONDS));
        assertEquals(1, server.getRequestCount());
        assertEquals(0, shipper.bufferedCount());
    }


    @Test
    public void aNewShipperIsOffUntilItIsEnabled() throws Exception {
        LokiShipper fresh = new LokiShipper(
            new Callable<OkHttpClient>() {
                @Override
                public OkHttpClient call() {
                    return new OkHttpClient();
                }
            },
            LokiShipper.pushUrlFor(server.url("/")),
            new LinkedHashMap<String, String>(),
            new LinkedHashMap<String, String>(),
            noToken()
        );

        assertFalse(fresh.isEnabled());
        fresh.append(trace("app", LogLevel.INFO, "NOT_YET", "i", 1));
        assertEquals(0, fresh.bufferedCount());
    }


    @Test
    public void nothingIsQueuedWhileDisabled() {
        shipper.setEnabled(false);

        shipper.append(trace("app", LogLevel.INFO, "IGNORED", "i", 1));

        assertEquals(0, shipper.bufferedCount());
    }


    @Test
    public void appDeviceAndInstallTravelInTheLineNotInTheLabels() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        shipper.append(trace("app", LogLevel.INFO, "MARKER", "i", 1));
        assertTrue(shipper.flushAndWait(AwaitSeconds * 1000L));

        JsonObject line = parseFirstLine(server.takeRequest(AwaitSeconds, TimeUnit.SECONDS).getBody().readUtf8());
        assertEquals("12.10.6", line.get("app").getAsString());
        assertEquals("Pixel 8", line.get("device").getAsString());
        assertEquals("install-1", line.get("install").getAsString());
    }


    @Test
    public void theBearerTokenIsSentWhenASessionExistsAndNotBefore() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        server.enqueue(new MockResponse().setResponseCode(204));
        final AtomicReference<String> token = new AtomicReference<String>(null);
        LokiShipper authed = create(realClock(), tokenOf(token), LokiShipper.MaxBodyBytes);

        authed.append(trace("app", LogLevel.INFO, "BEFORE_LOGIN", "i", 1));
        assertTrue(authed.flushAndWait(AwaitSeconds * 1000L));
        token.set("access-1");
        authed.append(trace("app", LogLevel.INFO, "AFTER_LOGIN", "i", 2));
        assertTrue(authed.flushAndWait(AwaitSeconds * 1000L));

        assertNull(server.takeRequest(AwaitSeconds, TimeUnit.SECONDS).getHeader("Authorization"));
        assertEquals("Bearer access-1", server.takeRequest(AwaitSeconds, TimeUnit.SECONDS).getHeader("Authorization"));
    }


    @Test
    public void a401DropsTheBatchAndTheRejectedTokenIsNotSentAgainUntilItChanges() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(204));
        server.enqueue(new MockResponse().setResponseCode(204));
        final AtomicReference<String> token = new AtomicReference<String>("stale");
        LokiShipper authed = create(realClock(), tokenOf(token), LokiShipper.MaxBodyBytes);

        authed.append(trace("app", LogLevel.INFO, "DROPPED", "i", 1));
        assertTrue("a 401 drops the batch, it is not retried", authed.flushAndWait(AwaitSeconds * 1000L));
        authed.append(trace("app", LogLevel.INFO, "ANONYMOUS", "i", 2));
        assertTrue(authed.flushAndWait(AwaitSeconds * 1000L));
        token.set("fresh");
        authed.append(trace("app", LogLevel.INFO, "FRESH", "i", 3));
        assertTrue(authed.flushAndWait(AwaitSeconds * 1000L));

        RecordedRequest rejected = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS);
        RecordedRequest anonymous = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS);
        RecordedRequest fresh = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS);
        assertEquals("Bearer stale", rejected.getHeader("Authorization"));
        assertNull(anonymous.getHeader("Authorization"));
        assertFalse(events(anonymous).contains("DROPPED"));
        assertEquals("Bearer fresh", fresh.getHeader("Authorization"));
    }


    @Test
    public void a429WithRetryAfterSecondsWaitsThatLong() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "30"));
        server.enqueue(new MockResponse().setResponseCode(204));
        TestClock clock = new TestClock();
        LokiShipper limited = create(clock, noToken(), LokiShipper.MaxBodyBytes);

        limited.append(trace("app", LogLevel.INFO, "LIMITED", "i", 1));
        limited.flush();
        assertNotNull(server.takeRequest(AwaitSeconds, TimeUnit.SECONDS));
        awaitBuffered(limited, 1);

        clock.advanceSeconds(29L);
        limited.flush();
        assertNull("must wait the full Retry-After", server.takeRequest(700L, TimeUnit.MILLISECONDS));
        clock.advanceSeconds(2L);
        limited.flush();
        RecordedRequest retried = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS);
        assertNotNull(retried);
        assertEquals("LIMITED", events(retried).get(0));
    }


    @Test
    public void a429WithRetryAfterHttpDateWaitsUntilThatDate() throws Exception {
        SimpleDateFormat httpDate = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        httpDate.setTimeZone(TimeZone.getTimeZone("GMT"));
        String date = httpDate.format(new Date(System.currentTimeMillis() + 30000L));
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", date));
        server.enqueue(new MockResponse().setResponseCode(204));
        TestClock clock = new TestClock();
        LokiShipper limited = create(clock, noToken(), LokiShipper.MaxBodyBytes);

        limited.append(trace("app", LogLevel.INFO, "LIMITED", "i", 1));
        limited.flush();
        assertNotNull(server.takeRequest(AwaitSeconds, TimeUnit.SECONDS));
        awaitBuffered(limited, 1);

        clock.advanceSeconds(15L);
        limited.flush();
        assertNull(server.takeRequest(700L, TimeUnit.MILLISECONDS));
        clock.advanceSeconds(25L);
        limited.flush();
        assertNotNull(server.takeRequest(AwaitSeconds, TimeUnit.SECONDS));
    }


    @Test
    public void a429RetryAfterIsCappedAtTenMinutes() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "86400"));
        server.enqueue(new MockResponse().setResponseCode(204));
        TestClock clock = new TestClock();
        LokiShipper limited = create(clock, noToken(), LokiShipper.MaxBodyBytes);

        limited.append(trace("app", LogLevel.INFO, "LIMITED", "i", 1));
        limited.flush();
        assertNotNull(server.takeRequest(AwaitSeconds, TimeUnit.SECONDS));
        awaitBuffered(limited, 1);

        clock.advanceSeconds(599L);
        limited.flush();
        assertNull(server.takeRequest(700L, TimeUnit.MILLISECONDS));
        clock.advanceSeconds(2L);
        limited.flush();
        assertNotNull("the wait is capped at 10 minutes", server.takeRequest(AwaitSeconds, TimeUnit.SECONDS));
    }


    @Test
    public void a429WithoutRetryAfterUsesTheBackoff() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429));
        server.enqueue(new MockResponse().setResponseCode(204));
        TestClock clock = new TestClock();
        LokiShipper limited = create(clock, noToken(), LokiShipper.MaxBodyBytes);

        limited.append(trace("app", LogLevel.INFO, "LIMITED", "i", 1));
        limited.flush();
        assertNotNull(server.takeRequest(AwaitSeconds, TimeUnit.SECONDS));
        awaitBuffered(limited, 1);

        clock.advanceSeconds(1L);
        limited.flush();
        assertNotNull(server.takeRequest(AwaitSeconds, TimeUnit.SECONDS));
    }


    @Test
    public void a413DropsTheBatchAndCountsItAsRejected() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(413));
        server.enqueue(new MockResponse().setResponseCode(204));

        shipper.append(trace("app", LogLevel.INFO, "TOO_BIG", "i", 1));
        assertTrue("a 413 is not retried", shipper.flushAndWait(AwaitSeconds * 1000L));
        shipper.append(trace("app", LogLevel.INFO, "NEXT", "i", 2));
        assertTrue(shipper.flushAndWait(AwaitSeconds * 1000L));

        assertNotNull(server.takeRequest(AwaitSeconds, TimeUnit.SECONDS));
        RecordedRequest next = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS);
        assertEquals("REMOTE_LOG_RECORDS_LOST", events(next).get(0));
        assertFalse(events(next).contains("TOO_BIG"));
    }


    @Test
    public void everyRequestStaysUnderTheBodyLimitAndNoRecordIsLost() throws Exception {
        for (int i = 0; i < 40; i++) {
            server.enqueue(new MockResponse().setResponseCode(204));
        }
        int records = 20;
        for (int i = 0; i < records; i++) {
            shipper.append(new LogRecord(FixedTimestampMillis, LogLevel.ERROR, "crash", "BIG_" + i, null, bigError(), "test", null));
        }
        assertTrue(shipper.flushAndWait(AwaitSeconds * 1000L));
        assertTrue(shipper.flushAndWait(AwaitSeconds * 1000L));

        int delivered = 0;
        int requests = server.getRequestCount();
        for (int i = 0; i < requests; i++) {
            RecordedRequest request = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS);
            assertTrue("body " + request.getBodySize(), request.getBodySize() <= LokiShipper.MaxBodyBytes);
            delivered += events(request).size();
        }
        assertEquals(records, delivered);
    }


    @Test
    public void aSingleRecordOverTheLimitIsDroppedAndCountedAsRejected() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        server.enqueue(new MockResponse().setResponseCode(204));
        LokiShipper small = create(realClock(), noToken(), 20000);

        small.append(new LogRecord(FixedTimestampMillis, LogLevel.ERROR, "crash", "HUGE", null, bigError(), "test", null));
        assertTrue(small.flushAndWait(AwaitSeconds * 1000L));
        small.append(trace("app", LogLevel.INFO, "NEXT", "i", 2));
        assertTrue(small.flushAndWait(AwaitSeconds * 1000L));

        RecordedRequest only = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS);
        List<String> events = events(only);
        assertEquals("REMOTE_LOG_RECORDS_LOST", events.get(0));
        assertEquals("NEXT", events.get(1));
        assertFalse(events.contains("HUGE"));
        JsonObject lost = nthLine(only, 0).getAsJsonObject("fields");
        assertEquals(1, lost.get("rejectedByServer").getAsInt());
    }


    @Test
    public void theLossReportSurvivesAFailedPushAndShipsWithTheNextOne() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(204));
        TestClock clock = new TestClock();
        LokiShipper lossy = create(clock, noToken(), LokiShipper.MaxBodyBytes);

        lossy.append(trace("app", LogLevel.INFO, "SEED", "i", 0));
        lossy.flush();
        assertNotNull(server.takeRequest(AwaitSeconds, TimeUnit.SECONDS));
        awaitBuffered(lossy, 1);
        int total = LokiShipper.MaxBufferedRecords + 50;
        for (int i = 0; i < total; i++) {
            lossy.append(trace("app", LogLevel.INFO, "E" + i, "i", i));
        }
        int dropped = 1 + total - LokiShipper.MaxBufferedRecords;

        clock.advanceSeconds(3600L);
        lossy.flush();
        assertNotNull("the push carrying the loss report fails", server.takeRequest(AwaitSeconds, TimeUnit.SECONDS));
        awaitBuffered(lossy, LokiShipper.MaxBufferedRecords);
        clock.advanceSeconds(3600L);
        lossy.flush();
        RecordedRequest delivered = server.takeRequest(AwaitSeconds, TimeUnit.SECONDS);

        assertEquals("REMOTE_LOG_RECORDS_LOST", events(delivered).get(0));
        assertEquals(dropped, nthLine(delivered, 0).getAsJsonObject("fields").get("droppedBufferFull").getAsInt());
    }


    @Test
    public void aForcedFlushDoesNotHitTheEndpointWhileARetryIsPending() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(204));
        TestClock clock = new TestClock();
        LokiShipper failing = create(clock, noToken(), LokiShipper.MaxBodyBytes);

        failing.append(trace("app", LogLevel.INFO, "RETRY", "i", 1));
        failing.flush();
        assertNotNull(server.takeRequest(AwaitSeconds, TimeUnit.SECONDS));
        awaitBuffered(failing, 1);

        failing.flush();
        failing.flush();
        assertNull("flush() must wait for the backoff", server.takeRequest(700L, TimeUnit.MILLISECONDS));
        clock.advanceSeconds(1L);
        failing.flush();
        assertNotNull(server.takeRequest(AwaitSeconds, TimeUnit.SECONDS));
    }


    @Test
    public void aPushFailingAfterTheSwitchWasTurnedOffLeavesNothingQueued() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setHeadersDelay(600L, TimeUnit.MILLISECONDS));

        shipper.append(trace("app", LogLevel.INFO, "IN_FLIGHT", "i", 1));
        shipper.flush();
        assertNotNull(server.takeRequest(AwaitSeconds, TimeUnit.SECONDS));
        shipper.setEnabled(false);
        Thread.sleep(1500L);

        assertEquals(0, shipper.bufferedCount());
    }


    @Test
    public void turningTheSwitchOffClearsTheQueueSoNothingShipsOnReEnable() throws Exception {
        shipper.append(trace("app", LogLevel.INFO, "QUEUED", "i", 1));
        shipper.setEnabled(false);
        shipper.setEnabled(true);
        shipper.flush();

        assertNull(server.takeRequest(700L, TimeUnit.MILLISECONDS));
        assertEquals(0, shipper.bufferedCount());
    }


    private static void awaitBuffered(
        LokiShipper target,
        int count
    ) throws InterruptedException {
        long deadline = System.currentTimeMillis() + AwaitSeconds * 1000L;
        while (target.bufferedCount() != count && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
        }
        assertEquals(count, target.bufferedCount());
    }


    private static LokiShipper.Clock realClock() {
        return new LokiShipper.Clock() {
            @Override
            public long nanoTime() {
                return System.nanoTime();
            }
        };
    }


    private static LokiShipper.TokenSource noToken() {
        return new LokiShipper.TokenSource() {
            @Override
            public String bearer() {
                return null;
            }
        };
    }


    private static LokiShipper.TokenSource tokenOf(final AtomicReference<String> token) {
        return new LokiShipper.TokenSource() {
            @Override
            public String bearer() {
                return token.get();
            }
        };
    }


    /** Time that moves only when the test says so; the shipper's schedule is woken with flush() after an advance. */
    private static final class TestClock implements LokiShipper.Clock {

        private final AtomicLong nanos = new AtomicLong(1000L);


        @Override
        public long nanoTime() {
            return nanos.get();
        }


        void advanceSeconds(long seconds) {
            nanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
        }
    }


    /** An exception whose rendering is far over 64 KiB: five levels of cause, each with a full-length stack. */
    private static Throwable bigError() {
        Throwable error = null;
        for (int level = 0; level < 5; level++) {
            Throwable next = error == null ? new IllegalStateException() : new IllegalStateException(error);
            StackTraceElement[] frames = new StackTraceElement[400];
            for (int i = 0; i < frames.length; i++) {
                frames[i] = new StackTraceElement("org.example.deeply.nested.package.SomeVeryLongClassName" + i, "method" + i, "File.java", i);
            }
            next.setStackTrace(frames);
            error = next;
        }
        return error;
    }


    private static JsonObject nthLine(
        RecordedRequest request,
        int index
    ) {
        JsonArray streams = JsonParser.parseString(request.getBody().clone().readUtf8()).getAsJsonObject().getAsJsonArray("streams");
        List<JsonObject> lines = new ArrayList<JsonObject>();
        for (JsonElement stream : streams) {
            for (JsonElement value : stream.getAsJsonObject().getAsJsonArray("values")) {
                lines.add(JsonParser.parseString(value.getAsJsonArray().get(1).getAsString()).getAsJsonObject());
            }
        }
        return lines.get(index);
    }


    private static LogRecord trace(
        String component,
        LogLevel level,
        String event,
        Object... fields
    ) {
        return new LogRecord(System.currentTimeMillis(), level, component, event, null, null, "test", LogRecord.fieldsOf(fields));
    }


    private static LogRecord freeText(
        LogLevel level,
        String message
    ) {
        return new LogRecord(FixedTimestampMillis, level, "app", null, message, null, "test", null);
    }


    private static JsonObject parseFirstLine(String body) {
        JsonArray streams = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("streams");
        String line = streams.get(0).getAsJsonObject().getAsJsonArray("values").get(0).getAsJsonArray().get(1).getAsString();
        return JsonParser.parseString(line).getAsJsonObject();
    }


    private static JsonObject firstLine(RecordedRequest request) {
        JsonArray streams = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject().getAsJsonArray("streams");
        String line = streams.get(0).getAsJsonObject().getAsJsonArray("values").get(0).getAsJsonArray().get(1).getAsString();
        return JsonParser.parseString(line).getAsJsonObject();
    }


    private static List<String> events(RecordedRequest request) {
        List<String> events = new ArrayList<String>();
        JsonArray streams = JsonParser.parseString(request.getBody().clone().readUtf8()).getAsJsonObject().getAsJsonArray("streams");
        for (JsonElement stream : streams) {
            for (JsonElement value : stream.getAsJsonObject().getAsJsonArray("values")) {
                String line = value.getAsJsonArray().get(1).getAsString();
                events.add(JsonParser.parseString(line).getAsJsonObject().get("event").getAsString());
            }
        }
        return events;
    }
}
