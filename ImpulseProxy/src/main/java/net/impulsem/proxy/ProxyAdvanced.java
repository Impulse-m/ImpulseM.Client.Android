package net.impulsem.proxy;

import java.util.Arrays;
import java.util.List;


/**
 * Advanced proxy settings: fragmentation, fingerprint, mux, TCP options, DNS and ping pacing.
 * A plain mutable object; the defaults reproduce the behaviour before these settings existed.
 */
public final class ProxyAdvanced {
    public static final String FragmentOff = "off";
    public static final String FragmentClassic = "classic";
    public static final String FragmentFinalMask = "finalmask";

    public static final List<String> Fingerprints = Arrays.asList(
        "chrome",
        "firefox",
        "safari",
        "ios",
        "android",
        "edge",
        "360",
        "qq",
        "random",
        "randomized"
    );

    public static final String DnsSystem = "system";
    public static final String DnsCloudflare = "cloudflare";
    public static final String DnsGoogle = "google";
    public static final String DnsCustom = "custom";
    public static final String CloudflareDoh = "https://1.1.1.1/dns-query";
    public static final String GoogleDoh = "https://8.8.8.8/dns-query";

    public static final String PingBatch = "batch";
    public static final String PingSequential = "sequential";

    public static final String DefaultFragmentPackets = "tlshello";
    public static final String DefaultFragmentLength = "100-200";
    public static final String DefaultFragmentInterval = "10-20";
    public static final int DefaultMuxConcurrency = 8;
    public static final int DefaultPingTimeoutSeconds = 5;
    public static final String DefaultPingUrl = "https://www.gstatic.com/generate_204";

    public String fragmentMode = FragmentOff;
    /** "tlshello" or an Int32Range such as "1-3". */
    public String fragmentPackets = DefaultFragmentPackets;
    /** Int32Range, bytes. */
    public String fragmentLength = DefaultFragmentLength;
    /** Int32Range, milliseconds. */
    public String fragmentInterval = DefaultFragmentInterval;
    /** Honour a Happ-style fragment= link parameter when fragmentMode is off. */
    public boolean fragmentFromLink = true;

    /** Empty means "as in the link"; otherwise one of Fingerprints. */
    public String fingerprint = "";

    public boolean muxEnabled = false;
    /** 1..128. */
    public int muxConcurrency = DefaultMuxConcurrency;

    /** Seconds, 0 = system. */
    public int keepAliveIdle = 0;
    /** Seconds, 0 = system. */
    public int keepAliveInterval = 0;
    public boolean tcpFastOpen = false;
    /** 0 = system, otherwise 64..1460. */
    public int tcpMaxSeg = 0;

    public String dnsMode = DnsSystem;
    /** An https:// DoH URL or an IPv4 address. */
    public String dnsCustom = "";

    public String pingMode = PingBatch;
    /** 0..10000, pause between ping chunks. */
    public int pingPauseMillis = 0;
    /** 1..30. */
    public int pingTimeoutSeconds = DefaultPingTimeoutSeconds;
    /** https:// only. */
    public String pingUrl = DefaultPingUrl;
    public boolean pingSkipWhileConnected = true;


    public ProxyAdvanced copy() {
        ProxyAdvanced other = new ProxyAdvanced();
        other.fragmentMode = fragmentMode;
        other.fragmentPackets = fragmentPackets;
        other.fragmentLength = fragmentLength;
        other.fragmentInterval = fragmentInterval;
        other.fragmentFromLink = fragmentFromLink;
        other.fingerprint = fingerprint;
        other.muxEnabled = muxEnabled;
        other.muxConcurrency = muxConcurrency;
        other.keepAliveIdle = keepAliveIdle;
        other.keepAliveInterval = keepAliveInterval;
        other.tcpFastOpen = tcpFastOpen;
        other.tcpMaxSeg = tcpMaxSeg;
        other.dnsMode = dnsMode;
        other.dnsCustom = dnsCustom;
        other.pingMode = pingMode;
        other.pingPauseMillis = pingPauseMillis;
        other.pingTimeoutSeconds = pingTimeoutSeconds;
        other.pingUrl = pingUrl;
        other.pingSkipWhileConnected = pingSkipWhileConnected;
        return other;
    }


    /** True when any field other than the ping* ones differs, so the running core must restart. */
    public boolean affectsCore(ProxyAdvanced other) {
        if (other == null) {
            return true;
        }
        ProxyAdvanced a = copy();
        ProxyAdvanced b = other.copy();
        b.pingMode = a.pingMode;
        b.pingPauseMillis = a.pingPauseMillis;
        b.pingTimeoutSeconds = a.pingTimeoutSeconds;
        b.pingUrl = a.pingUrl;
        b.pingSkipWhileConnected = a.pingSkipWhileConnected;
        return !a.equals(b);
    }


    /** Replaces every invalid field with its default. */
    public void sanitize() {
        if (!FragmentOff.equals(fragmentMode) && !FragmentClassic.equals(fragmentMode) && !FragmentFinalMask.equals(fragmentMode)) {
            fragmentMode = FragmentOff;
        }
        if (!isPackets(fragmentPackets)) {
            fragmentPackets = DefaultFragmentPackets;
        }
        if (!isRange(fragmentLength)) {
            fragmentLength = DefaultFragmentLength;
        }
        if (!isRange(fragmentInterval)) {
            fragmentInterval = DefaultFragmentInterval;
        }
        if (fingerprint == null || (!fingerprint.isEmpty() && !Fingerprints.contains(fingerprint))) {
            fingerprint = "";
        }
        if (muxConcurrency < 1 || muxConcurrency > 128) {
            muxConcurrency = DefaultMuxConcurrency;
        }
        if (keepAliveIdle < 0 || keepAliveIdle > 86400) {
            keepAliveIdle = 0;
        }
        if (keepAliveInterval < 0 || keepAliveInterval > 86400) {
            keepAliveInterval = 0;
        }
        if (tcpMaxSeg != 0 && (tcpMaxSeg < 64 || tcpMaxSeg > 1460)) {
            tcpMaxSeg = 0;
        }
        if (!DnsSystem.equals(dnsMode) && !DnsCloudflare.equals(dnsMode) && !DnsGoogle.equals(dnsMode) && !DnsCustom.equals(dnsMode)) {
            dnsMode = DnsSystem;
        }
        if (dnsCustom == null) {
            dnsCustom = "";
        }
        if (!dnsCustom.isEmpty() && !isDnsServer(dnsCustom)) {
            dnsCustom = "";
        }
        if (DnsCustom.equals(dnsMode) && dnsCustom.isEmpty()) {
            dnsMode = DnsSystem;
        }
        if (!PingBatch.equals(pingMode) && !PingSequential.equals(pingMode)) {
            pingMode = PingBatch;
        }
        if (pingPauseMillis < 0 || pingPauseMillis > 10000) {
            pingPauseMillis = 0;
        }
        if (pingTimeoutSeconds < 1 || pingTimeoutSeconds > 30) {
            pingTimeoutSeconds = DefaultPingTimeoutSeconds;
        }
        if (pingUrl == null || !pingUrl.startsWith("https://") || pingUrl.length() <= "https://".length()) {
            pingUrl = DefaultPingUrl;
        }
    }


    /** The DNS server to use, or null for the system resolver. */
    public String effectiveDnsServer() {
        if (DnsCloudflare.equals(dnsMode)) {
            return CloudflareDoh;
        }
        if (DnsGoogle.equals(dnsMode)) {
            return GoogleDoh;
        }
        if (DnsCustom.equals(dnsMode) && isDnsServer(dnsCustom)) {
            return dnsCustom;
        }
        return null;
    }


    /** Accepts "N" or "N-M" with 0 <= N <= M <= 65535. */
    public static boolean isRange(String value) {
        if (value == null) {
            return false;
        }
        int dash = value.indexOf('-');
        if (dash < 0) {
            return parseBounded(value) >= 0;
        }
        int low = parseBounded(value.substring(0, dash));
        int high = parseBounded(value.substring(dash + 1));
        return low >= 0 && high >= 0 && low <= high;
    }


    /** Accepts "tlshello" or a range. */
    public static boolean isPackets(String value) {
        return DefaultFragmentPackets.equals(value) || isRange(value);
    }


    /** Accepts an https:// URL or an IPv4 address. */
    public static boolean isDnsServer(String value) {
        if (value == null) {
            return false;
        }
        if (value.startsWith("https://")) {
            return value.length() > "https://".length() && value.indexOf(' ') < 0;
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) < '0' || part.charAt(i) > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }


    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof ProxyAdvanced)) {
            return false;
        }
        ProxyAdvanced o = (ProxyAdvanced) object;
        return fragmentFromLink == o.fragmentFromLink
            && muxEnabled == o.muxEnabled
            && muxConcurrency == o.muxConcurrency
            && keepAliveIdle == o.keepAliveIdle
            && keepAliveInterval == o.keepAliveInterval
            && tcpFastOpen == o.tcpFastOpen
            && tcpMaxSeg == o.tcpMaxSeg
            && pingPauseMillis == o.pingPauseMillis
            && pingTimeoutSeconds == o.pingTimeoutSeconds
            && pingSkipWhileConnected == o.pingSkipWhileConnected
            && same(fragmentMode, o.fragmentMode)
            && same(fragmentPackets, o.fragmentPackets)
            && same(fragmentLength, o.fragmentLength)
            && same(fragmentInterval, o.fragmentInterval)
            && same(fingerprint, o.fingerprint)
            && same(dnsMode, o.dnsMode)
            && same(dnsCustom, o.dnsCustom)
            && same(pingMode, o.pingMode)
            && same(pingUrl, o.pingUrl);
    }


    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[] {
            fragmentMode,
            fragmentPackets,
            fragmentLength,
            fragmentInterval,
            fragmentFromLink,
            fingerprint,
            muxEnabled,
            muxConcurrency,
            keepAliveIdle,
            keepAliveInterval,
            tcpFastOpen,
            tcpMaxSeg,
            dnsMode,
            dnsCustom,
            pingMode,
            pingPauseMillis,
            pingTimeoutSeconds,
            pingUrl,
            pingSkipWhileConnected
        });
    }


    private static boolean same(
        String a,
        String b
    ) {
        return a == null ? b == null : a.equals(b);
    }


    /** Parses digits only into 0..65535; -1 when invalid. */
    private static int parseBounded(String text) {
        if (text.isEmpty() || text.length() > 5) {
            return -1;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return -1;
            }
        }
        int value = Integer.parseInt(text);
        return value <= 65535 ? value : -1;
    }
}
