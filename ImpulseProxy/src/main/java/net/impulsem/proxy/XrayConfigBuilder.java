package net.impulsem.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;


/**
 * Builds the Xray run config: loopback HTTP + SOCKS inbounds with accounts,
 * all traffic to the selected VLESS outbound.
 */
public final class XrayConfigBuilder {
    public static final String ProxyTag = "proxy";


    private XrayConfigBuilder() {
    }


    public static String build(
        ProxyServer server,
        LocalInbounds inbounds
    ) {
        requireCredential(inbounds.user, "user");
        requireCredential(inbounds.password, "password");
        JsonObject config = new JsonObject();
        JsonObject log = new JsonObject();
        log.addProperty("loglevel", "error");
        log.addProperty("access", "none");
        config.add("log", log);

        JsonArray inboundList = new JsonArray();
        inboundList.add(httpInbound(inbounds));
        inboundList.add(socksInbound(inbounds));
        config.add("inbounds", inboundList);

        JsonArray outbounds = new JsonArray();
        outbounds.add(taggedOutbound(server));
        config.add("outbounds", outbounds);

        JsonObject rule = new JsonObject();
        rule.addProperty("type", "field");
        JsonArray inboundTags = new JsonArray();
        inboundTags.add("http-in");
        inboundTags.add("socks-in");
        rule.add("inboundTag", inboundTags);
        rule.addProperty("outboundTag", ProxyTag);
        JsonArray rules = new JsonArray();
        rules.add(rule);
        JsonObject routing = new JsonObject();
        routing.addProperty("domainStrategy", "AsIs");
        routing.add("rules", rules);
        config.add("routing", routing);
        return config.toString();
    }


    public static String pingConfig(ProxyServer server) {
        JsonObject config = new JsonObject();
        JsonArray outbounds = new JsonArray();
        outbounds.add(taggedOutbound(server));
        config.add("outbounds", outbounds);
        return config.toString();
    }


    private static void requireCredential(
        String value,
        String name
    ) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Local inbound " + name + " must not be empty");
        }
    }


    private static JsonObject taggedOutbound(ProxyServer server) {
        JsonObject outbound = server.outbound();
        outbound.addProperty("tag", ProxyTag);
        return outbound;
    }


    private static JsonObject account(LocalInbounds inbounds) {
        JsonObject account = new JsonObject();
        account.addProperty("user", inbounds.user);
        account.addProperty("pass", inbounds.password);
        return account;
    }


    private static JsonObject httpInbound(LocalInbounds inbounds) {
        JsonArray accounts = new JsonArray();
        accounts.add(account(inbounds));
        JsonObject settings = new JsonObject();
        settings.add("accounts", accounts);
        settings.addProperty("allowTransparent", false);
        JsonObject inbound = new JsonObject();
        inbound.addProperty("tag", "http-in");
        inbound.addProperty("listen", "127.0.0.1");
        inbound.addProperty("port", inbounds.httpPort);
        inbound.addProperty("protocol", "http");
        inbound.add("settings", settings);
        return inbound;
    }


    private static JsonObject socksInbound(LocalInbounds inbounds) {
        JsonArray accounts = new JsonArray();
        accounts.add(account(inbounds));
        JsonObject settings = new JsonObject();
        settings.addProperty("auth", "password");
        settings.add("accounts", accounts);
        settings.addProperty("udp", true);
        settings.addProperty("ip", "127.0.0.1");
        JsonObject inbound = new JsonObject();
        inbound.addProperty("tag", "socks-in");
        inbound.addProperty("listen", "127.0.0.1");
        inbound.addProperty("port", inbounds.socksPort);
        inbound.addProperty("protocol", "socks");
        inbound.add("settings", settings);
        return inbound;
    }
}
