package net.impulsem.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;


public class XrayConfigBuilderTest {

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
}
