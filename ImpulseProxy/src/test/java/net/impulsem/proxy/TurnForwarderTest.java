package net.impulsem.proxy;

import static org.junit.Assert.assertEquals;

import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.Test;


public class TurnForwarderTest {

    @Test
    public void tcpForwardsThroughSocks() throws Exception {
        FakeSocks5Server socks = new FakeSocks5Server("u", "p");
        TurnForwarder forwarder = new TurnForwarder(TurnEndpoint.parse("turn:t.example:3478?transport=tcp"), socks.address(), "u", "p");
        int port = forwarder.start();
        Socket client = new Socket(InetAddress.getLoopbackAddress(), port);
        client.getOutputStream().write("hello".getBytes(StandardCharsets.UTF_8));
        byte[] reply = new byte[5];
        InputStream in = client.getInputStream();
        int read = 0;
        while (read < 5) {
            read += in.read(reply, read, 5 - read);
        }
        assertEquals("hello", new String(reply, StandardCharsets.UTF_8));
        assertEquals("t.example:3478", socks.lastTarget.get());
        client.close();
        forwarder.close();
        socks.close();
    }


    @Test
    public void udpForwardsThroughSocksAndRepliesToTheClient() throws Exception {
        FakeSocks5Server socks = new FakeSocks5Server("u", "p");
        TurnForwarder forwarder = new TurnForwarder(TurnEndpoint.parse("turn:t.example:3478?transport=udp"), socks.address(), "u", "p");
        int port = forwarder.start();
        DatagramSocket client = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        client.setSoTimeout(5000);
        byte[] payload = "stun".getBytes(StandardCharsets.UTF_8);
        client.send(new DatagramPacket(payload, payload.length, InetAddress.getLoopbackAddress(), port));
        DatagramPacket reply = new DatagramPacket(new byte[64], 64);
        client.receive(reply);
        assertEquals("stun", new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8));
        client.close();
        forwarder.close();
        socks.close();
    }


    @Test
    public void udpIgnoresSecondLocalSource() throws Exception {
        FakeSocks5Server socks = new FakeSocks5Server("u", "p");
        TurnForwarder forwarder = new TurnForwarder(TurnEndpoint.parse("turn:t.example:3478?transport=udp"), socks.address(), "u", "p");
        int port = forwarder.start();
        DatagramSocket owner = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        owner.setSoTimeout(5000);
        byte[] first = "own".getBytes(StandardCharsets.UTF_8);
        owner.send(new DatagramPacket(first, first.length, InetAddress.getLoopbackAddress(), port));
        owner.receive(new DatagramPacket(new byte[64], 64));
        DatagramSocket intruder = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        intruder.setSoTimeout(700);
        byte[] second = "evil".getBytes(StandardCharsets.UTF_8);
        intruder.send(new DatagramPacket(second, second.length, InetAddress.getLoopbackAddress(), port));
        try {
            intruder.receive(new DatagramPacket(new byte[64], 64));
            org.junit.Assert.fail("intruder must not get a relayed reply");
        } catch (java.net.SocketTimeoutException expected) {
            // Dropped, as intended.
        }
        owner.setSoTimeout(700);
        try {
            owner.receive(new DatagramPacket(new byte[64], 64));
            org.junit.Assert.fail("the intruder's datagram must not be relayed");
        } catch (java.net.SocketTimeoutException expected) {
            // Dropped, as intended.
        }
        intruder.close();
        owner.close();
        forwarder.close();
        socks.close();
    }
}
