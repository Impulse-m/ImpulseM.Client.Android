package net.impulsem.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;


public class TurnEndpointTest {

    @Test
    public void udpWithExplicitPort() {
        TurnEndpoint endpoint = TurnEndpoint.parse("turn:eu.turn.livekit.cloud:3478?transport=udp");
        assertEquals("eu.turn.livekit.cloud", endpoint.host);
        assertEquals(3478, endpoint.port);
        assertFalse(endpoint.tcp);
        assertEquals("turn:127.0.0.1:41000?transport=udp", endpoint.localUrl(41000));
    }


    @Test
    public void tcpAndDefaultPort() {
        TurnEndpoint endpoint = TurnEndpoint.parse("turn:turn.example.com?transport=tcp");
        assertEquals(3478, endpoint.port);
        assertTrue(endpoint.tcp);
        assertEquals("turn:127.0.0.1:41001?transport=tcp", endpoint.localUrl(41001));
    }


    @Test
    public void ipv6Literal() {
        TurnEndpoint endpoint = TurnEndpoint.parse("turn:[2001:db8::1]:3479");
        assertEquals("2001:db8::1", endpoint.host);
        assertEquals(3479, endpoint.port);
        assertFalse(endpoint.tcp);
    }


    @Test
    public void tlsStunAndJunkAreDropped() {
        assertNull(TurnEndpoint.parse("turns:turn.example.com:443?transport=tcp"));
        assertNull(TurnEndpoint.parse("stun:stun.example.com:3478"));
        assertNull(TurnEndpoint.parse("stuns:stun.example.com:5349"));
        assertNull(TurnEndpoint.parse("turn:"));
        assertNull(TurnEndpoint.parse("turn:host:notaport"));
        assertNull(TurnEndpoint.parse(null));
    }


    @Test
    public void unbracketedIpv6IsRejected() {
        assertNull(TurnEndpoint.parse("turn:2001:db8::1"));
    }


    @Test
    public void zeroPortIsRejected() {
        assertNull(TurnEndpoint.parse("turn:h:0"));
    }


    @Test
    public void transportParameterMatchesExactly() {
        assertFalse(TurnEndpoint.parse("turn:h:3478?xtransport=tcp").tcp);
    }


    @Test
    public void schemeAndTransportValueAreCaseInsensitive() {
        TurnEndpoint endpoint = TurnEndpoint.parse("TURN:H:3478?transport=TCP");
        assertEquals("H", endpoint.host);
        assertTrue(endpoint.tcp);
    }
}
