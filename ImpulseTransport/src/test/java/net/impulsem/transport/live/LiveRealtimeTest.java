package net.impulsem.transport.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.impulsem.transport.auth.ImpulseRpcPaths;
import net.impulsem.transport.codec.CaptureSink;
import net.impulsem.transport.realtime.CentrifugoClient;
import net.impulsem.transport.realtime.CentrifugoListener;
import net.impulsem.transport.realtime.Publication;
import net.impulsem.transport.wire.ProtoReader;
import net.impulsem.transport.wire.ProtoWriter;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;


/**
 * Opt-in live realtime test: {@code -Dimpulse.live=true}. The 6 minute hold additionally needs
 * {@code -Dimpulse.live.long=true}.
 */
public class LiveRealtimeTest {

    private static final long PublicationWaitMillis = 10000L;

    private static final CaptureSink NoCapture = new CaptureSink() {
        @Override
        public void onCapture(
            String protoMessage,
            String fieldName,
            String value
        ) {
        }
    };


    /** Collects everything a client reports, with timestamps relative to the test start. */
    private static final class Collector implements CentrifugoListener {

        final String label;
        final long start = System.currentTimeMillis();
        final BlockingQueue<Publication> publications = new LinkedBlockingQueue<Publication>();
        final List<String> log = Collections.synchronizedList(new ArrayList<String>());


        Collector(String label) {
            this.label = label;
        }


        private void note(String text) {
            String line = String.format("LIVE %s +%ds %s", label, (System.currentTimeMillis() - start) / 1000L, text);
            log.add(line);
            System.out.println(line);
        }


        @Override
        public void onConnected() {
            note("connected");
        }


        @Override
        public void onSubscribed(
            String channel,
            boolean recovered,
            boolean wasRecovering
        ) {
            note("subscribed " + channel + " recovered=" + recovered + " wasRecovering=" + wasRecovering);
        }


        @Override
        public void onPublication(Publication publication) {
            note("publication " + publication.channel + " offset=" + publication.offset + " type=" + publication.envelope.type
                + " pts=" + publication.envelope.pts + " body=" + (publication.envelope.updatesProto != null)
                + " oversize=" + publication.envelope.oversize);
            publications.add(publication);
        }


        @Override
        public void onUnsubscribed(
            String channel,
            int code,
            String reason
        ) {
            note("UNSUBSCRIBED " + channel + " code=" + code + " reason=" + reason);
        }


        @Override
        public void onDisconnected(
            int code,
            String reason,
            boolean willReconnect
        ) {
            note("DISCONNECTED code=" + code + " reason=" + reason + " willReconnect=" + willReconnect);
        }


        /** Waits for a publication on the channel whose decoded updates satisfy the predicate. */
        Publication await(
            String channel,
            LiveRealtimeTest test,
            String updateKind,
            String text,
            long timeoutMillis
        ) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                Publication publication = publications.poll(200, TimeUnit.MILLISECONDS);
                if (publication != null && channel.equals(publication.channel) && test.carries(publication, updateKind, text)) {
                    return publication;
                }
            }
            return null;
        }
    }


    private LiveAccounts live;
    private ScheduledExecutorService executor;
    private final List<CentrifugoClient> clients = new ArrayList<CentrifugoClient>();
    private final Random random = new Random();


    @Before
    public void setUp() {
        Assume.assumeTrue(Boolean.getBoolean("impulse.live"));
        live = new LiveAccounts();
        executor = Executors.newScheduledThreadPool(4);
    }


    @After
    public void tearDown() {
        for (CentrifugoClient client : clients) {
            client.disconnect();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }


    @Test
    public void userLaneAndChannelLane() throws Exception {
        String nonce = Long.toHexString(random.nextLong());
        LiveAccounts.Account a = live.freshAccount(1, "RtA");
        LiveAccounts.Account b = live.freshAccount(2, "RtB");
        Map<String, Object> bUser = live.importContact(a, b.phone, "B");
        b.accessHash = (Long) bUser.get("access_hash");
        Map<String, Object> aUser = live.importContact(b, a.phone, "A");
        a.accessHash = (Long) aUser.get("access_hash");

        Collector bEvents = new Collector("B");
        CentrifugoClient bClient = newClient(b, bEvents);
        bClient.connect();
        bClient.subscribe("user:" + b.userId, null);

        // User lane: A writes to B, B receives updateNewMessage with the text within 10 s.
        awaitSubscribed(bEvents, "subscribed user:" + b.userId);
        String directText = "rt-user " + nonce;
        live.sendText(a, live.userPeer(b.userId, b.accessHash), directText);
        Publication direct = bEvents.await("user:" + b.userId, this, "updateNewMessage", directText, PublicationWaitMillis);
        assertNotNull("B got no user-lane publication carrying updateNewMessage with the text", direct);
        assertTrue(direct.envelope.updatesProto != null && direct.envelope.updatesProto.length > 0);
        System.out.println("LIVE user lane OK: type=" + direct.envelope.type + " pts=" + direct.envelope.pts
            + " ptsCount=" + direct.envelope.ptsCount + " offset=" + direct.offset);

        // Channel lane: A creates a megagroup (broadcast writes are closed on the stand) and invites B.
        Map<String, Object> created = live.call(
            a,
            "channels.createChannel",
            TlBuilder.method("channels.createChannel")
                .put("megagroup", Boolean.TRUE)
                .put("title", "rt " + nonce)
                .put("about", "realtime live test")
        );
        Map<String, Object> channel = firstChannel(created);
        long channelId = (Long) channel.get("id");
        long channelHash = (Long) channel.get("access_hash");
        System.out.println("LIVE created channel " + channelId);
        live.call(
            a,
            "channels.inviteToChannel",
            TlBuilder.method("channels.inviteToChannel")
                .put("channel", TlBuilder.object("inputChannel").put("channel_id", channelId).put("access_hash", channelHash))
                .put("users", TlBuilder.list(TlBuilder.object("inputUser").put("user_id", b.userId).put("access_hash", b.accessHash)))
        );

        String bTag = actorTag(b, channelId);
        String aTag = actorTag(a, channelId);
        System.out.println("LIVE actor tags: A=" + aTag + " B=" + bTag);
        assertFalse(bTag.isEmpty());
        assertFalse(aTag.equals(bTag));

        Collector aEvents = new Collector("A");
        CentrifugoClient aClient = newClient(a, aEvents);
        aClient.connect();
        aClient.subscribe("user:" + a.userId, null);
        aClient.subscribe("channel:" + channelId, aTag);
        bClient.subscribe("channel:" + channelId, bTag);
        awaitSubscribed(aEvents, "subscribed channel:" + channelId);
        awaitSubscribed(bEvents, "subscribed channel:" + channelId);

        String postText = "rt-channel " + nonce;
        TlBuilder channelPeer = TlBuilder.object("inputPeerChannel").put("channel_id", channelId).put("access_hash", channelHash);
        try {
            live.sendText(a, channelPeer, postText);
        } catch (AssertionError first) {
            // Known intermittent backend issue: the first post to a just-created channel can return 500. Retried once;
            // any other error is a real failure.
            if (!String.valueOf(first.getMessage()).contains("INTERNAL_SERVER_ERROR")) {
                throw first;
            }
            System.out.println("LIVE BACKEND ISSUE: first post to a fresh channel returned 500, retrying once: " + first.getMessage());
            live.sendText(a, channelPeer, postText);
        }
        Publication post = bEvents.await("channel:" + channelId, this, null, postText, PublicationWaitMillis);
        assertNotNull("B got no channel-lane publication carrying the post", post);
        System.out.println("LIVE channel lane OK: type=" + post.envelope.type + " pts=" + post.envelope.pts
            + " body=" + (post.envelope.updatesProto != null));

        // tf: A's own post must not come back on A's channel lane (A gets it through its user lane).
        Publication echo = aEvents.await("channel:" + channelId, this, null, postText, 5000L);
        System.out.println("LIVE tf filter: A's own post on its channel lane = " + (echo != null));
        assertNull("tf actor!=A should have excluded A's own post on the channel lane", echo);
    }


    /** Six minute hold: observation only. Fails only when publications stop arriving. */
    @Test
    public void longHoldObservation() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("impulse.live.long"));
        String nonce = Long.toHexString(random.nextLong());
        LiveAccounts.Account a = live.freshAccount(1, "HoldA");
        LiveAccounts.Account b = live.freshAccount(2, "HoldB");
        Map<String, Object> bUser = live.importContact(a, b.phone, "B");
        b.accessHash = (Long) bUser.get("access_hash");

        Collector bEvents = new Collector("B-hold");
        CentrifugoClient bClient = newClient(b, bEvents);
        bClient.connect();
        bClient.subscribe("user:" + b.userId, null);
        awaitSubscribed(bEvents, "subscribed user:" + b.userId);

        long start = System.currentTimeMillis();
        long holdMillis = 6L * 60L * 1000L + 15000L;
        long[] sendAtSeconds = {0L, 90L, 180L, 270L, 355L};
        for (int i = 0; i < sendAtSeconds.length; i++) {
            long wait = start + sendAtSeconds[i] * 1000L - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
            String text = "hold-" + i + " " + nonce;
            live.sendText(a, live.userPeer(b.userId, b.accessHash), text);
            Publication got = bEvents.await("user:" + b.userId, this, "updateNewMessage", text, PublicationWaitMillis);
            System.out.println("LIVE hold message " + i + " at +" + (System.currentTimeMillis() - start) / 1000L + "s delivered=" + (got != null));
            assertNotNull("publications stopped arriving at message " + i, got);
        }
        long rest = start + holdMillis - System.currentTimeMillis();
        if (rest > 0) {
            Thread.sleep(rest);
        }
        int unsubscribes = 0;
        int disconnects = 0;
        for (String line : bEvents.log) {
            if (line.contains("UNSUBSCRIBED")) {
                unsubscribes++;
            }
            if (line.contains("DISCONNECTED")) {
                disconnects++;
            }
        }
        System.out.println("LIVE hold summary: tokenMints=" + mintCount.get() + " serverUnsubscribes=" + unsubscribes
            + " disconnects=" + disconnects + " heldSeconds=" + (System.currentTimeMillis() - start) / 1000L);
        System.out.println("LIVE hold events:");
        for (String line : bEvents.log) {
            System.out.println("  " + line);
        }
    }


    private final AtomicInteger mintCount = new AtomicInteger();


    private CentrifugoClient newClient(
        final LiveAccounts.Account account,
        Collector listener
    ) {
        CentrifugoClient client = new CentrifugoClient(
            live.http,
            LiveAccounts.WsUrl,
            new CentrifugoClient.TokenProvider() {
                @Override
                public String fetchToken() throws IOException {
                    mintCount.incrementAndGet();
                    return mintToken(account);
                }
            },
            listener,
            executor
        );
        clients.add(client);
        return client;
    }


    private static String mintToken(LiveAccounts.Account account) throws IOException {
        byte[] response = account.rpc.callImpulse(ImpulseRpcPaths.GetCentrifugoToken, new byte[0]);
        ProtoReader reader = new ProtoReader(response, 0, response.length);
        String token = null;
        while (reader.next()) {
            if (reader.field() == 1) {
                token = new String(reader.readBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } else {
                reader.skip();
            }
        }
        if (token == null || token.isEmpty()) {
            throw new IOException("GetCentrifugoToken returned no token");
        }
        return token;
    }


    private static String actorTag(
        LiveAccounts.Account account,
        long channelId
    ) throws IOException {
        ProtoWriter request = new ProtoWriter();
        request.writePackedVarints(1, new long[] {channelId});
        byte[] response = account.rpc.callImpulse(ImpulseRpcPaths.GetChannelActorTags, request.toByteArray());
        ProtoReader reader = new ProtoReader(response, 0, response.length);
        Map<Long, String> tags = new LinkedHashMap<Long, String>();
        while (reader.next()) {
            if (reader.field() != 1) {
                reader.skip();
                continue;
            }
            int[] bounds = reader.readLengthDelimitedBounds();
            ProtoReader entry = new ProtoReader(response, bounds[0], bounds[1]);
            long key = 0L;
            String value = "";
            while (entry.next()) {
                if (entry.field() == 1) {
                    key = entry.readVarint();
                } else if (entry.field() == 2) {
                    value = new String(entry.readBytes(), java.nio.charset.StandardCharsets.UTF_8);
                } else {
                    entry.skip();
                }
            }
            tags.put(key, value);
        }
        String tag = tags.get(channelId);
        assertNotNull("no actor tag for channel " + channelId + " in " + tags, tag);
        return tag;
    }


    private static Map<String, Object> firstChannel(Map<String, Object> updates) {
        for (Object chat : (List<?>) updates.get("chats")) {
            Map<String, Object> map = LiveAccounts.asMap(chat);
            if ("channel".equals(map.get("_"))) {
                return map;
            }
        }
        throw new AssertionError("createChannel returned no channel: " + updates);
    }


    private static void awaitSubscribed(
        Collector events,
        String marker
    ) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15000L;
        while (System.currentTimeMillis() < deadline) {
            synchronized (events.log) {
                for (String line : events.log) {
                    if (line.contains(marker)) {
                        return;
                    }
                }
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("never saw: " + marker + " in " + events.log);
    }


    /** True when the publication decodes to updates holding a message with the text (under updateKind when given). */
    boolean carries(
        Publication publication,
        String updateKind,
        String text
    ) {
        if (publication.envelope.updatesProto == null) {
            return false;
        }
        byte[] tl = live.transcoder.decodeUpdates(publication.envelope.updatesProto, NoCapture);
        Map<String, Object> updates = live.view.decodeObject(tl);
        return containsMessage(updates, updateKind, text, null);
    }


    private static boolean containsMessage(
        Object node,
        String updateKind,
        String text,
        String holderKind
    ) {
        if (node instanceof Map) {
            Map<String, Object> map = LiveAccounts.asMap(node);
            Object kind = map.get("_");
            String holder = kind instanceof String && ((String) kind).startsWith("update") ? (String) kind : holderKind;
            if (text.equals(map.get("message")) && (updateKind == null || updateKind.equals(holder))) {
                return true;
            }
            for (Object child : map.values()) {
                if (containsMessage(child, updateKind, text, holder)) {
                    return true;
                }
            }
        } else if (node instanceof List) {
            for (Object child : (List<?>) node) {
                if (containsMessage(child, updateKind, text, holderKind)) {
                    return true;
                }
            }
        }
        return false;
    }
}
