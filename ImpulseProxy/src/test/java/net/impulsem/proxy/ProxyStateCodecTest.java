package net.impulsem.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
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
}
