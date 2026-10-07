package net.impulsem.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;


/** Typed wrapper over libXray's JSON Invoke API (apiVersion 3). */
public final class LibXrayClient {
    public static final int ApiVersion = 3;

    private final XrayRuntime runtime;


    public LibXrayClient(XrayRuntime runtime) {
        this.runtime = runtime;
    }


    public JsonArray convertShareLinks(String text) throws XrayException {
        String method = "convertShareLinksToXrayJson";
        JsonObject payload = new JsonObject();
        payload.addProperty("text", text);
        JsonObject data = objectOf(method, call(method, payload));
        return arrayOf(method, data, "outbounds");
    }


    public void test(String xrayJson) throws XrayException {
        call("testXray", configPayload(xrayJson));
    }


    public void run(String xrayJson) throws XrayException {
        call("runXray", configPayload(xrayJson));
    }


    public void stop() throws XrayException {
        call("stopXray", null);
    }


    public boolean isRunning() throws XrayException {
        String method = "getXrayState";
        JsonObject data = objectOf(method, call(method, null));
        return booleanOf(method, data.get("running"));
    }


    public int[] freePorts(int count) throws XrayException {
        String method = "getFreePorts";
        JsonObject payload = new JsonObject();
        payload.addProperty("count", count);
        JsonArray ports = arrayOf(method, objectOf(method, call(method, payload)), "ports");
        int[] result = new int[ports.size()];
        for (int i = 0; i < ports.size(); i++) {
            result[i] = intOf(method, ports.get(i));
        }
        return result;
    }


    /** One delay per config in milliseconds; -1 when the config failed or has no result entry. */
    public long[] pingBatch(
        List<String> xrayJsons,
        String outboundTag,
        String url,
        int timeoutSeconds
    ) throws XrayException {
        String method = "pingBatch";
        JsonArray configs = new JsonArray();
        for (String xrayJson : xrayJsons) {
            JsonObject item = new JsonObject();
            item.addProperty("xrayJson", xrayJson);
            item.addProperty("outboundTag", outboundTag);
            configs.add(item);
        }
        JsonObject payload = new JsonObject();
        payload.add("configs", configs);
        payload.addProperty("timeout", timeoutSeconds);
        payload.addProperty("url", url);
        JsonArray results = arrayOf(method, objectOf(method, call(method, payload)), "results");
        long[] delays = new long[xrayJsons.size()];
        for (int i = 0; i < delays.length; i++) {
            if (i >= results.size()) {
                delays[i] = -1L;
                continue;
            }
            JsonObject result = objectOf(method, results.get(i));
            delays[i] = booleanOf(method, result.get("success")) ? longOf(method, result.get("delay")) : -1L;
        }
        return delays;
    }


    private static JsonObject configPayload(String xrayJson) {
        JsonObject payload = new JsonObject();
        payload.addProperty("xrayJson", xrayJson);
        return payload;
    }


    private static XrayException malformed(String method) {
        return new XrayException(method + ": malformed response");
    }


    private static JsonObject objectOf(
        String method,
        JsonElement element
    ) throws XrayException {
        if (element == null || !element.isJsonObject()) {
            throw malformed(method);
        }
        return element.getAsJsonObject();
    }


    private static JsonArray arrayOf(
        String method,
        JsonObject object,
        String key
    ) throws XrayException {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonArray()) {
            throw malformed(method);
        }
        return element.getAsJsonArray();
    }


    private static boolean booleanOf(
        String method,
        JsonElement element
    ) throws XrayException {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw malformed(method);
        }
        return element.getAsBoolean();
    }


    private static int intOf(
        String method,
        JsonElement element
    ) throws XrayException {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw malformed(method);
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException e) {
            throw malformed(method);
        }
    }


    private static long longOf(
        String method,
        JsonElement element
    ) throws XrayException {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw malformed(method);
        }
        try {
            return element.getAsLong();
        } catch (NumberFormatException e) {
            throw malformed(method);
        }
    }


    private JsonElement call(
        String method,
        JsonObject payload
    ) throws XrayException {
        JsonObject request = new JsonObject();
        request.addProperty("apiVersion", ApiVersion);
        request.addProperty("method", method);
        if (payload != null) {
            request.add("payload", payload);
        }
        JsonObject response;
        try {
            JsonElement parsed = JsonParser.parseString(runtime.invoke(request.toString()));
            if (!parsed.isJsonObject()) {
                throw malformed(method);
            }
            response = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw malformed(method);
        }
        boolean success = booleanOf(method, response.get("success"));
        if (!success) {
            JsonElement error = response.get("error");
            boolean hasText = error != null && error.isJsonPrimitive();
            throw new XrayException(method + ": " + (hasText ? error.getAsString() : "unknown error"));
        }
        return response.get("data");
    }
}
