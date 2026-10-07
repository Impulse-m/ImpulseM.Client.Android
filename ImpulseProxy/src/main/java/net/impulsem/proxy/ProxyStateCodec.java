package net.impulsem.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;


/** ProxyState <-> JSON for SharedPreferences. Corrupt input decodes to an empty state. */
public final class ProxyStateCodec {

    private ProxyStateCodec() {
    }


    public static String encode(ProxyState state) {
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        root.addProperty("enabled", state.enabled);
        root.addProperty("useForCalls", state.useForCalls);
        if (state.selectedId != null) {
            root.addProperty("selectedId", state.selectedId);
        }
        root.add("manual", encodeServers(state.manual));
        JsonArray subscriptions = new JsonArray();
        for (Subscription subscription : state.subscriptions) {
            JsonObject item = new JsonObject();
            item.addProperty("id", subscription.id);
            item.addProperty("url", subscription.url);
            item.addProperty("updatedAt", subscription.updatedAt);
            item.addProperty("skipped", subscription.skipped);
            if (subscription.lastError != null) {
                item.addProperty("lastError", subscription.lastError);
            }
            JsonObject meta = new JsonObject();
            if (subscription.meta.title != null) {
                meta.addProperty("title", subscription.meta.title);
            }
            meta.addProperty("upload", subscription.meta.upload);
            meta.addProperty("download", subscription.meta.download);
            meta.addProperty("total", subscription.meta.total);
            meta.addProperty("expire", subscription.meta.expire);
            meta.addProperty("interval", subscription.meta.updateIntervalHours);
            item.add("meta", meta);
            item.add("servers", encodeServers(subscription.servers));
            subscriptions.add(item);
        }
        root.add("subscriptions", subscriptions);
        return root.toString();
    }


    public static ProxyState decode(String json) {
        ProxyState state = new ProxyState();
        if (json == null) {
            return state;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            state.enabled = root.has("enabled") && root.get("enabled").getAsBoolean();
            state.useForCalls = root.has("useForCalls") && root.get("useForCalls").getAsBoolean();
            state.selectedId = root.has("selectedId") ? root.get("selectedId").getAsString() : null;
            state.manual.addAll(decodeServers(root.getAsJsonArray("manual")));
            JsonArray subscriptions = root.getAsJsonArray("subscriptions");
            if (subscriptions != null) {
                for (JsonElement element : subscriptions) {
                    JsonObject item = element.getAsJsonObject();
                    JsonObject meta = item.getAsJsonObject("meta");
                    state.subscriptions.add(new Subscription(
                        item.get("id").getAsString(),
                        item.get("url").getAsString(),
                        new SubscriptionMeta(
                            meta.has("title") ? meta.get("title").getAsString() : null,
                            meta.get("upload").getAsLong(),
                            meta.get("download").getAsLong(),
                            meta.get("total").getAsLong(),
                            meta.get("expire").getAsLong(),
                            meta.get("interval").getAsInt()
                        ),
                        item.get("updatedAt").getAsLong(),
                        item.has("lastError") ? item.get("lastError").getAsString() : null,
                        item.get("skipped").getAsInt(),
                        decodeServers(item.getAsJsonArray("servers"))
                    ));
                }
            }
            return state;
        } catch (RuntimeException e) {
            return new ProxyState();
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
            JsonObject item = element.getAsJsonObject();
            servers.add(new ProxyServer(
                item.get("id").getAsString(),
                item.get("name").getAsString(),
                item.get("host").getAsString(),
                item.get("port").getAsInt(),
                item.get("network").getAsString(),
                item.get("security").getAsString(),
                item.get("outbound").getAsString()
            ));
        }
        return servers;
    }
}
