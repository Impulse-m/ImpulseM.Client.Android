package net.impulsem.transport.logging;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;


/** Renders redacted records as a Loki push body: one stream per (component, level) under the shared labels. */
public final class LokiPayload {

    public static final int MaxMessageLength = 4096;
    public static final int MaxStackLength = 16384;

    private static final int MaxCauseDepth = 4;
    private static final long NanosPerMilli = 1000000L;


    private LokiPayload() {
    }


    public static String build(
        List<LogRecord> records,
        Map<String, String> labels
    ) {
        Map<String, JsonArray> valuesByStream = new LinkedHashMap<String, JsonArray>();
        Map<String, JsonObject> labelsByStream = new LinkedHashMap<String, JsonObject>();
        SimpleDateFormat isoFormat = isoFormat();
        for (LogRecord record : records) {
            String streamKey = record.component + "\n" + record.level.label();
            JsonArray values = valuesByStream.get(streamKey);
            if (values == null) {
                values = new JsonArray();
                valuesByStream.put(streamKey, values);
                labelsByStream.put(streamKey, streamLabels(labels, record));
            }
            JsonArray entry = new JsonArray();
            entry.add(new JsonPrimitive(String.valueOf(record.timestampMillis * NanosPerMilli)));
            entry.add(new JsonPrimitive(line(record, isoFormat)));
            values.add(entry);
        }
        JsonArray streams = new JsonArray();
        for (Map.Entry<String, JsonArray> stream : valuesByStream.entrySet()) {
            JsonObject item = new JsonObject();
            item.add("stream", labelsByStream.get(stream.getKey()));
            item.add("values", stream.getValue());
            streams.add(item);
        }
        JsonObject body = new JsonObject();
        body.add("streams", streams);
        return body.toString();
    }


    /** The log line itself: a JSON object, so LogQL can filter on it with `| json`. */
    static String line(
        LogRecord record,
        SimpleDateFormat isoFormat
    ) {
        JsonObject line = new JsonObject();
        line.addProperty("ts", isoFormat.format(new Date(record.timestampMillis)));
        line.addProperty("level", record.level.label());
        line.addProperty("component", record.component);
        if (record.event != null) {
            line.addProperty("event", record.event);
        }
        if (record.message != null) {
            line.addProperty("message", truncate(LogRedactor.sanitizeText(record.message), MaxMessageLength));
        }
        if (record.thread != null) {
            line.addProperty("thread", record.thread);
        }
        if (!record.fields.isEmpty()) {
            JsonObject fields = new JsonObject();
            for (Map.Entry<String, Object> field : record.fields.entrySet()) {
                fields.add(field.getKey(), toJson(LogRedactor.sanitizeField(field.getKey(), field.getValue())));
            }
            line.add("fields", fields);
        }
        if (record.error != null) {
            line.add("error", describeError(record.error, 0));
        }
        return line.toString();
    }


    static SimpleDateFormat isoFormat() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format;
    }


    private static JsonObject streamLabels(
        Map<String, String> labels,
        LogRecord record
    ) {
        JsonObject stream = new JsonObject();
        for (Map.Entry<String, String> label : labels.entrySet()) {
            stream.addProperty(label.getKey(), label.getValue());
        }
        stream.addProperty("component", record.component);
        stream.addProperty("level", record.level.label());
        return stream;
    }


    private static JsonObject describeError(
        Throwable error,
        int depth
    ) {
        JsonObject described = new JsonObject();
        described.addProperty("type", error.getClass().getName());
        String message = error.getMessage();
        if (message != null) {
            described.addProperty("message", truncate(LogRedactor.sanitizeText(message), MaxMessageLength));
        }
        StringBuilder stack = new StringBuilder();
        for (StackTraceElement frame : error.getStackTrace()) {
            if (stack.length() >= MaxStackLength) {
                break;
            }
            stack.append("at ").append(frame).append('\n');
        }
        described.addProperty("stack", truncate(LogRedactor.sanitizeText(stack.toString()), MaxStackLength));
        Throwable cause = error.getCause();
        if (cause != null && cause != error && depth < MaxCauseDepth) {
            described.add("cause", describeError(cause, depth + 1));
        }
        return described;
    }


    private static JsonPrimitive toJson(Object value) {
        if (value == null) {
            return new JsonPrimitive("null");
        }
        if (value instanceof Boolean) {
            return new JsonPrimitive((Boolean) value);
        }
        if (value instanceof Number) {
            return new JsonPrimitive((Number) value);
        }
        return new JsonPrimitive(String.valueOf(value));
    }


    private static String truncate(
        String value,
        int maxLength
    ) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...[truncated]";
    }
}
