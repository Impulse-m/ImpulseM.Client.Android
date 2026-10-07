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
        JsonObject payload = new JsonObject();
        payload.addProperty("text", text);
        JsonElement data = call("convertShareLinksToXrayJson", payload);
        if (data == null || !data.isJsonObject()) {
            return new JsonArray();
        }
        JsonArray outbounds = data.getAsJsonObject().getAsJsonArray("outbounds");
        return outbounds == null ? new JsonArray() : outbounds;
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
        JsonElement data = call("getXrayState", null);
        return data != null && data.isJsonObject() && data.getAsJsonObject().get("running").getAsBoolean();
    }


    public int[] freePorts(int count) throws XrayException {
        JsonObject payload = new JsonObject();
        payload.addProperty("count", count);
        JsonArray ports = call("getFreePorts", payload).getAsJsonObject().getAsJsonArray("ports");
        int[] result = new int[ports.size()];
        for (int i = 0; i < ports.size(); i++) {
            result[i] = ports.get(i).getAsInt();
        }
        return result;
    }


    public long[] pingBatch(
        List<String> xrayJsons,
        String outboundTag,
        String url,
        int timeoutSeconds
    ) throws XrayException {
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
        JsonArray results = call("pingBatch", payload).getAsJsonObject().getAsJsonArray("results");
        long[] delays = new long[xrayJsons.size()];
        for (int i = 0; i < delays.length; i++) {
            JsonObject result = results != null && i < results.size() ? results.get(i).getAsJsonObject() : null;
            delays[i] = result != null && result.get("success").getAsBoolean() ? result.get("delay").getAsLong() : -1L;
        }
        return delays;
    }


    private static JsonObject configPayload(String xrayJson) {
        JsonObject payload = new JsonObject();
        payload.addProperty("xrayJson", xrayJson);
        return payload;
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
            response = JsonParser.parseString(runtime.invoke(request.toString())).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new XrayException(method + ": malformed response");
        }
        JsonElement success = response.get("success");
        if (success == null || !success.getAsBoolean()) {
            JsonElement error = response.get("error");
            throw new XrayException(method + ": " + (error == null ? "unknown error" : error.getAsString()));
        }
        return response.get("data");
    }
}
