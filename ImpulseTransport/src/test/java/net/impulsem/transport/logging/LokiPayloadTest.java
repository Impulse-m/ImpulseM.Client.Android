package net.impulsem.transport.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;


public class LokiPayloadTest {

    private static final long FixedTimestampMillis = 1700000000000L;


    @Test
    public void aDeepStackWithCausesRendersAtMostEightKiBAndKeepsTheOuterTypeAndTopFrame() {
        Throwable error = deepError(4, 500);
        LogRecord record = new LogRecord(FixedTimestampMillis, LogLevel.ERROR, "crash", "CRASHED", null, error, "test", null);

        String line = LokiPayload.line(record, LokiPayload.isoFormat(), context("note", "x"));

        assertNotNull(line);
        assertTrue("line bytes " + utf8Length(line), utf8Length(line) <= LokiPayload.MaxLineBytes);
        assertTrue(line.contains("java.lang.IllegalStateException"));
        assertTrue("the throw site must survive", line.contains("org.example.deeply.nested.package.SomeVeryLongClassName0.method0"));
    }


    @Test
    public void theLimitIsMeasuredInUtf8BytesNotChars() {
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            wide.append((char) 0x0436);
        }
        LogRecord record = new LogRecord(FixedTimestampMillis, LogLevel.ERROR, "crash", "CRASHED", null, deepError(1, 400), "test", null);

        String line = LokiPayload.line(record, LokiPayload.isoFormat(), context("note", wide.toString()));

        assertNotNull(line);
        assertTrue("line bytes " + utf8Length(line), utf8Length(line) <= LokiPayload.MaxLineBytes);
    }


    @Test
    public void aMinimalLineOverTheLimitInBytesButNotInCharsIsNotRendered() {
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            wide.append((char) 0x0436);
        }
        LogRecord record = new LogRecord(FixedTimestampMillis, LogLevel.INFO, "app", "EVENT", null, null, "test", null);

        String line = LokiPayload.line(record, LokiPayload.isoFormat(), context("note", wide.toString()));

        assertNull("5000 chars are 10000 bytes, no degrade step can make that fit", line);
    }


    @Test
    public void aLineUnderTheLimitIsUnchanged() {
        Throwable error = new IllegalStateException();
        error.setStackTrace(new StackTraceElement[] {new StackTraceElement("org.X", "m", "X.java", 5)});
        LogRecord record = new LogRecord(
            FixedTimestampMillis,
            LogLevel.INFO,
            "app",
            "EV",
            null,
            error,
            "test",
            LogRecord.fieldsOf("i", 1)
        );

        String line = LokiPayload.line(record, LokiPayload.isoFormat(), context("app", "1"));

        assertEquals(
            "{\"ts\":\"2023-11-14T22:13:20.000Z\",\"level\":\"info\",\"component\":\"app\",\"app\":\"1\",\"event\":\"EV\","
                + "\"fields\":{\"i\":1},\"error\":{\"type\":\"java.lang.IllegalStateException\",\"stack\":\"at org.X.m:5\\n\"}}",
            line
        );
    }


    private static Map<String, String> context(
        String key,
        String value
    ) {
        Map<String, String> context = new LinkedHashMap<String, String>();
        context.put(key, value);
        return context;
    }


    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }


    private static Throwable deepError(
        int causes,
        int frameCount
    ) {
        Throwable error = null;
        for (int level = 0; level <= causes; level++) {
            Throwable next = error == null ? new IllegalStateException() : new IllegalStateException(error);
            StackTraceElement[] frames = new StackTraceElement[frameCount];
            for (int i = 0; i < frames.length; i++) {
                frames[i] = new StackTraceElement("org.example.deeply.nested.package.SomeVeryLongClassName" + i, "method" + i, "File.java", i);
            }
            next.setStackTrace(frames);
            error = next;
        }
        return error;
    }
}
