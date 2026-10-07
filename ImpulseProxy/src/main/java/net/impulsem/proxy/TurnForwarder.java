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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Loopback listener that relays one TURN endpoint through the Xray SOCKS5 inbound.
 * TCP goes through CONNECT; UDP gets one SOCKS5 UDP association per local source.
 */
public final class TurnForwarder implements Closeable {
    private static final int MaxUdpSources = 8;

    private final TurnEndpoint target;
    private final InetSocketAddress socks;
    private final String user;
    private final String password;
    private final int maxUdpSources;
    private final Map<SocketAddress, UdpSession> sessions = new ConcurrentHashMap<SocketAddress, UdpSession>();
    private final Set<Socket> sockets = ConcurrentHashMap.<Socket>newKeySet();
    private volatile boolean closed;
    private ServerSocket tcpListener;
    private DatagramSocket udpLocal;


    public TurnForwarder(
        TurnEndpoint target,
        InetSocketAddress socks,
        String user,
        String password
    ) {
        this(target, socks, user, password, MaxUdpSources);
    }


    TurnForwarder(
        TurnEndpoint target,
        InetSocketAddress socks,
        String user,
        String password,
        int maxUdpSources
    ) {
        this.target = target;
        this.socks = socks;
        this.user = user;
        this.password = password;
        this.maxUdpSources = maxUdpSources;
    }


    public int start() throws IOException {
        try {
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
            udpLocal = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            daemon("turn-udp-local", new Runnable() {
                @Override
                public void run() {
                    udpLocalLoop();
                }
            });
            return udpLocal.getLocalPort();
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }


    @Override
    public void close() {
        closed = true;
        closeQuietly(tcpListener);
        closeQuietly(udpLocal);
        for (Socket socket : sockets) {
            closeTracked(socket);
        }
        for (UdpSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
    }


    /** Relay port of the session for this local source, or -1 when there is none. */
    int relayPortFor(SocketAddress source) {
        UdpSession session = sessions.get(source);
        return session == null ? -1 : session.relaySocket.getLocalPort();
    }


    int sessionCount() {
        return sessions.size();
    }


    private void acceptTcp() {
        while (!closed) {
            final Socket client;
            try {
                client = tcpListener.accept();
            } catch (IOException e) {
                return;
            }
            sockets.add(client);
            if (closed) {
                closeTracked(client);
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
            closeTracked(client);
            return;
        }
        sockets.add(upstream);
        if (closed) {
            closeTracked(upstream);
            closeTracked(client);
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


    private void udpLocalLoop() {
        byte[] buffer = new byte[65535];
        while (!closed) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udpLocal.receive(packet);
                UdpSession session = sessionFor(packet.getSocketAddress());
                if (session == null) {
                    continue;
                }
                byte[] wrapped = Socks5.wrapUdp(target.host, target.port, packet.getData(), packet.getLength());
                session.send(wrapped);
            } catch (IOException e) {
                if (closed) {
                    return;
                }
            }
        }
    }


    /** Returns the session for a local source, creating one (evicting the idlest at the cap); null means drop. */
    private UdpSession sessionFor(SocketAddress source) throws IOException {
        UdpSession existing = sessions.get(source);
        if (existing != null) {
            return existing;
        }
        if (sessions.size() >= maxUdpSources) {
            evictLeastRecentlyActive();
        }
        if (sessions.size() >= maxUdpSources) {
            return null;
        }
        UdpSession created = new UdpSession(source);
        sessions.put(source, created);
        if (closed || created.dead) {
            sessions.remove(source, created);
            created.close();
            return null;
        }
        return created;
    }


    private void evictLeastRecentlyActive() {
        UdpSession idlest = null;
        for (UdpSession session : sessions.values()) {
            if (idlest == null || session.lastActivity - idlest.lastActivity < 0) {
                idlest = session;
            }
        }
        if (idlest != null) {
            idlest.close();
        }
    }


    private void closeTracked(Socket socket) {
        sockets.remove(socket);
        closeQuietly(socket);
    }


    private void pipe(
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
            closeTracked(from);
            closeTracked(to);
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


    /** One SOCKS5 UDP association serving one local source. Datagrams flow only between that source and its relay. */
    private final class UdpSession {
        private final SocketAddress source;
        private final Socks5.UdpAssociation association;
        private final DatagramSocket relaySocket;
        private volatile boolean dead;
        private volatile long lastActivity = System.nanoTime();


        UdpSession(SocketAddress source) throws IOException {
            this.source = source;
            this.association = Socks5.associate(socks, user, password);
            DatagramSocket socket;
            try {
                socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            } catch (IOException e) {
                closeQuietly(association.control);
                throw e;
            }
            this.relaySocket = socket;
            daemon("turn-udp-in", new Runnable() {
                @Override
                public void run() {
                    receiveLoop();
                }
            });
            daemon("turn-udp-watch", new Runnable() {
                @Override
                public void run() {
                    watchControl();
                }
            });
        }


        void send(byte[] wrapped) throws IOException {
            // Stamped before the send so a reply cannot be seen before the activity it answers.
            lastActivity = System.nanoTime();
            relaySocket.send(new DatagramPacket(wrapped, wrapped.length, association.relay));
        }


        void close() {
            dead = true;
            closeQuietly(relaySocket);
            closeQuietly(association.control);
            sessions.remove(source, this);
        }


        private void receiveLoop() {
            byte[] buffer = new byte[65535];
            while (!closed && !relaySocket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    relaySocket.receive(packet);
                    if (!isFromRelay(packet)) {
                        continue;
                    }
                    byte[] data = Socks5.unwrapUdp(packet.getData(), packet.getLength());
                    lastActivity = System.nanoTime();
                    udpLocal.send(new DatagramPacket(data, data.length, source));
                } catch (IOException e) {
                    if (closed || relaySocket.isClosed()) {
                        return;
                    }
                }
            }
        }


        /** Accepts only the proxy's relay port, from loopback or the proxy's own address. */
        private boolean isFromRelay(DatagramPacket packet) {
            InetSocketAddress relay = association.relay;
            if (packet.getPort() != relay.getPort()) {
                return false;
            }
            InetAddress from = packet.getAddress();
            return from.isLoopbackAddress() || from.equals(relay.getAddress());
        }


        private void watchControl() {
            byte[] scratch = new byte[64];
            try {
                InputStream in = association.control.getInputStream();
                while (in.read(scratch) >= 0) {
                    // The proxy sends nothing more on the control connection; anything it sends is ignored.
                }
            } catch (IOException e) {
                // Treated the same as EOF.
            }
            close();
        }
    }
}
