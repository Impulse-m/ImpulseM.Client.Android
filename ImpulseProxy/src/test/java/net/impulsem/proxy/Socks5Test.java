package net.impulsem.proxy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.Test;


public class Socks5Test {

    @Test
    public void connectCarriesHostNameAndBytes() throws Exception {
        FakeSocks5Server server = new FakeSocks5Server("u", "p");
        Socket socket = null;
        try {
            socket = Socks5.connect(server.address(), "u", "p", "turn.example.com", 3478);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write("ping".getBytes(StandardCharsets.UTF_8));
            assertEquals("ping", readText(socket.getInputStream(), 4));
            assertEquals("turn.example.com:3478", server.lastTarget.get());
        } finally {
            if (socket != null) {
                socket.close();
            }
            server.close();
        }
    }


    @Test
    public void connectCarriesIpv6TargetAsAddressType4() throws Exception {
        FakeSocks5Server server = new FakeSocks5Server("u", "p");
        Socket socket = null;
        try {
            socket = Socks5.connect(server.address(), "u", "p", "2001:db8::1", 3478);
            assertEquals("2001:db8:0:0:0:0:0:1:3478", server.lastTarget.get());
        } finally {
            if (socket != null) {
                socket.close();
            }
            server.close();
        }
    }


    @Test
    public void connectReadsAddressType4Reply() throws Exception {
        FakeSocks5Server server = new FakeSocks5Server("u", "p");
        byte[] reply = new byte[22];
        reply[0] = 5;
        reply[3] = 4;
        reply[20] = 0x0d;
        reply[21] = (byte) 0x40;
        server.connectReply = reply;
        Socket socket = null;
        try {
            socket = Socks5.connect(server.address(), "u", "p", "h", 1);
            assertNotNull(socket);
        } finally {
            if (socket != null) {
                socket.close();
            }
            server.close();
        }
    }


    @Test
    public void connectReadsDomainNameReplyAndDiscardsIt() throws Exception {
        FakeSocks5Server server = new FakeSocks5Server("u", "p");
        server.connectReply = new byte[] {5, 0, 0, 3, 4, 104, 111, 115, 116, 0, 80};
        Socket socket = null;
        try {
            socket = Socks5.connect(server.address(), "u", "p", "h", 1);
            assertNotNull(socket);
        } finally {
            if (socket != null) {
                socket.close();
            }
            server.close();
        }
    }


    @Test
    public void connectReportsRequestFailureCode() throws Exception {
        FakeSocks5Server server = new FakeSocks5Server("u", "p");
        server.connectReply = new byte[] {5, 5, 0, 1, 0, 0, 0, 0, 0, 0};
        try {
            Socks5.connect(server.address(), "u", "p", "h", 1);
            fail();
        } catch (IOException e) {
            assertEquals("SOCKS5 request failed: 5", e.getMessage());
        } finally {
            server.close();
        }
    }


    @Test
    public void connectRejectsReplyWithWrongVersion() throws Exception {
        FakeSocks5Server server = new FakeSocks5Server("u", "p");
        server.connectReply = new byte[] {4, 0, 0, 1, 0, 0, 0, 0, 0, 0};
        try {
            Socks5.connect(server.address(), "u", "p", "h", 1);
            fail();
        } catch (IOException e) {
            assertEquals("Malformed SOCKS5 reply", e.getMessage());
        } finally {
            server.close();
        }
    }


    @Test
    public void connectTimesOutOnSilentServer() throws Exception {
        ServerSocket silent = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        try {
            InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), silent.getLocalPort());
            long started = System.currentTimeMillis();
            try {
                Socks5.connect(address, "u", "p", "h", 1, 300);
                fail("handshake must time out");
            } catch (IOException expected) {
                assertTrue(System.currentTimeMillis() - started < 5000);
            }
        } finally {
            silent.close();
        }
    }


    @Test
    public void overlongCredentialsAreRejectedWithoutEchoingThem() throws Exception {
        FakeSocks5Server server = new FakeSocks5Server("u", "p");
        String longUser = new String(new char[256]).replace('\0', 'a');
        try {
            Socks5.connect(server.address(), longUser, "p", "h", 1);
            fail();
        } catch (IOException e) {
            assertEquals("SOCKS5 credentials too long", e.getMessage());
        } finally {
            server.close();
        }
    }


    @Test
    public void rejectsWrongPassword() throws Exception {
        FakeSocks5Server server = new FakeSocks5Server("u", "p");
        try {
            Socks5.connect(server.address(), "u", "wrong", "h", 1);
            fail();
        } catch (IOException e) {
            assertEquals("SOCKS5 authentication failed", e.getMessage());
        } finally {
            server.close();
        }
    }


    @Test
    public void udpHeaderRoundTrip() throws Exception {
        byte[] data = new byte[] {1, 2, 3};
        byte[] wrapped = Socks5.wrapUdp("turn.example.com", 3478, data, data.length);
        assertArrayEquals(data, Socks5.unwrapUdp(wrapped, wrapped.length));
    }


    @Test
    public void udpHeaderRoundTripIpv6UsesAddressType4() throws Exception {
        byte[] data = new byte[] {1, 2, 3};
        byte[] wrapped = Socks5.wrapUdp("2001:db8::1", 3478, data, data.length);
        assertEquals(4, wrapped[3]);
        assertEquals(4 + 16 + 2 + data.length, wrapped.length);
        assertArrayEquals(data, Socks5.unwrapUdp(wrapped, wrapped.length));
    }


    @Test(expected = IOException.class)
    public void malformedUdpThrows() throws Exception {
        Socks5.unwrapUdp(new byte[] {0, 0, 0, 3, 50}, 5);
    }


    @Test
    public void truncatedIpv4UdpHeaderThrows() throws Exception {
        byte[] wrapped = Socks5.wrapUdp("1.2.3.4", 1, new byte[] {9}, 1);
        expectMalformed(wrapped, wrapped.length - 2);
    }


    @Test
    public void truncatedDomainUdpHeaderThrows() throws Exception {
        byte[] wrapped = Socks5.wrapUdp("example.com", 1, new byte[] {9}, 1);
        expectMalformed(wrapped, wrapped.length - 2);
    }


    @Test
    public void truncatedIpv6UdpHeaderThrows() throws Exception {
        byte[] wrapped = Socks5.wrapUdp("2001:db8::1", 1, new byte[] {9}, 1);
        expectMalformed(wrapped, wrapped.length - 2);
    }


    @Test
    public void nonZeroFragmentThrows() throws Exception {
        byte[] wrapped = Socks5.wrapUdp("1.2.3.4", 1, new byte[] {9}, 1);
        wrapped[2] = 1;
        expectMalformed(wrapped, wrapped.length);
    }


    private static void expectMalformed(
        byte[] packet,
        int length
    ) {
        try {
            Socks5.unwrapUdp(packet, length);
            fail("malformed packet must throw");
        } catch (IOException expected) {
            assertEquals("Malformed SOCKS5 UDP packet", expected.getMessage());
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
                throw new EOFException("Stream ended early");
            }
            read += count;
        }
        return new String(buffer, StandardCharsets.UTF_8);
    }
}
