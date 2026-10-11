package net.impulsem.transport.logging;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
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
    public static final int MaxLineBytes = 8192;

    private static final int MaxCauseDepth = 4;
    private static final long NanosPerMilli = 1000000L;


    private enum StackMode {
        Unlimited,
        Limited,
        None
    }


    /** How much stack text a line may carry; one budget is shared by the error and its causes, outermost first. */
    private static final class StackBudget {

        final StackMode mode;
        int remaining;


        private StackBudget(
            StackMode mode,
            int remaining
        ) {
            this.mode = mode;
            this.remaining = remaining;
        }


        static StackBudget unlimited() {
            return new StackBudget(StackMode.Unlimited, 0);
        }


        static StackBudget limited(int chars) {
            return new StackBudget(StackMode.Limited, chars);
        }


        static StackBudget none() {
            return new StackBudget(StackMode.None, 0);
        }
    }


    private LokiPayload() {
    }


    /**
     * @param labels  the stream labels shared by every record; kept to a minimum because every distinct label value
     *                makes a new Loki stream
     * @param context plain fields written into every line instead, e.g. the app version and the install id
     * @param unfit   receives the records whose line cannot be made to fit {@link #MaxLineBytes} even in its minimal
     *                form; they are left out of the body
     */
    public static String build(
        List<LogRecord> records,
        Map<String, String> labels,
        Map<String, String> context,
        List<LogRecord> unfit
    ) {
        Map<String, JsonArray> valuesByStream = new LinkedHashMap<String, JsonArray>();
        Map<String, JsonObject> labelsByStream = new LinkedHashMap<String, JsonObject>();
        SimpleDateFormat isoFormat = isoFormat();
        for (LogRecord record : records) {
            String line = line(record, isoFormat, context);
            if (line == null) {
                unfit.add(record);
                continue;
            }
            String streamKey = record.component + "\n" + record.level.label();
            JsonArray values = valuesByStream.get(streamKey);
            if (values == null) {
                values = new JsonArray();
                valuesByStream.put(streamKey, values);
                labelsByStream.put(streamKey, streamLabels(labels, record));
            }
            JsonArray entry = new JsonArray();
            entry.add(new JsonPrimitive(String.valueOf(record.timestampMillis * NanosPerMilli)));
            entry.add(new JsonPrimitive(line));
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
     *
     * <p>A line over {@link #MaxLineBytes} UTF-8 bytes is degraded until it fits: first the stacks are cut from the
     * bottom under one budget shared by the error and its causes (the outermost first), then the stacks are dropped,
     * then the fields.
     *
     * @return the line, or null when even the minimal form does not fit
     */
    static String line(
        LogRecord record,
        SimpleDateFormat isoFormat,
        Map<String, String> context
    ) {
        String full = render(record, isoFormat, context, true, StackBudget.unlimited());
        if (fits(full)) {
            return full;
        }
        int low = 0;
        int high = MaxStackLength * (MaxCauseDepth + 1);
        String best = null;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            String candidate = render(record, isoFormat, context, true, StackBudget.limited(middle));
            if (fits(candidate)) {
                best = candidate;
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        if (best != null) {
            return best;
        }
        String withoutStacks = render(record, isoFormat, context, true, StackBudget.none());
        if (fits(withoutStacks)) {
            return withoutStacks;
        }
        String minimal = render(record, isoFormat, context, false, StackBudget.none());
        return fits(minimal) ? minimal : null;
    }


    private static boolean fits(String line) {
        return line.getBytes(StandardCharsets.UTF_8).length <= MaxLineBytes;
    }


    private static String render(
        LogRecord record,
        SimpleDateFormat isoFormat,
        Map<String, String> context,
        boolean withFields,
        StackBudget budget
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
        JsonObject error = record.error != null ? describeError(record.error, 0, budget) : null;
        if (withFields) {
            JsonObject fields = admittedFields(record.fields, budget);
            if (fields.size() > 0) {
                line.add("fields", fields);
            }
        }
        if (error != null) {
            line.add("error", error);
        }
        return line.toString();
    }


    static SimpleDateFormat isoFormat() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format;
    }


    private static JsonObject admittedFields(
        Map<String, Object> recordFields,
        StackBudget budget
    ) {
        JsonObject fields = new JsonObject();
        for (Map.Entry<String, Object> field : recordFields.entrySet()) {
            JsonElement value = fieldJson(field.getValue(), budget);
            if (value != null) {
                fields.add(field.getKey(), value);
            }
        }
        return fields;
    }


    private static JsonElement fieldJson(
        Object value,
        StackBudget budget
    ) {
        if (value instanceof Throwable) {
            return describeError((Throwable) value, 0, budget);
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
        int depth,
        StackBudget budget
    ) {
        JsonObject described = new JsonObject();
        described.addProperty("type", error.getClass().getName());
        if (budget.mode == StackMode.Unlimited) {
            StringBuilder stack = new StringBuilder();
            for (StackTraceElement frame : error.getStackTrace()) {
                if (stack.length() >= MaxStackLength) {
                    break;
                }
                stack.append(frameText(frame));
            }
            described.addProperty("stack", truncate(stack.toString(), MaxStackLength));
        } else if (budget.mode == StackMode.Limited) {
            StringBuilder stack = new StringBuilder();
            boolean cut = false;
            for (StackTraceElement frame : error.getStackTrace()) {
                String text = frameText(frame);
                if (text.length() > budget.remaining) {
                    cut = true;
                    break;
                }
                stack.append(text);
                budget.remaining -= text.length();
            }
            described.addProperty("stack", stack.toString());
            if (cut) {
                described.addProperty("truncated", true);
            }
        }
        Throwable cause = error.getCause();
        if (cause != null && cause != error && depth < MaxCauseDepth) {
            described.add("cause", describeError(cause, depth + 1, budget));
        }
        return described;
    }


    private static String frameText(StackTraceElement frame) {
        return "at " + frame.getClassName() + '.' + frame.getMethodName() + ':' + frame.getLineNumber() + '\n';
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
