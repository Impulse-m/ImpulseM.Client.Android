package net.impulsem.proxy;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;


/** Minimal SOCKS5 client (RFC 1928 + RFC 1929 username/password) for the local Xray inbound. */
public final class Socks5 {

    public static final class UdpAssociation {
        public final Socket control;
        public final InetSocketAddress relay;


        UdpAssociation(
            Socket control,
            InetSocketAddress relay
        ) {
            this.control = control;
            this.relay = relay;
        }
    }


    private static final int ConnectTimeoutMillis = 10000;


    private Socks5() {
    }


    public static Socket connect(
        InetSocketAddress socks,
        String user,
        String password,
        String host,
        int port
    ) throws IOException {
        Socket socket = open(socks, user, password);
        try {
            request(socket, 1, host, port);
            readReply(socket);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }


    public static UdpAssociation associate(
        InetSocketAddress socks,
        String user,
        String password
    ) throws IOException {
        Socket socket = open(socks, user, password);
        try {
            request(socket, 3, "0.0.0.0", 0);
            InetSocketAddress bound = readReply(socket);
            InetSocketAddress relay = bound.getAddress().isAnyLocalAddress()
                ? new InetSocketAddress(socks.getAddress(), bound.getPort())
                : bound;
            return new UdpAssociation(socket, relay);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }


    public static byte[] wrapUdp(
        String host,
        int port,
        byte[] data,
        int length
    ) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(length + 262);
        out.write(0);
        out.write(0);
        out.write(0);
        writeAddress(out, host, port);
        out.write(data, 0, length);
        return out.toByteArray();
    }


    public static byte[] unwrapUdp(
        byte[] packet,
        int length
    ) throws IOException {
        if (length < 4 || packet[2] != 0) {
            throw new IOException("Malformed SOCKS5 UDP packet");
        }
        int offset;
        int type = packet[3] & 0xff;
        if (type == 1) {
            offset = 4 + 4 + 2;
        } else if (type == 4) {
            offset = 4 + 16 + 2;
        } else if (type == 3 && length > 4) {
            offset = 4 + 1 + (packet[4] & 0xff) + 2;
        } else {
            throw new IOException("Malformed SOCKS5 UDP packet");
        }
        if (offset > length) {
            throw new IOException("Malformed SOCKS5 UDP packet");
        }
        byte[] data = new byte[length - offset];
        System.arraycopy(packet, offset, data, 0, data.length);
        return data;
    }


    private static Socket open(
        InetSocketAddress socks,
        String user,
        String password
    ) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(socks, ConnectTimeoutMillis);
            socket.setTcpNoDelay(true);
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            out.write(new byte[] {5, 1, 2});
            out.flush();
            if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 2) {
                throw new IOException("SOCKS5 server refused username/password auth");
            }
            byte[] userBytes = user.getBytes(StandardCharsets.UTF_8);
            byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream auth = new ByteArrayOutputStream();
            auth.write(1);
            auth.write(userBytes.length);
            auth.write(userBytes, 0, userBytes.length);
            auth.write(passwordBytes.length);
            auth.write(passwordBytes, 0, passwordBytes.length);
            out.write(auth.toByteArray());
            out.flush();
            in.readUnsignedByte();
            if (in.readUnsignedByte() != 0) {
                throw new IOException("SOCKS5 authentication failed");
            }
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }


    private static void request(
        Socket socket,
        int command,
        String host,
        int port
    ) throws IOException {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(5);
        request.write(command);
        request.write(0);
        writeAddress(request, host, port);
        socket.getOutputStream().write(request.toByteArray());
        socket.getOutputStream().flush();
    }


    private static InetSocketAddress readReply(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        in.readUnsignedByte();
        int reply = in.readUnsignedByte();
        in.readUnsignedByte();
        int type = in.readUnsignedByte();
        InetAddress address;
        if (type == 1 || type == 4) {
            byte[] raw = new byte[type == 1 ? 4 : 16];
            in.readFully(raw);
            address = InetAddress.getByAddress(raw);
        } else if (type == 3) {
            byte[] name = new byte[in.readUnsignedByte()];
            in.readFully(name);
            address = InetAddress.getByName(new String(name, StandardCharsets.UTF_8));
        } else {
            throw new IOException("Malformed SOCKS5 reply");
        }
        int port = in.readUnsignedShort();
        if (reply != 0) {
            throw new IOException("SOCKS5 request failed: " + reply);
        }
        return new InetSocketAddress(address, port);
    }


    private static void writeAddress(
        ByteArrayOutputStream out,
        String host,
        int port
    ) {
        byte[] literal = ipv4Literal(host);
        if (literal != null) {
            out.write(1);
            out.write(literal, 0, 4);
        } else {
            byte[] name = host.getBytes(StandardCharsets.UTF_8);
            out.write(3);
            out.write(name.length);
            out.write(name, 0, name.length);
        }
        out.write((port >> 8) & 0xff);
        out.write(port & 0xff);
    }


    private static byte[] ipv4Literal(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        byte[] result = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int value = Integer.parseInt(parts[i]);
                if (value < 0 || value > 255) {
                    return null;
                }
                result[i] = (byte) value;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return result;
    }
}
