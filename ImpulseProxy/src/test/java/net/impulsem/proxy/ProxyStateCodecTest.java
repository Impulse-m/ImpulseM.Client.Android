package net.impulsem.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;


public class ProxyStateCodecTest {

    @Test
    public void roundTrip() {
        ProxyState state = new ProxyState();
        state.enabled = true;
        state.useForCalls = true;
        state.addManual(Arrays.asList(
            new ProxyServer("a", "A", "ha", 443, "ws", "tls", "{\"protocol\":\"vless\",\"tag\":\"A\"}")
        ));
        state.replaceSubscription(new Subscription(
            "s",
            "https://panel/sub",
            SubscriptionMeta.parse("T", "upload=1; download=2; total=3; expire=4", "6"),
            1790000000000L,
            "err",
            2,
            Arrays.asList(
                new ProxyServer("b", "B", "hb", 8443, "grpc", "reality", "{\"protocol\":\"vless\",\"tag\":\"B\"}")
            )
        ));
        state.selectedId = "b";
        ProxyState decoded = ProxyStateCodec.decode(ProxyStateCodec.encode(state));
        assertTrue(decoded.enabled);
        assertTrue(decoded.useForCalls);
        assertEquals("b", decoded.selectedId);
        assertEquals("A", decoded.manual.get(0).name);
        Subscription subscription = decoded.subscriptions.get(0);
        assertEquals("https://panel/sub", subscription.url);
        assertEquals("T", subscription.meta.title);
        assertEquals(3L, subscription.meta.total);
        assertEquals(6, subscription.meta.updateIntervalHours);
        assertEquals(2, subscription.skipped);
        assertEquals("err", subscription.lastError);
        assertEquals("reality", subscription.servers.get(0).security);
        assertEquals("{\"protocol\":\"vless\",\"tag\":\"B\"}", subscription.servers.get(0).outboundJson);
    }


    @Test
    public void corruptInputGivesEmptyState() {
        ProxyState state = ProxyStateCodec.decode("{not json");
        assertFalse(state.enabled);
        assertEquals(0, state.allServers().size());
        assertEquals(0, ProxyStateCodec.decode(null).allServers().size());
    }


    @Test
    public void malformedManualServerIsSkippedAndKillSwitchSurvives() {
        String json = "{\"v\":1,\"enabled\":true,\"useForCalls\":true,\"selectedId\":\"a\","
            + "\"manual\":["
            + "{\"id\":\"a\",\"name\":\"A\",\"host\":\"ha\",\"port\":443,\"network\":\"tcp\",\"security\":\"none\","
            + "\"outbound\":\"{}\"},"
            + "{\"id\":\"b\",\"name\":\"B\",\"port\":443,\"network\":\"tcp\",\"security\":\"none\",\"outbound\":\"{}\"}"
            + "]}";
        ProxyState decoded = ProxyStateCodec.decode(json);
        assertTrue(decoded.enabled);
        assertTrue(decoded.useForCalls);
        assertEquals("a", decoded.selectedId);
        assertEquals(1, decoded.manual.size());
        assertEquals("a", decoded.manual.get(0).id);
    }


    @Test
    public void malformedServerInsideSubscriptionKeepsTheSubscription() {
        String json = "{\"enabled\":true,\"subscriptions\":[{\"id\":\"s\",\"url\":\"https://x/s\",\"updatedAt\":5,"
            + "\"skipped\":0,\"meta\":{\"upload\":-1,\"download\":-1,\"total\":-1,\"expire\":-1,\"interval\":24},"
            + "\"servers\":["
            + "{\"id\":\"ok\",\"name\":\"OK\",\"host\":\"h\",\"port\":443,\"network\":\"tcp\",\"security\":\"none\","
            + "\"outbound\":\"{}\"},"
            + "{\"id\":\"bad\",\"name\":\"Bad\",\"port\":443,\"network\":\"tcp\",\"security\":\"none\","
            + "\"outbound\":\"{}\"}"
            + "]}]}";
        ProxyState decoded = ProxyStateCodec.decode(json);
        assertTrue(decoded.enabled);
        assertEquals(1, decoded.subscriptions.size());
        assertEquals(1, decoded.subscriptions.get(0).servers.size());
        assertEquals("ok", decoded.subscriptions.get(0).servers.get(0).id);
    }


    @Test
    public void subscriptionWithoutIdOrUrlIsSkipped() {
        String json = "{\"subscriptions\":[{\"url\":\"https://x/s\",\"servers\":[]},{\"id\":\"t\",\"servers\":[]}]}";
        assertEquals(0, ProxyStateCodec.decode(json).subscriptions.size());
    }


    @Test
    public void unusableRootGivesEmptyState() {
        assertEquals(0, ProxyStateCodec.decode("[]").allServers().size());
        assertEquals(0, ProxyStateCodec.decode("").allServers().size());
        assertFalse(ProxyStateCodec.decode("{}").enabled);
        assertEquals(0, ProxyStateCodec.decode("{}").allServers().size());
    }


    @Test
    public void enabledOnlyJsonKeepsTheFlag() {
        ProxyState decoded = ProxyStateCodec.decode("{\"enabled\":true}");
        assertTrue(decoded.enabled);
        assertEquals(0, decoded.allServers().size());
    }


    @Test
    public void nullMetaEncodesAsDefaults() {
        ProxyState state = new ProxyState();
        state.replaceSubscription(new Subscription(
            "s",
            "https://x/s",
            null,
            0L,
            null,
            0,
            Collections.<ProxyServer>emptyList()
        ));
        Subscription decoded = ProxyStateCodec.decode(ProxyStateCodec.encode(state)).subscriptions.get(0);
        assertNull(decoded.meta.title);
        assertEquals(-1L, decoded.meta.upload);
        assertEquals(-1L, decoded.meta.expire);
        assertEquals(24, decoded.meta.updateIntervalHours);
    }
}
