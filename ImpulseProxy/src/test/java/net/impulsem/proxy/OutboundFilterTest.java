package net.impulsem.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.Test;


public class OutboundFilterTest {

    private static final String VnextReality =
        "{\"protocol\":\"vless\",\"tag\":\"NL Amsterdam\",\"settings\":{\"vnext\":[{\"address\":\"nl.example.com\",\"port\":443,"
        + "\"users\":[{\"id\":\"11111111-1111-1111-1111-111111111111\",\"flow\":\"xtls-rprx-vision\",\"encryption\":\"none\"}]}]},"
        + "\"streamSettings\":{\"network\":\"tcp\",\"security\":\"reality\",\"realitySettings\":{\"publicKey\":\"pk\",\"shortId\":\"ab\",\"serverName\":\"www.example.com\"}}}";

    private static final String FlatWs =
        "{\"protocol\":\"vless\",\"tag\":\"\",\"settings\":{\"address\":\"ws.example.com\",\"port\":8443,\"id\":\"22222222-2222-2222-2222-222222222222\"},"
        + "\"streamSettings\":{\"network\":\"ws\",\"security\":\"tls\"}}";

    private static final String Vmess = "{\"protocol\":\"vmess\",\"tag\":\"vm\",\"settings\":{}}";

    private static final String Trojan = "{\"protocol\":\"trojan\",\"tag\":\"tr\",\"settings\":{}}";


    @Test
    public void mixedSchemesKeepOnlyVlessAndCountSkipped() {
        JsonArray outbounds = JsonParser.parseString("[" + VnextReality + "," + Vmess + "," + FlatWs + "," + Trojan + "]").getAsJsonArray();
        OutboundFilter.Result result = OutboundFilter.filter(outbounds);
        assertEquals(2, result.servers.size());
        assertEquals(2, result.skipped);
    }


    @Test
    public void readsVnextShape() {
        ProxyServer server = OutboundFilter.filter(JsonParser.parseString("[" + VnextReality + "]").getAsJsonArray()).servers.get(0);
        assertEquals("NL Amsterdam", server.name);
        assertEquals("nl.example.com", server.host);
        assertEquals(443, server.port);
        assertEquals("tcp", server.network);
        assertEquals("reality", server.security);
    }


    @Test
    public void readsFlatShapeAndFallsBackToHostForName() {
        ProxyServer server = OutboundFilter.filter(JsonParser.parseString("[" + FlatWs + "]").getAsJsonArray()).servers.get(0);
        assertEquals("ws.example.com", server.name);
        assertEquals(8443, server.port);
        assertEquals("ws", server.network);
        assertEquals("tls", server.security);
    }


    @Test
    public void idIsStableAndDistinct() {
        JsonArray outbounds = JsonParser.parseString("[" + VnextReality + "," + FlatWs + "]").getAsJsonArray();
        OutboundFilter.Result first = OutboundFilter.filter(outbounds);
        OutboundFilter.Result second = OutboundFilter.filter(JsonParser.parseString("[" + VnextReality + "," + FlatWs + "]").getAsJsonArray());
        assertEquals(first.servers.get(0).id, second.servers.get(0).id);
        assertNotEquals(first.servers.get(0).id, first.servers.get(1).id);
    }


    @Test
    public void missingHostIsSkipped() {
        JsonArray outbounds = JsonParser.parseString("[{\"protocol\":\"vless\",\"settings\":{}}]").getAsJsonArray();
        OutboundFilter.Result result = OutboundFilter.filter(outbounds);
        assertEquals(0, result.servers.size());
        assertEquals(1, result.skipped);
    }
}
