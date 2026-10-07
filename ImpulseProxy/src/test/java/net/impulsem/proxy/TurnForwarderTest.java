package net.impulsem.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;


public class TurnForwarderTest {

    @Test
    public void tcpForwardsThroughSocks() throws Exception {
        FakeSocks5Server socks = new FakeSocks5Server("u", "p");
        TurnForwarder forwarder = new TurnForwarder(
            TurnEndpoint.parse("turn:t.example:3478?transport=tcp"),
            socks.address(),
            "u",
            "p"
        );
        Socket client = null;
        try {
            int port = forwarder.start();
            client = new Socket(InetAddress.getLoopbackAddress(), port);
            client.setSoTimeout(5000);
            client.getOutputStream().write("hello".getBytes(StandardCharsets.UTF_8));
            assertEquals("hello", readText(client.getInputStream(), 5));
            assertEquals("t.example:3478", socks.lastTarget.get());
        } finally {
            closeQuietly(client);
            forwarder.close();
            socks.close();
        }
    }


    @Test
    public void closeTearsDownLiveTcpRelays() throws Exception {
        FakeSocks5Server socks = new FakeSocks5Server("u", "p");
        TurnForwarder forwarder = new TurnForwarder(
            TurnEndpoint.parse("turn:t.example:3478?transport=tcp"),
            socks.address(),
            "u",
            "p"
        );
        Socket client = null;
        try {
            int port = forwarder.start();
            client = new Socket(InetAddress.getLoopbackAddress(), port);
            client.setSoTimeout(2000);
            client.getOutputStream().write("hi".getBytes(StandardCharsets.UTF_8));
            assertEquals("hi", readText(client.getInputStream(), 2));
            forwarder.close();
            try {
                assertEquals(-1, client.getInputStream().read());
            } catch (SocketTimeoutException e) {
                fail("relay survived close()");
            } catch (IOException expected) {
                // A reset also means the relay is gone.
            }
        } finally {
            closeQuietly(client);
            forwarder.close();
            socks.close();
        }
    }


    @Test
    public void udpForwardsThroughSocksAndRepliesToTheClient() throws Exception {
        FakeSocks5Server socks = new FakeSocks5Server("u", "p");
        TurnForwarder forwarder = udpForwarder(socks, 8);
        DatagramSocket client = null;
        try {
            int port = forwarder.start();
            client = newClient();
            sendText(client, "stun", port);
            assertEquals("stun", receiveText(client));
        } finally {
            closeQuietly(client);
            forwarder.close();
            socks.close();
        }
    }


    @Test
    public void udpSourcesAreIsolated() throws Exception {
        FakeSocks5Server socks = new FakeSocks5Server("u", "p");
        TurnForwarder forwarder = udpForwarder(socks, 8);
        DatagramSocket a = null;
        DatagramSocket b = null;
        try {
            int port = forwarder.start();
            a = newClient();
            b = newClient();
            sendText(a, "alpha", port);
            sendText(b, "bravo", port);
            assertEquals("alpha", receiveText(a));
            assertEquals("bravo", receiveText(b));
            sendText(a, "alpha-again", port);
            assertEquals("alpha-again", receiveText(a));
        } finally {
            closeQuietly(a);
            closeQuietly(b);
            forwarder.close();
            socks.close();
        }
    }


    @Test
    public void udpRelayDropsSpoofedFrames() throws Exception {
        FakeSocks5Server socks = new FakeSocks5Server("u", "p");
        TurnForwarder forwarder = udpForwarder(socks, 8);
        DatagramSocket a = null;
        DatagramSocket spoof = null;
        try {
            int port = forwarder.start();
            a = newClient();
            spoof = newClient();
            sendText(a, "own", port);
            assertEquals("own", receiveText(a));
            int relayPort = forwarder.relayPortFor(a.getLocalSocketAddress());
            assertTrue(relayPort > 0);
            byte[] payload = "evil".getBytes(StandardCharsets.UTF_8);
            byte[] frame = Socks5.wrapUdp("t.example", 3478, payload, payload.length);
            spoof.send(new DatagramPacket(frame, frame.length, InetAddress.getLoopbackAddress(), relayPort));
            expectSilence(a, 700);
            sendText(a, "again", port);
            assertEquals("again", receiveText(a));
        } finally {
            closeQuietly(a);
            closeQuietly(spoof);
            forwarder.close();
            socks.close();
        }
    }


    @Test
    public void udpSourceLimitIsEnforced() throws Exception {
        FakeSocks5Server socks = new FakeSocks5Server("u", "p");
        TurnForwarder forwarder = udpForwarder(socks, 2);
        DatagramSocket a = null;
        DatagramSocket b = null;
        DatagramSocket c = null;
        try {
            int port = forwarder.start();
            a = newClient();
            b = newClient();
            c = newClient();
            sendText(a, "a", port);
            assertEquals("a", receiveText(a));
            sendText(b, "b", port);
            assertEquals("b", receiveText(b));
            sendText(c, "c", port);
            expectSilence(c, 700);
        } finally {
            closeQuietly(a);
            closeQuietly(b);
            closeQuietly(c);
            forwarder.close();
            socks.close();
        }
    }


    private static TurnForwarder udpForwarder(
        FakeSocks5Server socks,
        int maxSources
    ) {
        return new TurnForwarder(
            TurnEndpoint.parse("turn:t.example:3478?transport=udp"),
            socks.address(),
            "u",
            "p",
            maxSources
        );
    }


    private static DatagramSocket newClient() throws SocketException {
        DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        socket.setSoTimeout(5000);
        return socket;
    }


    private static void sendText(
        DatagramSocket from,
        String text,
        int port
    ) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        from.send(new DatagramPacket(payload, payload.length, InetAddress.getLoopbackAddress(), port));
    }


    private static String receiveText(DatagramSocket socket) throws IOException {
        DatagramPacket packet = new DatagramPacket(new byte[256], 256);
        socket.receive(packet);
        return new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
    }


    private static void expectSilence(
        DatagramSocket socket,
        int millis
    ) throws IOException {
        boolean received = false;
        socket.setSoTimeout(millis);
        try {
            socket.receive(new DatagramPacket(new byte[256], 256));
            received = true;
        } catch (SocketTimeoutException expected) {
            received = false;
        } finally {
            socket.setSoTimeout(5000);
        }
        if (received) {
            fail("unexpected datagram was relayed");
        }
    }


    private static String readText(
        InputStream in,
        int length
    ) throws IOException {
        byte[] buffer = new byte[length];
        int read = 0;
        while (read < length) {
            int count = in.read(buffer, read, length - read);
            if (count < 0) {
                throw new EOFException("Relay closed early");
            }
            read += count;
        }
        return new String(buffer, StandardCharsets.UTF_8);
    }


    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            // Nothing useful to do in test teardown.
        }
    }


    private static void closeQuietly(DatagramSocket socket) {
        if (socket != null) {
            socket.close();
        }
    }
}
