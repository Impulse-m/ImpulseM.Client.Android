package net.impulsem.proxy;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
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
    private static final int HandshakeTimeoutMillis = 10000;
    private static final int MaxCredentialBytes = 255;


    private Socks5() {
    }


    public static Socket connect(
        InetSocketAddress socks,
        String user,
        String password,
        String host,
        int port
    ) throws IOException {
        return connect(socks, user, password, host, port, HandshakeTimeoutMillis);
    }


    static Socket connect(
        InetSocketAddress socks,
        String user,
        String password,
        String host,
        int port,
        int handshakeTimeoutMillis
    ) throws IOException {
        Socket socket = open(socks, user, password, handshakeTimeoutMillis);
        try {
            request(socket, 1, host, port);
            readReply(socket);
            socket.setSoTimeout(0);
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
        Socket socket = open(socks, user, password, HandshakeTimeoutMillis);
        try {
            request(socket, 3, "0.0.0.0", 0);
            int port = readReply(socket);
            socket.setSoTimeout(0);
            // The relay host is always the proxy host; the reply address is never resolved.
            return new UdpAssociation(socket, new InetSocketAddress(socks.getAddress(), port));
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
        String password,
        int handshakeTimeoutMillis
    ) throws IOException {
        byte[] userBytes = credentialBytes(user);
        byte[] passwordBytes = credentialBytes(password);
        Socket socket = new Socket();
        try {
            socket.connect(socks, ConnectTimeoutMillis);
            socket.setTcpNoDelay(true);
            // Bounds every handshake read; connect() and associate() clear it once the reply is in.
            socket.setSoTimeout(handshakeTimeoutMillis);
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            out.write(new byte[] {5, 1, 2});
            out.flush();
            if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 2) {
                throw new IOException("SOCKS5 server refused username/password auth");
            }
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


    private static byte[] credentialBytes(String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MaxCredentialBytes) {
            throw new IOException("SOCKS5 credentials too long");
        }
        return bytes;
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


    /** Reads a SOCKS5 reply, discards the bound address and returns the bound port. */
    private static int readReply(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        if (in.readUnsignedByte() != 5) {
            throw new IOException("Malformed SOCKS5 reply");
        }
        int reply = in.readUnsignedByte();
        in.readUnsignedByte();
        int type = in.readUnsignedByte();
        int addressLength;
        if (type == 1) {
            addressLength = 4;
        } else if (type == 4) {
            addressLength = 16;
        } else if (type == 3) {
            addressLength = in.readUnsignedByte();
        } else {
            throw new IOException("Malformed SOCKS5 reply");
        }
        in.readFully(new byte[addressLength]);
        int port = in.readUnsignedShort();
        if (reply != 0) {
            throw new IOException("SOCKS5 request failed: " + reply);
        }
        return port;
    }


    private static void writeAddress(
        ByteArrayOutputStream out,
        String host,
        int port
    ) {
        byte[] literal = ipLiteral(host);
        if (literal == null) {
            byte[] name = host.getBytes(StandardCharsets.UTF_8);
            out.write(3);
            out.write(name.length);
            out.write(name, 0, name.length);
        } else {
            out.write(literal.length == 4 ? 1 : 4);
            out.write(literal, 0, literal.length);
        }
        out.write((port >> 8) & 0xff);
        out.write(port & 0xff);
    }


    /** Returns 4 bytes for an IPv4 literal, 16 for an IPv6 literal, or null for a domain name. */
    private static byte[] ipLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return ipv6Literal(host);
        }
        return ipv4Literal(host);
    }


    private static byte[] ipv6Literal(String host) {
        // Only hex digits, colons and dots: getByName then parses a literal and never reaches DNS.
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean allowed = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                || c == ':' || c == '.';
            if (!allowed) {
                return null;
            }
        }
        try {
            return InetAddress.getByName(host).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
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
