package net.impulsem.transport.logging;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;


/** One diagnostic as captured on the calling thread; redaction and serialization happen later, on the shipper thread. */
public final class LogRecord {

    public final long timestampMillis;
    public final LogLevel level;
    public final String component;
    public final String event;
    public final String message;
    public final Throwable error;
    public final String thread;
    public final Map<String, Object> fields;


    public LogRecord(
        long timestampMillis,
        LogLevel level,
        String component,
        String event,
        String message,
        Throwable error,
        String thread,
        Map<String, Object> fields
    ) {
        this.timestampMillis = timestampMillis;
        this.level = level;
        this.component = component;
        this.event = event;
        this.message = message;
        this.error = error;
        this.thread = thread;
        this.fields = fields == null || fields.isEmpty()
            ? Collections.<String, Object>emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(fields));
    }


    /** Builds the field map of a trace from alternating key and value arguments. */
    public static Map<String, Object> fieldsOf(Object... keysAndValues) {
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        if (keysAndValues == null) {
            return fields;
        }
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            fields.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        if (keysAndValues.length % 2 != 0) {
            fields.put("unpairedArgument", keysAndValues[keysAndValues.length - 1]);
        }
        return fields;
    }
}
