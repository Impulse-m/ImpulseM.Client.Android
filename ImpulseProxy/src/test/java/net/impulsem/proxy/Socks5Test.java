package net.impulsem.proxy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.Test;


public class Socks5Test {

    @Test
    public void connectCarriesHostNameAndBytes() throws Exception {
        FakeSocks5Server server = new FakeSocks5Server("u", "p");
        Socket socket = Socks5.connect(server.address(), "u", "p", "turn.example.com", 3478);
        socket.getOutputStream().write("ping".getBytes(StandardCharsets.UTF_8));
        byte[] reply = new byte[4];
        InputStream in = socket.getInputStream();
        int read = 0;
        while (read < 4) {
            read += in.read(reply, read, 4 - read);
        }
        assertEquals("ping", new String(reply, StandardCharsets.UTF_8));
        assertEquals("turn.example.com:3478", server.lastTarget.get());
        socket.close();
        server.close();
    }


    @Test
    public void rejectsWrongPassword() throws Exception {
        FakeSocks5Server server = new FakeSocks5Server("u", "p");
        try {
            Socks5.connect(server.address(), "u", "wrong", "h", 1);
            fail();
        } catch (IOException e) {
            assertEquals("SOCKS5 authentication failed", e.getMessage());
        }
        server.close();
    }


    @Test
    public void udpHeaderRoundTrip() throws Exception {
        byte[] data = new byte[] {1, 2, 3};
        byte[] wrapped = Socks5.wrapUdp("turn.example.com", 3478, data, data.length);
        assertArrayEquals(data, Socks5.unwrapUdp(wrapped, wrapped.length));
    }


    @Test(expected = IOException.class)
    public void malformedUdpThrows() throws Exception {
        Socks5.unwrapUdp(new byte[] {0, 0, 0, 3, 50}, 5);
    }
}
