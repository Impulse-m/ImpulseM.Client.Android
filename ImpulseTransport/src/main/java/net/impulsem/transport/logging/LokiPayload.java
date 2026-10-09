package net.impulsem.transport.logging;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;


/** Renders records as a Loki push body: one stream per (component, level) under the shared labels. */
public final class LokiPayload {

    public static final int MaxStackLength = 16384;

    private static final int MaxCauseDepth = 4;
    private static final long NanosPerMilli = 1000000L;


    private LokiPayload() {
    }


    /**
     * @param labels  the stream labels shared by every record; kept to a minimum because every distinct label value
     *                makes a new Loki stream
     * @param context plain fields written into every line instead, e.g. the app version and the install id
     */
    public static String build(
        List<LogRecord> records,
        Map<String, String> labels,
        Map<String, String> context
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
            entry.add(new JsonPrimitive(line(record, isoFormat, context)));
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


    /**
     * The log line itself, a JSON object so LogQL can filter on it with `| json`. The message and the thread of a record
     * are never written: free text does not leave the device.
     */
    static String line(
        LogRecord record,
        SimpleDateFormat isoFormat,
        Map<String, String> context
    ) {
        JsonObject line = new JsonObject();
        line.addProperty("ts", isoFormat.format(new Date(record.timestampMillis)));
        line.addProperty("level", record.level.label());
        line.addProperty("component", record.component);
        for (Map.Entry<String, String> entry : context.entrySet()) {
            line.addProperty(entry.getKey(), entry.getValue());
        }
        if (record.event != null && LogRedactor.isEnumToken(record.event)) {
            line.addProperty("event", record.event);
        }
        JsonObject fields = admittedFields(record.fields);
        if (fields.size() > 0) {
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


    private static JsonObject admittedFields(Map<String, Object> recordFields) {
        JsonObject fields = new JsonObject();
        for (Map.Entry<String, Object> field : recordFields.entrySet()) {
            JsonElement value = fieldJson(field.getValue());
            if (value != null) {
                fields.add(field.getKey(), value);
            }
        }
        return fields;
    }


    private static JsonElement fieldJson(Object value) {
        if (value instanceof Throwable) {
            return describeError((Throwable) value, 0);
        }
        Object admitted = LogRedactor.admit(value);
        if (admitted == null) {
            return null;
        }
        if (admitted instanceof Boolean) {
            return new JsonPrimitive((Boolean) admitted);
        }
        if (admitted instanceof Number) {
            return new JsonPrimitive((Number) admitted);
        }
        return new JsonPrimitive(String.valueOf(admitted));
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


    /** An exception is its class and its stack frames as class, method and line; its message and toString never ship. */
    private static JsonObject describeError(
        Throwable error,
        int depth
    ) {
        JsonObject described = new JsonObject();
        described.addProperty("type", error.getClass().getName());
        StringBuilder stack = new StringBuilder();
        for (StackTraceElement frame : error.getStackTrace()) {
            if (stack.length() >= MaxStackLength) {
                break;
            }
            stack.append("at ")
                .append(frame.getClassName())
                .append('.')
                .append(frame.getMethodName())
                .append(':')
                .append(frame.getLineNumber())
                .append('\n');
        }
        described.addProperty("stack", truncate(stack.toString(), MaxStackLength));
        Throwable cause = error.getCause();
        if (cause != null && cause != error && depth < MaxCauseDepth) {
            described.add("cause", describeError(cause, depth + 1));
        }
        return described;
    }


    private static String truncate(
        String value,
        int maxLength
    ) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...[truncated]";
    }
}
