package net.impulsem.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;


public class ProxyStateTest {

    private static ProxyServer server(String id) {
        return new ProxyServer(id, "n" + id, "h" + id, 443, "tcp", "none", "{\"protocol\":\"vless\"}");
    }


    private static Subscription subscription(String id, ProxyServer... servers) {
        return new Subscription(
            id,
            "https://x/" + id,
            SubscriptionMeta.parse(null, null, null),
            0L,
            null,
            0,
            Arrays.asList(servers)
        );
    }


    @Test
    public void addManualDeduplicates() {
        ProxyState state = new ProxyState();
        state.addManual(Arrays.asList(server("a"), server("a"), server("b")));
        assertEquals(2, state.manual.size());
    }


    @Test
    public void refreshKeepsSelectionWhenStillPresent() {
        ProxyState state = new ProxyState();
        state.replaceSubscription(subscription("s", server("a"), server("b")));
        state.selectedId = "b";
        state.replaceSubscription(subscription("s", server("c"), server("b")));
        assertEquals("b", state.selectedId);
    }


    @Test
    public void refreshDropsSelectedFallsBackToFirstOfSameSubscription() {
        ProxyState state = new ProxyState();
        state.replaceSubscription(subscription("s", server("a"), server("b")));
        state.selectedId = "b";
        state.replaceSubscription(subscription("s", server("c"), server("d")));
        assertEquals("c", state.selectedId);
    }


    @Test
    public void refreshToEmptyClearsSelection() {
        ProxyState state = new ProxyState();
        state.replaceSubscription(subscription("s", server("a")));
        state.selectedId = "a";
        state.replaceSubscription(new Subscription(
            "s",
            "https://x/s",
            SubscriptionMeta.parse(null, null, null),
            0L,
            null,
            0,
            Collections.<ProxyServer>emptyList()
        ));
        assertNull(state.selectedId);
        assertNull(state.selected());
    }


    @Test
    public void removingSelectedServerClearsSelection() {
        ProxyState state = new ProxyState();
        state.addManual(Arrays.asList(server("a")));
        state.selectedId = "a";
        state.removeServer("a");
        assertNull(state.selectedId);
    }
}
