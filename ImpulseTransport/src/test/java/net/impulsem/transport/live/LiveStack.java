package net.impulsem.transport.live;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.impulsem.transport.auth.Clock;
import net.impulsem.transport.auth.ConnectionDeclaration;
import net.impulsem.transport.auth.Credentials;
import net.impulsem.transport.auth.IdentityManager;
import net.impulsem.transport.auth.SessionStore;
import net.impulsem.transport.auth.SessionTokens;
import net.impulsem.transport.auth.TokenManager;
import net.impulsem.transport.codec.Transcoder;
import net.impulsem.transport.fleet.MediaFleet;
import net.impulsem.transport.fleet.RpcPoolCarrier;
import net.impulsem.transport.realtime.CentrifugoClient;
import net.impulsem.transport.realtime.CentrifugoListener;
import net.impulsem.transport.realtime.Publication;
import net.impulsem.transport.rpc.ConnectionDeclarer;
import net.impulsem.transport.rpc.RpcClient;
import okhttp3.OkHttpClient;


/**
 * One account's transport, wired the way the app wires it (ImpulseConnection and ImpulseRealtime): the update socket,
 * the token-less pool fleet, the media fleet and the declaration gate. The socket presents the connection identity
 * while signed out and the access token once a session exists.
 */
final class LiveStack {

    static final int ApiId = 3;
    static final String SocketUrl = "wss://rpc.impulsem.io/connection/websocket?cf_ws_frame_ping_pong=true";
    static final String PoolUrl = "wss://rpc.impulsem.io/pool/connection/websocket?cf_protocol=protobuf&cf_ws_frame_ping_pong=true";

    private static final String MediaPath = "/connection/websocket?cf_protocol=protobuf&cf_ws_frame_ping_pong=true";
    private static final int SchedulerThreads = 4;
    private static final String DeviceModel = "LiveTest";
    private static final String SystemVersion = "JVM";
    private static final String AppVersion = "12.10.6";
    private static final String SystemLangCode = "en";
    private static final String LangPack = "android";
    private static final String LangCode = "en";
    private static final long PollMillis = 100L;


    /** Keeps the session in memory; the live tests never persist it. */
    private static final class MemorySessionStore implements SessionStore {

        private SessionTokens saved;


        @Override
        public synchronized SessionTokens load() {
            return saved;
        }


        @Override
        public synchronized void save(SessionTokens tokens) {
            saved = tokens;
        }


        @Override
        public synchronized void clear() {
            saved = null;
        }
    }


    private static final class MemoryIdentityStore implements IdentityManager.Store {

        private String saved;


        @Override
        public synchronized String load() {
            return saved;
        }


        @Override
        public synchronized void save(String identityToken) {
            saved = identityToken;
        }


        @Override
        public synchronized void clear() {
            saved = null;
        }
    }


    /** Records what the socket reports. Callbacks run on the SDK thread, so they only store the event. */
    private final class Recorder implements CentrifugoListener {

        private final String label;
        private final Set<String> subscribed = ConcurrentHashMap.newKeySet();
        private final BlockingQueue<Publication> publications = new LinkedBlockingQueue<Publication>();
        private volatile boolean connected;


        Recorder(String label) {
            this.label = label;
        }


        /** Forgets the socket state before a rekey; the SDK reports the new state again. */
        void forget() {
            connected = false;
            subscribed.clear();
        }


        @Override
        public void onConnected() {
            connected = true;
            log("connected");
        }


        @Override
        public void onSubscribed(
            String channel,
            boolean recovered,
            boolean wasRecovering
        ) {
            subscribed.add(channel);
            log("subscribed " + channel + " recovered=" + recovered + " wasRecovering=" + wasRecovering);
        }


        @Override
        public void onPublication(Publication publication) {
            publications.add(publication);
            log("publication " + publication.channel + " offset=" + publication.offset);
        }


        @Override
        public void onUnsubscribed(
            String channel,
            int code,
            String reason
        ) {
            subscribed.remove(channel);
            log("UNSUBSCRIBED " + channel + " code=" + code + " reason=" + reason);
        }


        @Override
        public void onDisconnected(
            int code,
            String reason,
            boolean willReconnect
        ) {
            connected = false;
            log("DISCONNECTED code=" + code + " reason=" + reason + " willReconnect=" + willReconnect);
        }


        private void log(String text) {
            System.out.println("LIVE " + label + " " + text);
        }
    }


    final TokenManager tokens;
    final Credentials credentials;
    final IdentityManager identity;
    final MediaFleet mediaFleet;
    final RpcClient rpc;

    private final ScheduledThreadPoolExecutor scheduler;
    private final MemorySessionStore store;
    private final Recorder recorder;
    private final RpcPoolCarrier poolFleet;
    private final CentrifugoClient client;
    private final ConnectionDeclarer declarer;
    /** The credential kind the socket was last keyed with: true for the access token, false for the identity. */
    private volatile boolean socketSignedIn;


    LiveStack(
        OkHttpClient http,
        Transcoder transcoder,
        String label,
        SessionTokens saved
    ) {
        this.scheduler = new ScheduledThreadPoolExecutor(SchedulerThreads);
        this.store = new MemorySessionStore();
        if (saved != null) {
            store.save(saved);
        }
        this.recorder = new Recorder(label);
        final ScheduledThreadPoolExecutor timers = scheduler;
        ConnectionDeclarer.Delayer delayer = new ConnectionDeclarer.Delayer() {
            @Override
            public void schedule(
                Runnable task,
                long delayMillis
            ) {
                timers.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
            }
        };
        this.tokens = new TokenManager(store, Clock.SYSTEM);
        this.tokens.setDeferredRefreshScheduling(scheduler, delayer);
        // A WebSocket has no read deadline of its own: the server's pings keep it alive, as in the app.
        OkHttpClient poolHttp = http.newBuilder().readTimeout(0L, TimeUnit.MILLISECONDS).build();
        this.poolFleet = RpcPoolCarrier.poolFleet(poolHttp, PoolUrl);
        this.mediaFleet = new MediaFleet(poolHttp, tokens, scheduler);
        IdentityManager.Store identityStore = new MemoryIdentityStore();
        this.identity = new IdentityManager(identityStore, poolFleet, Clock.SYSTEM, new Random(), delayer, scheduler);
        this.identity.setApiId(ApiId);
        this.credentials = new Credentials(tokens, identity);
        this.client = new CentrifugoClient(
            poolHttp,
            SocketUrl,
            new CentrifugoClient.TokenProvider() {
                @Override
                public String fetchToken() throws IOException {
                    return credentials.connectTokenBlocking();
                }
            },
            recorder,
            scheduler,
            randomInstance(new Random())
        );
        this.tokens.setRefreshCarriers(client, poolFleet);
        this.identity.setRenewalCarrier(client);
        this.identity.setListener(new IdentityManager.Listener() {
            @Override
            public void onIdentityChanged() {
                rekey();
            }


            @Override
            public void onIdentityRenewed(String identityToken) {
                client.refreshConnectionToken(identityToken);
            }
        });
        this.socketSignedIn = tokens.hasSession();
        this.tokens.addSessionListener(new Runnable() {
            @Override
            public void run() {
                // A sign-in ends the identity and rekeys the socket to the access token. A refresh of a live session
                // goes to the socket without a reconnect. The socket's credential kind is checked too, so a sign-in
                // whose identity had already lapsed still rekeys.
                if (identity.current() != null || !socketSignedIn) {
                    identity.discard();
                    mediaFleet.sessionChanged();
                    rekey();
                } else {
                    client.refreshConnectionToken(tokens.bearer());
                }
            }
        });
        this.rpc = new RpcClient(transcoder, client, mediaFleet, tokens, credentials);
        this.declarer = new ConnectionDeclarer(
            rpc,
            tokens,
            credentials,
            scheduler,
            delayer,
            new ConnectionDeclarer.Listener() {
                @Override
                public void onResult(
                    String outcome,
                    int attempt,
                    int code
                ) {
                    System.out.println("LIVE " + label + " declare " + outcome + " attempt=" + attempt + " code=" + code);
                }
            }
        );
        this.rpc.setDeclarationGate(declarer);
        this.declarer.setDeclaration(new ConnectionDeclaration(
            ApiId,
            DeviceModel,
            SystemVersion,
            AppVersion,
            SystemLangCode,
            LangPack,
            LangCode
        ));
    }


    /** Starts the update socket. It connects with the identity, or with the access token when a session exists. */
    void connect() {
        client.connect();
    }


    /** Drops the socket and connects it again with the credential the stack holds now. */
    void rekey() {
        socketSignedIn = credentials.signedIn();
        recorder.forget();
        client.disconnect();
        client.connect();
    }


    /** What the app does after auth.logOut: the tokens go, the media pool closes, and the socket follows the signed-out state. */
    void afterLogOut() {
        tokens.clear();
        mediaFleet.sessionChanged();
        rekey();
    }


    /** Subscribes the user lane; the SDK sends the subscription again after every reconnect. */
    void subscribeUser(long userId) {
        client.subscribe("user:" + userId);
    }


    /** Waits until the socket reports connected; false when it does not within the timeout. */
    boolean awaitConnected(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!recorder.connected && System.currentTimeMillis() < deadline) {
            Thread.sleep(PollMillis);
        }
        return recorder.connected;
    }


    /** Waits until the socket reports the channel subscribed; false when it does not within the timeout. */
    boolean awaitSubscribed(
        String channel,
        long timeoutMillis
    ) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!recorder.subscribed.contains(channel) && System.currentTimeMillis() < deadline) {
            Thread.sleep(PollMillis);
        }
        return recorder.subscribed.contains(channel);
    }


    /** Every publication the socket delivers, in arrival order. */
    BlockingQueue<Publication> publications() {
        return recorder.publications;
    }


    /** The stored session, or null when the account is signed out. */
    SessionTokens session() {
        return store.load();
    }


    /** Points the media fleet at the media_only option of this_dc in the config, as the app does on every help.getConfig. */
    void applyConfig(Map<String, Object> config) {
        String endpoint = mediaEndpoint(config);
        mediaFleet.setEndpoint(endpoint);
        System.out.println("LIVE media endpoint = " + endpoint);
    }


    void close() {
        client.disconnect();
        poolFleet.close();
        mediaFleet.close();
        scheduler.shutdownNow();
    }


    /** The media_only option of this_dc as the media fleet's WebSocket URL, or null when the config has none. */
    static String mediaEndpoint(Map<String, Object> config) {
        int thisDc = ((Number) config.get("this_dc")).intValue();
        for (Object item : (List<?>) config.get("dc_options")) {
            Map<String, Object> option = LiveAccounts.asMap(item);
            boolean mediaOnly = Boolean.TRUE.equals(option.get("media_only"));
            if (mediaOnly && ((Number) option.get("id")).intValue() == thisDc) {
                String address = (String) option.get("ip_address");
                String host = Boolean.TRUE.equals(option.get("ipv6")) ? "[" + address + "]" : address;
                return "wss://" + host + ":" + ((Number) option.get("port")).intValue() + MediaPath;
            }
        }
        return null;
    }


    /** A random rpc instance: 32 hex characters, sent to the server for the rpc options. */
    private static String randomInstance(Random random) {
        return String.format("%016x%016x", random.nextLong(), random.nextLong());
    }
}
