package net.impulsem.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;


public class XrayConfigBuilderTest {
    private static final LocalInbounds Inbounds = new LocalInbounds(1, 2, "u", "p");
    private static final String LinkBase = "vless://u@h.example:443?type=tcp&fragment=";

    // Golden outbounds produced by the code before the advanced settings existed (edfd355).
    private static final String GoldenTlsOutbound = "{\"protocol\":\"vless\",\"tag\":\"proxy\","
        + "\"settings\":{\"vnext\":[{\"address\":\"h.example\",\"port\":443,\"users\":["
        + "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"encryption\":\"none\"}]}]},"
        + "\"streamSettings\":{\"network\":\"tcp\",\"security\":\"tls\",\"tlsSettings\":{\"serverName\":\"a\"}}}";
    private static final String GoldenRealityOutbound = "{\"protocol\":\"vless\",\"tag\":\"proxy\","
        + "\"settings\":{\"vnext\":[{\"address\":\"h.example\",\"port\":443,\"users\":["
        + "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"encryption\":\"none\",\"flow\":\"xtls-rprx-vision\"}]}]},"
        + "\"streamSettings\":{\"network\":\"tcp\",\"security\":\"reality\","
        + "\"realitySettings\":{\"serverName\":\"s\",\"fingerprint\":\"chrome\",\"publicKey\":\"k\",\"shortId\":\"ab\"}}}";

    private static ProxyServer server() {
        return ProxyServer.fromOutbound(JsonParser.parseString(
            "{\"protocol\":\"vless\",\"tag\":\"Name With Secrets?\","
                + "\"settings\":{\"vnext\":[{\"address\":\"h.example\",\"port\":443,"
                + "\"users\":[{\"id\":\"11111111-1111-1111-1111-111111111111\",\"encryption\":\"none\"}]}]},"
                + "\"streamSettings\":{\"network\":\"tcp\"}}"
        ).getAsJsonObject());
    }


    @Test
    public void inboundsListenOnLoopbackWithAccounts() {
        JsonObject config = JsonParser.parseString(
            XrayConfigBuilder.build(server(), new LocalInbounds(41001, 41002, "u1", "p1"))
        ).getAsJsonObject();
        JsonArray inbounds = config.getAsJsonArray("inbounds");
        JsonObject http = inbounds.get(0).getAsJsonObject();
        JsonObject socks = inbounds.get(1).getAsJsonObject();
        assertEquals("http", http.get("protocol").getAsString());
        assertEquals("127.0.0.1", http.get("listen").getAsString());
        assertEquals(41001, http.get("port").getAsInt());
        JsonObject httpAccount = http.getAsJsonObject("settings").getAsJsonArray("accounts").get(0).getAsJsonObject();
        assertEquals("u1", httpAccount.get("user").getAsString());
        assertEquals("socks", socks.get("protocol").getAsString());
        assertEquals("127.0.0.1", socks.get("listen").getAsString());
        assertEquals("password", socks.getAsJsonObject("settings").get("auth").getAsString());
        assertTrue(socks.getAsJsonObject("settings").get("udp").getAsBoolean());
        JsonObject socksAccount = socks.getAsJsonObject("settings").getAsJsonArray("accounts").get(0).getAsJsonObject();
        assertEquals("p1", socksAccount.get("pass").getAsString());
    }


    @Test
    public void coreLogsErrorsOnly() {
        JsonObject config = JsonParser.parseString(
            XrayConfigBuilder.build(server(), new LocalInbounds(1, 2, "u", "p"))
        ).getAsJsonObject();
        assertEquals("error", config.getAsJsonObject("log").get("loglevel").getAsString());
    }


    @Test
    public void everythingRoutesToTheProxyOutbound() {
        JsonObject config = JsonParser.parseString(
            XrayConfigBuilder.build(server(), new LocalInbounds(1, 2, "u", "p"))
        ).getAsJsonObject();
        JsonObject first = config.getAsJsonArray("outbounds").get(0).getAsJsonObject();
        assertEquals(XrayConfigBuilder.ProxyTag, first.get("tag").getAsString());
        assertEquals("vless", first.get("protocol").getAsString());
        JsonObject rule = config.getAsJsonObject("routing").getAsJsonArray("rules").get(0).getAsJsonObject();
        assertEquals(XrayConfigBuilder.ProxyTag, rule.get("outboundTag").getAsString());
        assertEquals("AsIs", config.getAsJsonObject("routing").get("domainStrategy").getAsString());
    }


    @Test
    public void accessLogIsOff() {
        JsonObject config = JsonParser.parseString(
            XrayConfigBuilder.build(server(), new LocalInbounds(1, 2, "u", "p"))
        ).getAsJsonObject();
        assertEquals("none", config.getAsJsonObject("log").get("access").getAsString());
    }


    @Test
    public void pingConfigHasOnlyTheTaggedOutbound() {
        JsonObject config = JsonParser.parseString(XrayConfigBuilder.pingConfig(server())).getAsJsonObject();
        assertEquals(1, config.getAsJsonArray("outbounds").size());
        assertEquals(
            XrayConfigBuilder.ProxyTag,
            config.getAsJsonArray("outbounds").get(0).getAsJsonObject().get("tag").getAsString()
        );
        assertFalse(config.has("inbounds"));
    }


    @Test(expected = IllegalArgumentException.class)
    public void emptyPasswordIsRejected() {
        XrayConfigBuilder.build(server(), new LocalInbounds(1, 2, "u", ""));
    }


    @Test(expected = IllegalArgumentException.class)
    public void nullUserIsRejected() {
        XrayConfigBuilder.build(server(), new LocalInbounds(1, 2, null, "p"));
    }


    @Test
    public void originalTagIsNotInTheConfig() {
        String config = XrayConfigBuilder.build(server(), new LocalInbounds(1, 2, "u", "p"));
        assertFalse(config.contains("Name With Secrets?"));
    }


    private static ProxyServer serverWith(
        String stream,
        String flow,
        String shareLink
    ) {
        String user = "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"encryption\":\"none\""
            + (flow == null ? "" : ",\"flow\":\"" + flow + "\"") + "}";
        ProxyServer base = ProxyServer.fromOutbound(JsonParser.parseString(
            "{\"protocol\":\"vless\",\"tag\":\"t\","
                + "\"settings\":{\"vnext\":[{\"address\":\"h.example\",\"port\":443,\"users\":[" + user + "]}]},"
                + "\"streamSettings\":" + stream + "}"
        ).getAsJsonObject());
        return shareLink == null ? base : base.withShareLink(shareLink);
    }


    private static JsonObject outboundOf(
        String config,
        int index
    ) {
        return JsonParser.parseString(config).getAsJsonObject().getAsJsonArray("outbounds").get(index).getAsJsonObject();
    }


    private static JsonObject sockopt(JsonObject outbound) {
        return outbound.getAsJsonObject("streamSettings").getAsJsonObject("sockopt");
    }


    private static String goldenBuild(String outbound) {
        return "{\"log\":{\"loglevel\":\"error\",\"access\":\"none\"},\"inbounds\":["
            + "{\"tag\":\"http-in\",\"listen\":\"127.0.0.1\",\"port\":41001,\"protocol\":\"http\","
            + "\"settings\":{\"accounts\":[{\"user\":\"u\",\"pass\":\"p\"}],\"allowTransparent\":false}},"
            + "{\"tag\":\"socks-in\",\"listen\":\"127.0.0.1\",\"port\":41002,\"protocol\":\"socks\","
            + "\"settings\":{\"auth\":\"password\",\"accounts\":[{\"user\":\"u\",\"pass\":\"p\"}],"
            + "\"udp\":true,\"ip\":\"127.0.0.1\"}}],"
            + "\"outbounds\":[" + outbound + "],"
            + "\"routing\":{\"domainStrategy\":\"AsIs\",\"rules\":[{\"type\":\"field\","
            + "\"inboundTag\":[\"http-in\",\"socks-in\"],\"outboundTag\":\"proxy\"}]}}";
    }


    @Test
    public void defaultAdvancedReproducesThePreChangeOutput() {
        LocalInbounds inbounds = new LocalInbounds(41001, 41002, "u", "p");
        ProxyServer tls = serverWith(
            "{\"network\":\"tcp\",\"security\":\"tls\",\"tlsSettings\":{\"serverName\":\"a\"}}",
            null,
            null
        );
        ProxyServer reality = serverWith(
            "{\"network\":\"tcp\",\"security\":\"reality\","
                + "\"realitySettings\":{\"serverName\":\"s\",\"fingerprint\":\"chrome\",\"publicKey\":\"k\",\"shortId\":\"ab\"}}",
            "xtls-rprx-vision",
            null
        );
        String tlsPing = "{\"outbounds\":[" + GoldenTlsOutbound + "]}";
        String realityPing = "{\"outbounds\":[" + GoldenRealityOutbound + "]}";
        assertEquals(goldenBuild(GoldenTlsOutbound), XrayConfigBuilder.build(tls, inbounds));
        assertEquals(goldenBuild(GoldenTlsOutbound), XrayConfigBuilder.build(tls, inbounds, new ProxyAdvanced()));
        assertEquals(goldenBuild(GoldenRealityOutbound), XrayConfigBuilder.build(reality, inbounds));
        assertEquals(goldenBuild(GoldenRealityOutbound), XrayConfigBuilder.build(reality, inbounds, new ProxyAdvanced()));
        assertEquals(tlsPing, XrayConfigBuilder.pingConfig(tls));
        assertEquals(tlsPing, XrayConfigBuilder.pingConfig(tls, new ProxyAdvanced()));
        assertEquals(realityPing, XrayConfigBuilder.pingConfig(reality));
        assertEquals(realityPing, XrayConfigBuilder.pingConfig(reality, new ProxyAdvanced()));
    }


    @Test
    public void classicFragmentKeepsExistingSockoptKeys() {
        ProxyAdvanced a = new ProxyAdvanced();
        a.fragmentMode = ProxyAdvanced.FragmentClassic;
        ProxyServer s = serverWith("{\"network\":\"tcp\",\"sockopt\":{\"mark\":255}}", null, null);
        JsonObject options = sockopt(outboundOf(XrayConfigBuilder.build(s, Inbounds, a), 0));
        assertEquals(255, options.get("mark").getAsInt());
        assertEquals("fragment", options.get("dialerProxy").getAsString());
    }


    @Test
    public void nullAdvancedAndNullFingerprintAreTolerated() {
        ProxyServer s = serverWith("{\"network\":\"tcp\",\"security\":\"tls\",\"tlsSettings\":{}}", null, null);
        assertEquals(XrayConfigBuilder.build(s, Inbounds), XrayConfigBuilder.build(s, Inbounds, null));
        assertEquals(XrayConfigBuilder.pingConfig(s), XrayConfigBuilder.pingConfig(s, null));
        ProxyAdvanced a = new ProxyAdvanced();
        a.fingerprint = null;
        assertEquals(XrayConfigBuilder.pingConfig(s), XrayConfigBuilder.pingConfig(s, a));
    }


    @Test
    public void linkPacketsAreCaseInsensitive() {
        ProxyServer s = serverWith("{\"network\":\"tcp\"}", null, LinkBase + "1-10,5-20,TLSHello");
        String config = XrayConfigBuilder.build(s, Inbounds, new ProxyAdvanced());
        assertEquals(
            "tlshello",
            outboundOf(config, 1).getAsJsonObject("settings").getAsJsonObject("fragment").get("packets").getAsString()
        );
    }


    @Test
    public void classicFragmentUsesAFreedomDialer() {
        ProxyAdvanced a = new ProxyAdvanced();
        a.fragmentMode = ProxyAdvanced.FragmentClassic;
        a.fragmentPackets = "1-3";
        a.fragmentLength = "10-20";
        a.fragmentInterval = "5-6";
        a.keepAliveIdle = 30;
        a.keepAliveInterval = 10;
        a.tcpFastOpen = true;
        a.tcpMaxSeg = 1300;
        a.dnsMode = ProxyAdvanced.DnsGoogle;
        String config = XrayConfigBuilder.build(server(), Inbounds, a);
        JsonObject proxy = outboundOf(config, 0);
        JsonObject freedom = outboundOf(config, 1);
        assertEquals("proxy", proxy.get("tag").getAsString());
        assertEquals("fragment", sockopt(proxy).get("dialerProxy").getAsString());
        assertEquals(1, sockopt(proxy).size());
        assertEquals(
            JsonParser.parseString("{\"packets\":\"1-3\",\"length\":\"10-20\",\"interval\":\"5-6\"}"),
            freedom.getAsJsonObject("settings").get("fragment")
        );
        assertEquals("fragment", freedom.get("tag").getAsString());
        assertEquals("freedom", freedom.get("protocol").getAsString());
        assertEquals("UseIPv4", freedom.getAsJsonObject("settings").get("domainStrategy").getAsString());
        JsonObject options = sockopt(freedom);
        assertEquals(30, options.get("tcpKeepAliveIdle").getAsInt());
        assertEquals(10, options.get("tcpKeepAliveInterval").getAsInt());
        assertTrue(options.get("tcpFastOpen").getAsBoolean());
        assertEquals(1300, options.get("tcpMaxSeg").getAsInt());
        assertFalse(proxy.getAsJsonObject("streamSettings").has("finalmask"));
    }


    @Test
    public void finalMaskFragmentIsOnTheProxyStream() {
        ProxyAdvanced a = new ProxyAdvanced();
        a.fragmentMode = ProxyAdvanced.FragmentFinalMask;
        String config = XrayConfigBuilder.build(server(), Inbounds, a);
        assertEquals(1, JsonParser.parseString(config).getAsJsonObject().getAsJsonArray("outbounds").size());
        assertEquals(
            JsonParser.parseString(
                "{\"tcp\":[{\"type\":\"fragment\",\"settings\":{\"packets\":\"tlshello\","
                    + "\"lengths\":[\"100-200\"],\"delays\":[\"10-20\"]}}]}"
            ),
            outboundOf(config, 0).getAsJsonObject("streamSettings").get("finalmask")
        );
    }


    @Test
    public void fingerprintOverridesTlsAndReality() {
        ProxyAdvanced a = new ProxyAdvanced();
        a.fingerprint = "firefox";
        JsonObject tls = outboundOf(XrayConfigBuilder.pingConfig(
            serverWith("{\"network\":\"tcp\",\"security\":\"tls\",\"tlsSettings\":{\"fingerprint\":\"chrome\"}}", null, null),
            a
        ), 0).getAsJsonObject("streamSettings");
        assertEquals("firefox", tls.getAsJsonObject("tlsSettings").get("fingerprint").getAsString());
        assertFalse(tls.has("realitySettings"));
        JsonObject reality = outboundOf(XrayConfigBuilder.build(
            serverWith("{\"network\":\"tcp\",\"security\":\"reality\",\"realitySettings\":{\"serverName\":\"s\"}}", null, null),
            Inbounds,
            a
        ), 0).getAsJsonObject("streamSettings");
        assertEquals("firefox", reality.getAsJsonObject("realitySettings").get("fingerprint").getAsString());
        assertFalse(reality.has("tlsSettings"));
    }


    @Test
    public void muxIsAddedExceptForVision() {
        ProxyAdvanced a = new ProxyAdvanced();
        a.muxEnabled = true;
        a.muxConcurrency = 4;
        JsonObject plain = outboundOf(XrayConfigBuilder.build(server(), Inbounds, a), 0);
        assertEquals(JsonParser.parseString("{\"enabled\":true,\"concurrency\":4}"), plain.get("mux"));
        ProxyServer vision = serverWith("{\"network\":\"tcp\"}", "xtls-rprx-vision", null);
        assertFalse(outboundOf(XrayConfigBuilder.build(vision, Inbounds, a), 0).has("mux"));
        assertFalse(outboundOf(XrayConfigBuilder.pingConfig(vision, a), 0).has("mux"));
        ProxyServer visionUdp = serverWith("{\"network\":\"tcp\"}", "xtls-rprx-vision-udp443", null);
        assertFalse(outboundOf(XrayConfigBuilder.build(visionUdp, Inbounds, a), 0).has("mux"));
    }


    @Test
    public void tcpOptionsGoOnTheProxySockoptWithoutClassicFragment() {
        ProxyAdvanced a = new ProxyAdvanced();
        a.keepAliveIdle = 20;
        a.tcpMaxSeg = 1200;
        JsonObject options = sockopt(outboundOf(XrayConfigBuilder.build(server(), Inbounds, a), 0));
        assertEquals(20, options.get("tcpKeepAliveIdle").getAsInt());
        assertEquals(1200, options.get("tcpMaxSeg").getAsInt());
        assertFalse(options.has("tcpKeepAliveInterval"));
        assertFalse(options.has("tcpFastOpen"));
        a.fragmentMode = ProxyAdvanced.FragmentFinalMask;
        JsonObject masked = sockopt(outboundOf(XrayConfigBuilder.build(server(), Inbounds, a), 0));
        assertEquals(20, masked.get("tcpKeepAliveIdle").getAsInt());
    }


    @Test
    public void dnsAddsTheTopLevelObjectAndDomainStrategy() {
        ProxyAdvanced a = new ProxyAdvanced();
        a.dnsMode = ProxyAdvanced.DnsCustom;
        a.dnsCustom = "8.8.4.4";
        String expected = "{\"servers\":[\"8.8.4.4\"],\"queryStrategy\":\"UseIPv4\"}";
        String built = XrayConfigBuilder.build(server(), Inbounds, a);
        String ping = XrayConfigBuilder.pingConfig(server(), a);
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(built).getAsJsonObject().get("dns"));
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(ping).getAsJsonObject().get("dns"));
        assertEquals("UseIPv4", sockopt(outboundOf(built, 0)).get("domainStrategy").getAsString());
        assertFalse(JsonParser.parseString(XrayConfigBuilder.build(server(), Inbounds)).getAsJsonObject().has("dns"));
    }


    @Test
    public void linkFragmentIsHonouredWhenModeIsOffAndFromLinkIsOn() {
        ProxyServer s = serverWith("{\"network\":\"tcp\"}", null, LinkBase + "1-10%2C5-20%2Ctlshello%2C3#name");
        String config = XrayConfigBuilder.build(s, Inbounds, new ProxyAdvanced());
        assertEquals("fragment", sockopt(outboundOf(config, 0)).get("dialerProxy").getAsString());
        assertEquals(
            JsonParser.parseString("{\"packets\":\"tlshello\",\"length\":\"1-10\",\"interval\":\"5-20\"}"),
            outboundOf(config, 1).getAsJsonObject("settings").get("fragment")
        );
        String plainComma = XrayConfigBuilder.pingConfig(
            serverWith("{\"network\":\"tcp\"}", null, LinkBase + "1-10,5-20,tlshello"),
            new ProxyAdvanced()
        );
        assertEquals(2, JsonParser.parseString(plainComma).getAsJsonObject().getAsJsonArray("outbounds").size());
    }


    @Test
    public void linkFragmentIsIgnoredWhenFromLinkIsOff() {
        ProxyServer s = serverWith("{\"network\":\"tcp\"}", null, LinkBase + "1-10,5-20,tlshello");
        ProxyAdvanced a = new ProxyAdvanced();
        a.fragmentFromLink = false;
        assertEquals(
            XrayConfigBuilder.build(serverWith("{\"network\":\"tcp\"}", null, null), Inbounds),
            XrayConfigBuilder.build(s, Inbounds, a)
        );
    }


    @Test
    public void globalModeWinsOverTheLinkFragment() {
        ProxyServer s = serverWith("{\"network\":\"tcp\"}", null, LinkBase + "1-10,5-20,tlshello");
        ProxyAdvanced a = new ProxyAdvanced();
        a.fragmentMode = ProxyAdvanced.FragmentFinalMask;
        String config = XrayConfigBuilder.build(s, Inbounds, a);
        assertEquals(1, JsonParser.parseString(config).getAsJsonObject().getAsJsonArray("outbounds").size());
        assertTrue(outboundOf(config, 0).getAsJsonObject("streamSettings").has("finalmask"));
    }


    @Test
    public void invalidLinkFragmentIsIgnored() {
        String[] bad = {"abc", "1-10,5-20", "10-1,5-20,tlshello", "1-10,x,tlshello", "1-10,5-20,nope", ""};
        for (String value : bad) {
            ProxyServer s = serverWith("{\"network\":\"tcp\"}", null, LinkBase + value);
            assertEquals(
                value,
                1,
                JsonParser.parseString(XrayConfigBuilder.build(s, Inbounds, new ProxyAdvanced())).getAsJsonObject()
                    .getAsJsonArray("outbounds").size()
            );
        }
    }


    @Test
    public void pingConfigCarriesTheSameTransformations() {
        ProxyAdvanced a = new ProxyAdvanced();
        a.fragmentMode = ProxyAdvanced.FragmentClassic;
        a.fingerprint = "edge";
        a.muxEnabled = true;
        ProxyServer s = serverWith("{\"network\":\"tcp\",\"security\":\"tls\",\"tlsSettings\":{}}", null, null);
        JsonObject config = JsonParser.parseString(XrayConfigBuilder.pingConfig(s, a)).getAsJsonObject();
        assertEquals(2, config.getAsJsonArray("outbounds").size());
        assertFalse(config.has("inbounds"));
        JsonObject proxy = outboundOf(XrayConfigBuilder.pingConfig(s, a), 0);
        assertEquals("fragment", sockopt(proxy).get("dialerProxy").getAsString());
        assertEquals("edge", proxy.getAsJsonObject("streamSettings").getAsJsonObject("tlsSettings").get("fingerprint").getAsString());
        assertTrue(proxy.getAsJsonObject("mux").get("enabled").getAsBoolean());
    }


    @Test
    public void storedOutboundIsNotMutated() {
        ProxyServer s = server();
        String before = s.outboundJson;
        ProxyAdvanced a = new ProxyAdvanced();
        a.fragmentMode = ProxyAdvanced.FragmentClassic;
        a.muxEnabled = true;
        a.fingerprint = "chrome";
        XrayConfigBuilder.build(s, Inbounds, a);
        assertEquals(before, s.outboundJson);
        assertEquals(before, s.outbound().toString());
    }
}
