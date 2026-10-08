package net.impulsem.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;


/**
 * ProxyState <-> JSON for SharedPreferences.
 * Decoding is best-effort: a bad server or subscription is skipped, the rest of the state survives.
 * Only input that is not a JSON object at all decodes to an empty state.
 */
public final class ProxyStateCodec {

    private ProxyStateCodec() {
    }


    public static String encode(ProxyState state) {
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        root.addProperty("enabled", state.enabled);
        root.addProperty("useForTransport", state.useForTransport);
        root.addProperty("useForCalls", state.useForCalls);
        if (state.selectedId != null) {
            root.addProperty("selectedId", state.selectedId);
        }
        root.add("manual", encodeServers(state.manual));
        JsonArray subscriptions = new JsonArray();
        for (Subscription subscription : state.subscriptions) {
            SubscriptionMeta meta = subscription.meta == null
                ? SubscriptionMeta.parse(null, null, null)
                : subscription.meta;
            JsonObject item = new JsonObject();
            item.addProperty("id", subscription.id);
            item.addProperty("url", subscription.url);
            item.addProperty("updatedAt", subscription.updatedAt);
            item.addProperty("skipped", subscription.skipped);
            if (subscription.lastError != null) {
                item.addProperty("lastError", subscription.lastError);
            }
            JsonObject metaJson = new JsonObject();
            if (meta.title != null) {
                metaJson.addProperty("title", meta.title);
            }
            metaJson.addProperty("upload", meta.upload);
            metaJson.addProperty("download", meta.download);
            metaJson.addProperty("total", meta.total);
            metaJson.addProperty("expire", meta.expire);
            metaJson.addProperty("interval", meta.updateIntervalHours);
            item.add("meta", metaJson);
            item.add("servers", encodeServers(subscription.servers));
            subscriptions.add(item);
        }
        root.add("subscriptions", subscriptions);
        root.add("advanced", encodeAdvanced(state.advanced == null ? new ProxyAdvanced() : state.advanced));
        return root.toString();
    }


    public static ProxyState decode(String json) {
        if (json == null) {
            return new ProxyState();
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return new ProxyState();
        }
        ProxyState state = new ProxyState();
        state.enabled = readBoolean(root, "enabled");
        state.useForTransport = readBoolean(root, "useForTransport", true);
        state.useForCalls = readBoolean(root, "useForCalls");
        state.selectedId = readString(root, "selectedId");
        state.manual.addAll(decodeServers(readArray(root, "manual")));
        JsonArray subscriptions = readArray(root, "subscriptions");
        if (subscriptions != null) {
            for (JsonElement element : subscriptions) {
                Subscription subscription = decodeSubscription(element);
                if (subscription != null) {
                    state.subscriptions.add(subscription);
                }
            }
        }
        state.advanced = decodeAdvanced(root.get("advanced"));
        return state;
    }


    private static JsonObject encodeAdvanced(ProxyAdvanced advanced) {
        JsonObject item = new JsonObject();
        item.addProperty("fragmentMode", advanced.fragmentMode);
        item.addProperty("fragmentPackets", advanced.fragmentPackets);
        item.addProperty("fragmentLength", advanced.fragmentLength);
        item.addProperty("fragmentInterval", advanced.fragmentInterval);
        item.addProperty("fragmentFromLink", advanced.fragmentFromLink);
        item.addProperty("fingerprint", advanced.fingerprint);
        item.addProperty("muxEnabled", advanced.muxEnabled);
        item.addProperty("muxConcurrency", advanced.muxConcurrency);
        item.addProperty("keepAliveIdle", advanced.keepAliveIdle);
        item.addProperty("keepAliveInterval", advanced.keepAliveInterval);
        item.addProperty("tcpFastOpen", advanced.tcpFastOpen);
        item.addProperty("tcpMaxSeg", advanced.tcpMaxSeg);
        item.addProperty("dnsMode", advanced.dnsMode);
        item.addProperty("dnsCustom", advanced.dnsCustom);
        item.addProperty("pingMode", advanced.pingMode);
        item.addProperty("pingPauseMillis", advanced.pingPauseMillis);
        item.addProperty("pingTimeoutSeconds", advanced.pingTimeoutSeconds);
        item.addProperty("pingUrl", advanced.pingUrl);
        item.addProperty("pingSkipWhileConnected", advanced.pingSkipWhileConnected);
        return item;
    }


    /** Missing or mistyped fields keep their defaults; the result is always sanitized. */
    private static ProxyAdvanced decodeAdvanced(JsonElement element) {
        ProxyAdvanced advanced = new ProxyAdvanced();
        if (element != null && element.isJsonObject()) {
            JsonObject item = element.getAsJsonObject();
            advanced.fragmentMode = readString(item, "fragmentMode", advanced.fragmentMode);
            advanced.fragmentPackets = readString(item, "fragmentPackets", advanced.fragmentPackets);
            advanced.fragmentLength = readString(item, "fragmentLength", advanced.fragmentLength);
            advanced.fragmentInterval = readString(item, "fragmentInterval", advanced.fragmentInterval);
            advanced.fragmentFromLink = readBoolean(item, "fragmentFromLink", advanced.fragmentFromLink);
            advanced.fingerprint = readString(item, "fingerprint", advanced.fingerprint);
            advanced.muxEnabled = readBoolean(item, "muxEnabled", advanced.muxEnabled);
            advanced.muxConcurrency = readInt(item, "muxConcurrency", advanced.muxConcurrency);
            advanced.keepAliveIdle = readInt(item, "keepAliveIdle", advanced.keepAliveIdle);
            advanced.keepAliveInterval = readInt(item, "keepAliveInterval", advanced.keepAliveInterval);
            advanced.tcpFastOpen = readBoolean(item, "tcpFastOpen", advanced.tcpFastOpen);
            advanced.tcpMaxSeg = readInt(item, "tcpMaxSeg", advanced.tcpMaxSeg);
            advanced.dnsMode = readString(item, "dnsMode", advanced.dnsMode);
            advanced.dnsCustom = readString(item, "dnsCustom", advanced.dnsCustom);
            advanced.pingMode = readString(item, "pingMode", advanced.pingMode);
            advanced.pingPauseMillis = readInt(item, "pingPauseMillis", advanced.pingPauseMillis);
            advanced.pingTimeoutSeconds = readInt(item, "pingTimeoutSeconds", advanced.pingTimeoutSeconds);
            advanced.pingUrl = readString(item, "pingUrl", advanced.pingUrl);
            advanced.pingSkipWhileConnected = readBoolean(item, "pingSkipWhileConnected", advanced.pingSkipWhileConnected);
        }
        advanced.sanitize();
        return advanced;
    }


    private static boolean readBoolean(
        JsonObject object,
        String key,
        boolean fallback
    ) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }
        return element.getAsBoolean();
    }


    private static String readString(
        JsonObject object,
        String key,
        String fallback
    ) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return fallback;
        }
        return element.getAsString();
    }


    private static int readInt(
        JsonObject object,
        String key,
        int fallback
    ) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        try {
            return element.getAsBigDecimal().intValueExact();
        } catch (RuntimeException e) {
            return fallback;
        }
    }


    private static JsonArray encodeServers(List<ProxyServer> servers) {
        JsonArray array = new JsonArray();
        for (ProxyServer server : servers) {
            JsonObject item = new JsonObject();
            item.addProperty("id", server.id);
            item.addProperty("name", server.name);
            item.addProperty("host", server.host);
            item.addProperty("port", server.port);
            item.addProperty("network", server.network);
            item.addProperty("security", server.security);
            item.addProperty("outbound", server.outboundJson);
            if (server.shareLink != null) {
                item.addProperty("shareLink", server.shareLink);
            }
            array.add(item);
        }
        return array;
    }


    private static List<ProxyServer> decodeServers(JsonArray array) {
        List<ProxyServer> servers = new ArrayList<ProxyServer>();
        if (array == null) {
            return servers;
        }
        for (JsonElement element : array) {
            try {
                servers.add(decodeServer(element));
            } catch (RuntimeException e) {
                // Malformed server: skip it, keep the rest of the state.
            }
        }
        return servers;
    }


    private static ProxyServer decodeServer(JsonElement element) {
        JsonObject item = element.getAsJsonObject();
        return new ProxyServer(
            item.get("id").getAsString(),
            item.get("name").getAsString(),
            item.get("host").getAsString(),
            item.get("port").getAsInt(),
            item.get("network").getAsString(),
            item.get("security").getAsString(),
            item.get("outbound").getAsString(),
            readString(item, "shareLink")
        );
    }


    /** Returns null when the subscription has no usable id or url. */
    private static Subscription decodeSubscription(JsonElement element) {
        try {
            JsonObject item = element.getAsJsonObject();
            String id = item.get("id").getAsString();
            String url = item.get("url").getAsString();
            return new Subscription(
                id,
                url,
                decodeMeta(item.get("meta")),
                readLong(item, "updatedAt", 0L),
                readString(item, "lastError"),
                (int) readLong(item, "skipped", 0L),
                decodeServers(readArray(item, "servers"))
            );
        } catch (RuntimeException e) {
            return null;
        }
    }


    private static SubscriptionMeta decodeMeta(JsonElement element) {
        SubscriptionMeta defaults = SubscriptionMeta.parse(null, null, null);
        if (element == null || !element.isJsonObject()) {
            return defaults;
        }
        JsonObject meta = element.getAsJsonObject();
        return new SubscriptionMeta(
            readString(meta, "title"),
            readLong(meta, "upload", defaults.upload),
            readLong(meta, "download", defaults.download),
            readLong(meta, "total", defaults.total),
            readLong(meta, "expire", defaults.expire),
            (int) readLong(meta, "interval", defaults.updateIntervalHours)
        );
    }


    private static boolean readBoolean(
        JsonObject object,
        String key
    ) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return false;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }


    private static String readString(
        JsonObject object,
        String key
    ) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }


    private static long readLong(
        JsonObject object,
        String key,
        long fallback
    ) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }


    private static JsonArray readArray(
        JsonObject object,
        String key
    ) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }
}
