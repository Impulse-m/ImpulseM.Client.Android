package net.impulsem.proxy;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;


/**
 * SOCKS5 (RFC 1928/1929) test server: CONNECT echoes bytes, UDP ASSOCIATE echoes datagrams
 * back with the same header.
 */
final class FakeSocks5Server implements Closeable {
    final AtomicReference<String> lastTarget = new AtomicReference<String>();
    volatile byte[] connectReply = new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 0};

    private final ServerSocket server;
    private final String user;
    private final String password;
    private volatile boolean closed;


    FakeSocks5Server(
        String user,
        String password
    ) throws IOException {
        this.user = user;
        this.password = password;
        this.server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "fake-socks");
        thread.setDaemon(true);
        thread.start();
    }


    InetSocketAddress address() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());
    }


    @Override
    public void close() throws IOException {
        closed = true;
        server.close();
    }


    private void acceptLoop() {
        while (!closed) {
            try {
                final Socket socket = server.accept();
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handle(socket);
                    }
                }, "fake-socks-conn");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException e) {
                return;
            }
        }
    }


    private void handle(Socket socket) {
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            in.readUnsignedByte();
            int methods = in.readUnsignedByte();
            in.readFully(new byte[methods]);
            out.write(new byte[] {5, 2});
            in.readUnsignedByte();
            byte[] gotUser = new byte[in.readUnsignedByte()];
            in.readFully(gotUser);
            byte[] gotPassword = new byte[in.readUnsignedByte()];
            in.readFully(gotPassword);
            boolean ok = user.equals(new String(gotUser, StandardCharsets.UTF_8))
                && password.equals(new String(gotPassword, StandardCharsets.UTF_8));
            out.write(new byte[] {1, (byte) (ok ? 0 : 1)});
            if (!ok) {
                socket.close();
                return;
            }
            in.readUnsignedByte();
            int command = in.readUnsignedByte();
            in.readUnsignedByte();
            String target = readAddress(in);
            lastTarget.set(target);
            if (command == 1) {
                out.write(connectReply);
                pump(in, out);
            } else {
                final DatagramSocket relay = new DatagramSocket(0, InetAddress.getLoopbackAddress());
                int port = relay.getLocalPort();
                out.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port});
                Thread echo = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        echoUdp(relay);
                    }
                }, "fake-socks-udp");
                echo.setDaemon(true);
                echo.start();
                while (in.read() >= 0) {
                    // Hold the association until the client closes the control connection.
                }
                relay.close();
            }
        } catch (IOException e) {
            // Test server: a broken client just ends the session.
        }
    }


    private static String readAddress(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        String host;
        if (type == 3) {
            byte[] name = new byte[in.readUnsignedByte()];
            in.readFully(name);
            host = new String(name, StandardCharsets.UTF_8);
        } else {
            byte[] address = new byte[type == 4 ? 16 : 4];
            in.readFully(address);
            host = InetAddress.getByAddress(address).getHostAddress();
        }
        int port = in.readUnsignedShort();
        return host + ":" + port;
    }


    private static void pump(
        InputStream in,
        OutputStream out
    ) throws IOException {
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
            out.flush();
        }
    }


    private static void echoUdp(DatagramSocket relay) {
        byte[] buffer = new byte[65535];
        while (!relay.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                relay.receive(packet);
                relay.send(new DatagramPacket(packet.getData(), packet.getLength(), packet.getSocketAddress()));
            } catch (IOException e) {
                return;
            }
        }
    }
}
