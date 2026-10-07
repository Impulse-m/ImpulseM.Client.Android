package net.impulsem.proxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;


/** One selectable VLESS server: display data plus the Xray outbound it came from. */
public final class ProxyServer {
    public final String id;
    public final String name;
    public final String host;
    public final int port;
    public final String network;
    public final String security;
    public final String outboundJson;


    public ProxyServer(
        String id,
        String name,
        String host,
        int port,
        String network,
        String security,
        String outboundJson
    ) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.port = port;
        this.network = network;
        this.security = security;
        this.outboundJson = outboundJson;
    }


    /** Returns null when the outbound has no usable address or its fields have the wrong JSON types. */
    public static ProxyServer fromOutbound(JsonObject outbound) {
        try {
            JsonObject settings = outbound.has("settings") && outbound.get("settings").isJsonObject()
                ? outbound.getAsJsonObject("settings")
                : new JsonObject();
            JsonObject endpoint = settings;
            if (settings.has("vnext") && settings.getAsJsonArray("vnext").size() > 0) {
                endpoint = settings.getAsJsonArray("vnext").get(0).getAsJsonObject();
            }
            String host = endpoint.has("address") ? endpoint.get("address").getAsString() : "";
            int port = endpoint.has("port") ? endpoint.get("port").getAsInt() : 0;
            if (host.isEmpty() || port <= 0) {
                return null;
            }
            JsonObject stream = outbound.has("streamSettings") && outbound.get("streamSettings").isJsonObject()
                ? outbound.getAsJsonObject("streamSettings")
                : new JsonObject();
            String network = stream.has("network") ? stream.get("network").getAsString() : "tcp";
            String security = stream.has("security") ? stream.get("security").getAsString() : "none";
            String tag = outbound.has("tag") ? outbound.get("tag").getAsString().trim() : "";
            String outboundJson = outbound.toString();
            return new ProxyServer(
                sha256(outboundJson),
                tag.isEmpty() ? host : tag,
                host,
                port,
                network,
                security,
                outboundJson
            );
        } catch (ClassCastException | IllegalStateException | NumberFormatException | UnsupportedOperationException e) {
            return null;
        }
    }


    public JsonObject outbound() {
        return JsonParser.parseString(outboundJson).getAsJsonObject();
    }


    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
