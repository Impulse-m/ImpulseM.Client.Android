package net.impulsem.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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


    @Test
    public void transportOffIsDirectEvenWhenEnabledAndRunning() {
        assertEquals(ProxyRouting.Route.DIRECT, ProxyRouting.decide(true, false, true));
        assertEquals(ProxyRouting.Route.DIRECT, ProxyRouting.decide(true, false, false));
        assertEquals(ProxyRouting.Route.DIRECT, ProxyRouting.decide(false, false, true));
    }


    @Test
    public void transportOnKeepsTheKillSwitch() {
        assertEquals(ProxyRouting.Route.PROXY, ProxyRouting.decide(true, true, true));
        assertEquals(ProxyRouting.Route.BLOCKED, ProxyRouting.decide(true, true, false));
        assertEquals(ProxyRouting.Route.DIRECT, ProxyRouting.decide(false, true, true));
    }


    @Test
    public void callsAreTunnelledWheneverEnabledAndUseForCalls() {
        assertTrue(ProxyRouting.tunnelCalls(true, true, true));
    }


    @Test
    public void callsAreTunnelledRegardlessOfCoreState() {
        // tunnelCalls takes no running flag: a down core (BLOCKED route) must not turn a tunnelled call into a direct one.
        assertEquals(ProxyRouting.Route.BLOCKED, ProxyRouting.decide(true, false));
        assertTrue(ProxyRouting.tunnelCalls(true, true, true));
    }


    @Test
    public void callsAreDirectWhenAnyGateIsOff() {
        assertFalse(ProxyRouting.tunnelCalls(false, true, true));
        assertFalse(ProxyRouting.tunnelCalls(true, false, true));
        assertFalse(ProxyRouting.tunnelCalls(true, true, false));
        assertFalse(ProxyRouting.tunnelCalls(false, false, false));
    }
}
