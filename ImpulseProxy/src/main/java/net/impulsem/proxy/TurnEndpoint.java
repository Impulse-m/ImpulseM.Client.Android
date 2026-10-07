package net.impulsem.proxy;

import java.util.Locale;


/** A plain TURN URL (RFC 7065). turns:/stun: are not forwarded. */
public final class TurnEndpoint {
    public static final int DefaultPort = 3478;

    public final String host;
    public final int port;
    public final boolean tcp;


    private TurnEndpoint(
        String host,
        int port,
        boolean tcp
    ) {
        this.host = host;
        this.port = port;
        this.tcp = tcp;
    }


    public static TurnEndpoint parse(String url) {
        if (url == null || !url.toLowerCase(Locale.ROOT).startsWith("turn:")) {
            return null;
        }
        String rest = url.substring("turn:".length());
        boolean tcp = false;
        int query = rest.indexOf('?');
        if (query >= 0) {
            tcp = rest.substring(query + 1).toLowerCase(Locale.ROOT).contains("transport=tcp");
            rest = rest.substring(0, query);
        }
        String host;
        String portText = null;
        if (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            if (close < 0) {
                return null;
            }
            host = rest.substring(1, close);
            if (close + 1 < rest.length()) {
                if (rest.charAt(close + 1) != ':') {
                    return null;
                }
                portText = rest.substring(close + 2);
            }
        } else {
            int colon = rest.lastIndexOf(':');
            host = colon >= 0 ? rest.substring(0, colon) : rest;
            portText = colon >= 0 ? rest.substring(colon + 1) : null;
        }
        if (host.isEmpty()) {
            return null;
        }
        int port = DefaultPort;
        if (portText != null) {
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                return null;
            }
            if (port <= 0 || port > 65535) {
                return null;
            }
        }
        return new TurnEndpoint(host, port, tcp);
    }


    public String localUrl(int localPort) {
        return "turn:127.0.0.1:" + localPort + "?transport=" + (tcp ? "tcp" : "udp");
    }
}
