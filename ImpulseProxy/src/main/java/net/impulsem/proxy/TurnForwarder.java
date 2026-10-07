package net.impulsem.proxy;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;


/** Loopback listener that relays one TURN endpoint through the Xray SOCKS5 inbound (TCP via CONNECT, UDP via UDP ASSOCIATE). */
public final class TurnForwarder implements Closeable {
    private final TurnEndpoint target;
    private final InetSocketAddress socks;
    private final String user;
    private final String password;
    private volatile boolean closed;
    private ServerSocket tcpListener;
    private DatagramSocket udpLocal;
    private DatagramSocket udpRelay;
    private Socks5.UdpAssociation association;
    private volatile SocketAddress udpClient;


    public TurnForwarder(
        TurnEndpoint target,
        InetSocketAddress socks,
        String user,
        String password
    ) {
        this.target = target;
        this.socks = socks;
        this.user = user;
        this.password = password;
    }


    public int start() throws IOException {
        if (target.tcp) {
            tcpListener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            daemon("turn-tcp-accept", new Runnable() {
                @Override
                public void run() {
                    acceptTcp();
                }
            });
            return tcpListener.getLocalPort();
        }
        association = Socks5.associate(socks, user, password);
        udpLocal = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        udpRelay = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        daemon("turn-udp-out", new Runnable() {
            @Override
            public void run() {
                udpOut();
            }
        });
        daemon("turn-udp-in", new Runnable() {
            @Override
            public void run() {
                udpIn();
            }
        });
        return udpLocal.getLocalPort();
    }


    @Override
    public void close() {
        closed = true;
        closeQuietly(tcpListener);
        closeQuietly(udpLocal);
        closeQuietly(udpRelay);
        if (association != null) {
            closeQuietly(association.control);
        }
    }


    private void acceptTcp() {
        while (!closed) {
            final Socket client;
            try {
                client = tcpListener.accept();
            } catch (IOException e) {
                return;
            }
            daemon("turn-tcp-conn", new Runnable() {
                @Override
                public void run() {
                    relayTcp(client);
                }
            });
        }
    }


    private void relayTcp(final Socket client) {
        final Socket upstream;
        try {
            upstream = Socks5.connect(socks, user, password, target.host, target.port);
        } catch (IOException e) {
            closeQuietly(client);
            return;
        }
        daemon("turn-tcp-up", new Runnable() {
            @Override
            public void run() {
                pipe(client, upstream);
            }
        });
        pipe(upstream, client);
    }


    private void udpOut() {
        byte[] buffer = new byte[65535];
        while (!closed) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpLocal.receive(packet);
                SocketAddress source = packet.getSocketAddress();
                // Pin the first sender (WebRTC's TURN socket); datagrams from any other local app are dropped.
                if (udpClient == null) {
                    udpClient = source;
                } else if (!udpClient.equals(source)) {
                    continue;
                }
                byte[] wrapped = Socks5.wrapUdp(target.host, target.port, packet.getData(), packet.getLength());
                udpRelay.send(new DatagramPacket(wrapped, wrapped.length, association.relay));
            } catch (IOException e) {
                return;
            }
        }
    }


    private void udpIn() {
        byte[] buffer = new byte[65535];
        while (!closed) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpRelay.receive(packet);
                SocketAddress client = udpClient;
                if (client == null) {
                    continue;
                }
                byte[] data = Socks5.unwrapUdp(packet.getData(), packet.getLength());
                udpLocal.send(new DatagramPacket(data, data.length, client));
            } catch (IOException e) {
                if (closed) {
                    return;
                }
            }
        }
    }


    private static void pipe(
        Socket from,
        Socket to
    ) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException e) {
            // Either side closed; tear the pair down below.
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }


    private static void daemon(
        String name,
        Runnable body
    ) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
    }


    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            // Nothing useful to do on close.
        }
    }
}
