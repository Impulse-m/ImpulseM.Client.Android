package net.impulsem.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;


public class ProxyAdvancedTest {

    @Test
    public void defaults() {
        ProxyAdvanced a = new ProxyAdvanced();
        assertEquals("off", a.fragmentMode);
        assertEquals("tlshello", a.fragmentPackets);
        assertEquals("100-200", a.fragmentLength);
        assertEquals("10-20", a.fragmentInterval);
        assertTrue(a.fragmentFromLink);
        assertEquals("", a.fingerprint);
        assertFalse(a.muxEnabled);
        assertEquals(8, a.muxConcurrency);
        assertEquals(0, a.keepAliveIdle);
        assertEquals(0, a.keepAliveInterval);
        assertFalse(a.tcpFastOpen);
        assertEquals(0, a.tcpMaxSeg);
        assertEquals("system", a.dnsMode);
        assertEquals("", a.dnsCustom);
        assertEquals("batch", a.pingMode);
        assertEquals(0, a.pingPauseMillis);
        assertEquals(5, a.pingTimeoutSeconds);
        assertEquals("https://www.gstatic.com/generate_204", a.pingUrl);
        assertFalse(a.pingSkipWhileConnected);
        assertNull(a.effectiveDnsServer());
    }


    @Test
    public void copyAndEquals() {
        ProxyAdvanced a = new ProxyAdvanced();
        a.fragmentMode = ProxyAdvanced.FragmentClassic;
        a.fingerprint = "chrome";
        a.muxEnabled = true;
        a.pingUrl = "https://example.com/x";
        ProxyAdvanced b = a.copy();
        assertNotSame(a, b);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        b.tcpMaxSeg = 1200;
        assertFalse(a.equals(b));
        assertEquals(0, a.tcpMaxSeg);
        assertEquals(new ProxyAdvanced(), new ProxyAdvanced());
    }


    @Test
    public void affectsCoreIgnoresPingFieldsOnly() {
        ProxyAdvanced a = new ProxyAdvanced();
        ProxyAdvanced b = a.copy();
        b.pingMode = ProxyAdvanced.PingSequential;
        b.pingPauseMillis = 500;
        b.pingTimeoutSeconds = 9;
        b.pingUrl = "https://example.com/p";
        b.pingSkipWhileConnected = true;
        assertFalse(a.affectsCore(b));
        b.fragmentMode = ProxyAdvanced.FragmentClassic;
        assertTrue(a.affectsCore(b));
        ProxyAdvanced c = a.copy();
        c.dnsMode = ProxyAdvanced.DnsGoogle;
        assertTrue(a.affectsCore(c));
        ProxyAdvanced d = a.copy();
        d.muxEnabled = true;
        assertTrue(a.affectsCore(d));
        ProxyAdvanced e = a.copy();
        e.fingerprint = "chrome";
        assertTrue(a.affectsCore(e));
    }


    @Test
    public void sanitizeKeepsValidValues() {
        ProxyAdvanced a = new ProxyAdvanced();
        a.fragmentMode = ProxyAdvanced.FragmentFinalMask;
        a.fragmentPackets = "1-3";
        a.fingerprint = "firefox";
        a.tcpMaxSeg = 1460;
        a.dnsMode = ProxyAdvanced.DnsCustom;
        a.dnsCustom = "9.9.9.9";
        ProxyAdvanced before = a.copy();
        a.sanitize();
        assertEquals(before, a);
    }


    @Test
    public void sanitizeResetsInvalidFields() {
        ProxyAdvanced a = new ProxyAdvanced();
        a.fragmentMode = "weird";
        a.fragmentPackets = "x";
        a.fragmentLength = "200-100";
        a.fragmentInterval = "a-b";
        a.fingerprint = "netscape";
        a.muxConcurrency = 129;
        a.keepAliveIdle = -1;
        a.keepAliveInterval = -5;
        a.tcpMaxSeg = 63;
        a.dnsMode = ProxyAdvanced.DnsCustom;
        a.dnsCustom = "not a server";
        a.pingMode = "zzz";
        a.pingPauseMillis = 10001;
        a.pingTimeoutSeconds = 31;
        a.pingUrl = "http://insecure.example";
        a.sanitize();
        assertEquals(new ProxyAdvanced(), a);
    }


    @Test
    public void sanitizeBoundsEachNumber() {
        ProxyAdvanced a = new ProxyAdvanced();
        a.muxConcurrency = 0;
        a.tcpMaxSeg = 1461;
        a.pingPauseMillis = -1;
        a.pingTimeoutSeconds = 0;
        a.sanitize();
        assertEquals(8, a.muxConcurrency);
        assertEquals(0, a.tcpMaxSeg);
        assertEquals(0, a.pingPauseMillis);
        assertEquals(5, a.pingTimeoutSeconds);
        a.muxConcurrency = 128;
        a.tcpMaxSeg = 64;
        a.pingPauseMillis = 10000;
        a.pingTimeoutSeconds = 30;
        a.sanitize();
        assertEquals(128, a.muxConcurrency);
        assertEquals(64, a.tcpMaxSeg);
        assertEquals(10000, a.pingPauseMillis);
        assertEquals(30, a.pingTimeoutSeconds);
    }


    @Test
    public void effectiveDnsServer() {
        ProxyAdvanced a = new ProxyAdvanced();
        a.dnsMode = ProxyAdvanced.DnsCloudflare;
        assertEquals(ProxyAdvanced.CloudflareDoh, a.effectiveDnsServer());
        a.dnsMode = ProxyAdvanced.DnsGoogle;
        assertEquals(ProxyAdvanced.GoogleDoh, a.effectiveDnsServer());
        a.dnsMode = ProxyAdvanced.DnsCustom;
        a.dnsCustom = "https://dns.example/dns-query";
        assertEquals("https://dns.example/dns-query", a.effectiveDnsServer());
        a.dnsCustom = "";
        assertNull(a.effectiveDnsServer());
    }


    @Test
    public void isRangeCases() {
        assertTrue(ProxyAdvanced.isRange("0"));
        assertTrue(ProxyAdvanced.isRange("5"));
        assertTrue(ProxyAdvanced.isRange("1-3"));
        assertTrue(ProxyAdvanced.isRange("7-7"));
        assertTrue(ProxyAdvanced.isRange("0-65535"));
        assertFalse(ProxyAdvanced.isRange(null));
        assertFalse(ProxyAdvanced.isRange(""));
        assertFalse(ProxyAdvanced.isRange("-"));
        assertFalse(ProxyAdvanced.isRange("3-1"));
        assertFalse(ProxyAdvanced.isRange("1-"));
        assertFalse(ProxyAdvanced.isRange("-1"));
        assertFalse(ProxyAdvanced.isRange("65536"));
        assertFalse(ProxyAdvanced.isRange("1-2-3"));
        assertFalse(ProxyAdvanced.isRange("a"));
        assertFalse(ProxyAdvanced.isRange("1 - 2"));
        assertFalse(ProxyAdvanced.isRange("99999999999"));
        assertFalse(ProxyAdvanced.isRange("tlshello"));
    }


    @Test
    public void isPacketsAcceptsTlsHelloOrRange() {
        assertTrue(ProxyAdvanced.isPackets("tlshello"));
        assertTrue(ProxyAdvanced.isPackets("1-3"));
        assertFalse(ProxyAdvanced.isPackets("TLSHELLO"));
        assertFalse(ProxyAdvanced.isPackets(""));
    }
}
