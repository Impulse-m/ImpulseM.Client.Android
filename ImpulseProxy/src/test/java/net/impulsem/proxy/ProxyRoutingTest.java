package net.impulsem.proxy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;


public class ProxyRoutingTest {

    @Test
    public void offIsDirect() {
        assertEquals(ProxyRouting.Route.DIRECT, ProxyRouting.decide(false, false));
        assertEquals(ProxyRouting.Route.DIRECT, ProxyRouting.decide(false, true));
    }


    @Test
    public void onAndRunningIsProxy() {
        assertEquals(ProxyRouting.Route.PROXY, ProxyRouting.decide(true, true));
    }


    @Test
    public void onButNotRunningIsBlockedNeverDirect() {
        assertEquals(ProxyRouting.Route.BLOCKED, ProxyRouting.decide(true, false));
    }
}
