package net.impulsem.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;


/**
 * Builds the Xray run config: loopback HTTP + SOCKS inbounds with accounts,
 * all traffic to the selected VLESS outbound.
 */
public final class XrayConfigBuilder {
    public static final String ProxyTag = "proxy";
    public static final String FragmentTag = "fragment";


    private XrayConfigBuilder() {
    }


    public static String build(
        ProxyServer server,
        LocalInbounds inbounds
    ) {
        return build(server, inbounds, new ProxyAdvanced());
    }


    public static String build(
        ProxyServer server,
        LocalInbounds inbounds,
        ProxyAdvanced advanced
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

        config.add("outbounds", advancedOutbounds(server, advanced));
        addDns(config, advanced);

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
        return pingConfig(server, new ProxyAdvanced());
    }


    public static String pingConfig(
        ProxyServer server,
        ProxyAdvanced advanced
    ) {
        JsonObject config = new JsonObject();
        config.add("outbounds", advancedOutbounds(server, advanced));
        addDns(config, advanced);
        return config.toString();
    }


    /** The proxy outbound with the advanced settings applied, plus the fragment dialer in classic mode. */
    private static JsonArray advancedOutbounds(
        ProxyServer server,
        ProxyAdvanced advanced
    ) {
        JsonObject proxy = taggedOutbound(server);
        JsonArray outbounds = new JsonArray();
        outbounds.add(proxy);
        String[] fragment = effectiveFragment(server, advanced);
        boolean classic = fragment != null && ProxyAdvanced.FragmentClassic.equals(fragment[0]);

        if (!advanced.fingerprint.isEmpty()) {
            JsonObject stream = streamOf(proxy, false);
            if (stream != null) {
                setFingerprint(stream, "tlsSettings", advanced.fingerprint);
                setFingerprint(stream, "realitySettings", advanced.fingerprint);
            }
        }
        if (advanced.muxEnabled && !usesVision(proxy)) {
            JsonObject mux = new JsonObject();
            mux.addProperty("enabled", true);
            mux.addProperty("concurrency", advanced.muxConcurrency);
            proxy.add("mux", mux);
        }

        // The dialing outbound carries the TCP options and the DNS strategy: the freedom dialer in classic mode.
        JsonObject dialer = proxy;
        JsonObject freedom = null;
        if (classic) {
            freedom = new JsonObject();
            freedom.addProperty("tag", FragmentTag);
            freedom.addProperty("protocol", "freedom");
            JsonObject settings = new JsonObject();
            JsonObject fragmentJson = new JsonObject();
            fragmentJson.addProperty("packets", fragment[1]);
            fragmentJson.addProperty("length", fragment[2]);
            fragmentJson.addProperty("interval", fragment[3]);
            settings.add("fragment", fragmentJson);
            freedom.add("settings", settings);
            dialer = freedom;
            streamOf(proxy, true).add("sockopt", dialerProxySockopt());
        } else if (fragment != null) {
            JsonObject settings = new JsonObject();
            settings.addProperty("packets", fragment[1]);
            JsonArray lengths = new JsonArray();
            lengths.add(fragment[2]);
            settings.add("lengths", lengths);
            JsonArray delays = new JsonArray();
            delays.add(fragment[3]);
            settings.add("delays", delays);
            JsonObject mask = new JsonObject();
            mask.addProperty("type", "fragment");
            mask.add("settings", settings);
            JsonArray tcp = new JsonArray();
            tcp.add(mask);
            JsonObject finalMask = new JsonObject();
            finalMask.add("tcp", tcp);
            streamOf(proxy, true).add("finalmask", finalMask);
        }

        if (advanced.keepAliveIdle > 0) {
            sockoptOf(dialer).addProperty("tcpKeepAliveIdle", advanced.keepAliveIdle);
        }
        if (advanced.keepAliveInterval > 0) {
            sockoptOf(dialer).addProperty("tcpKeepAliveInterval", advanced.keepAliveInterval);
        }
        if (advanced.tcpFastOpen) {
            sockoptOf(dialer).addProperty("tcpFastOpen", true);
        }
        if (advanced.tcpMaxSeg > 0) {
            sockoptOf(dialer).addProperty("tcpMaxSeg", advanced.tcpMaxSeg);
        }
        if (advanced.effectiveDnsServer() != null) {
            if (classic) {
                freedom.getAsJsonObject("settings").addProperty("domainStrategy", "UseIPv4");
            } else {
                sockoptOf(dialer).addProperty("domainStrategy", "UseIPv4");
            }
        }
        if (freedom != null) {
            outbounds.add(freedom);
        }
        return outbounds;
    }


    private static void addDns(
        JsonObject config,
        ProxyAdvanced advanced
    ) {
        String server = advanced.effectiveDnsServer();
        if (server == null) {
            return;
        }
        JsonArray servers = new JsonArray();
        servers.add(server);
        JsonObject dns = new JsonObject();
        dns.add("servers", servers);
        dns.addProperty("queryStrategy", "UseIPv4");
        config.add("dns", dns);
    }


    /**
     * The fragmentation to apply as {mode, packets, length, interval}, or null for none.
     * A global mode wins; otherwise a Happ-style fragment= link parameter is honoured (classic mode).
     */
    private static String[] effectiveFragment(
        ProxyServer server,
        ProxyAdvanced advanced
    ) {
        if (!ProxyAdvanced.FragmentOff.equals(advanced.fragmentMode)) {
            return new String[] {
                advanced.fragmentMode,
                advanced.fragmentPackets,
                advanced.fragmentLength,
                advanced.fragmentInterval
            };
        }
        if (!advanced.fragmentFromLink || server.shareLink == null) {
            return null;
        }
        String value = queryParameter(server.shareLink, "fragment");
        if (value == null) {
            return null;
        }
        String[] parts = value.split(",", -1);
        if (parts.length < 3) {
            return null;
        }
        String length = parts[0].trim();
        String interval = parts[1].trim();
        String packets = parts[2].trim();
        if (!ProxyAdvanced.isRange(length) || !ProxyAdvanced.isRange(interval) || !ProxyAdvanced.isPackets(packets)) {
            return null;
        }
        return new String[] {ProxyAdvanced.FragmentClassic, packets, length, interval};
    }


    /** The URL-decoded value of a query parameter in a share link, or null when absent. */
    private static String queryParameter(
        String link,
        String name
    ) {
        int hash = link.indexOf('#');
        String withoutFragment = hash >= 0 ? link.substring(0, hash) : link;
        int question = withoutFragment.indexOf('?');
        if (question < 0) {
            return null;
        }
        for (String pair : withoutFragment.substring(question + 1).split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                try {
                    return URLDecoder.decode(pair.substring(equals + 1), "UTF-8");
                } catch (UnsupportedEncodingException | IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }


    /** The outbound's streamSettings; created when create is set, otherwise null when absent. */
    private static JsonObject streamOf(
        JsonObject outbound,
        boolean create
    ) {
        JsonElement stream = outbound.get("streamSettings");
        if (stream != null && stream.isJsonObject()) {
            return stream.getAsJsonObject();
        }
        if (!create) {
            return null;
        }
        JsonObject created = new JsonObject();
        outbound.add("streamSettings", created);
        return created;
    }


    /** The outbound's streamSettings.sockopt, created on demand. */
    private static JsonObject sockoptOf(JsonObject outbound) {
        JsonObject stream = streamOf(outbound, true);
        JsonElement sockopt = stream.get("sockopt");
        if (sockopt != null && sockopt.isJsonObject()) {
            return sockopt.getAsJsonObject();
        }
        JsonObject created = new JsonObject();
        stream.add("sockopt", created);
        return created;
    }


    private static JsonObject dialerProxySockopt() {
        JsonObject sockopt = new JsonObject();
        sockopt.addProperty("dialerProxy", FragmentTag);
        return sockopt;
    }


    private static void setFingerprint(
        JsonObject stream,
        String key,
        String fingerprint
    ) {
        JsonElement settings = stream.get(key);
        if (settings != null && settings.isJsonObject()) {
            settings.getAsJsonObject().addProperty("fingerprint", fingerprint);
        }
    }


    /** Vision flow is incompatible with mux; checks settings.flow and every vnext user's flow. */
    private static boolean usesVision(JsonObject outbound) {
        JsonElement settingsElement = outbound.get("settings");
        if (settingsElement == null || !settingsElement.isJsonObject()) {
            return false;
        }
        JsonObject settings = settingsElement.getAsJsonObject();
        if (isVision(settings.get("flow"))) {
            return true;
        }
        JsonElement vnext = settings.get("vnext");
        if (vnext == null || !vnext.isJsonArray()) {
            return false;
        }
        for (JsonElement endpoint : vnext.getAsJsonArray()) {
            if (!endpoint.isJsonObject()) {
                continue;
            }
            JsonElement users = endpoint.getAsJsonObject().get("users");
            if (users == null || !users.isJsonArray()) {
                continue;
            }
            for (JsonElement user : users.getAsJsonArray()) {
                if (user.isJsonObject() && isVision(user.getAsJsonObject().get("flow"))) {
                    return true;
                }
            }
        }
        return false;
    }


    private static boolean isVision(JsonElement flow) {
        return flow != null
            && flow.isJsonPrimitive()
            && flow.getAsString().startsWith("xtls-rprx-vision");
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
