package net.impulsem.transport.live;

import static net.impulsem.transport.live.LiveAccounts.asMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import net.impulsem.transport.auth.SessionTokens;
import org.junit.Assume;
import org.junit.Test;


/**
 * Acts as account B for the on-device end-to-end checks. Opt-in with {@code -Dimpulse.live=true}; the action comes from
 * {@code -Ddriver.action=login|send|history|createChannel|post|sendPhoto|fetchPhoto|call|burst|oldRefresh}. Account B persists between runs in the file named by
 * {@code -Ddriver.state} (default {@code ../branding-out/driver-state.properties}). Other parameters:
 * {@code driver.peerPhone} (the device account), {@code driver.text}, {@code driver.expect} (history must contain it).
 */
public class DeviceDriver {

    private static final String PhoneKey = "phone";
    private static final String ChannelIdKey = "channelId";
    private static final String ChannelHashKey = "channelHash";

    private final LiveAccounts live = new LiveAccounts();
    private final Random random = new Random();


    @Test
    public void run() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("impulse.live"));
        String action = System.getProperty("driver.action", "");
        Assume.assumeTrue("no driver.action", !action.isEmpty());
        File stateFile = new File(System.getProperty("driver.state", "../branding-out/driver-state.properties"));
        Properties state = new Properties();
        if (stateFile.exists()) {
            FileInputStream in = new FileInputStream(stateFile);
            try {
                state.load(in);
            } finally {
                in.close();
            }
        }
        if ("oldRefresh".equals(action)) {
            oldRefresh();
            return;
        }
        LiveAccounts.Account b;
        if ("login".equals(action)) {
            b = live.freshAccount(2, "Driver");
            state.clear();
            state.setProperty(PhoneKey, b.phone);
        } else {
            assertNotNull("run action=login first", state.getProperty(PhoneKey));
            b = live.restoreAccount(
                state.getProperty(PhoneKey),
                "22222",
                new SessionTokens(state.getProperty("access"), state.getProperty("refresh"), Long.parseLong(state.getProperty("userId")))
            );
        }
        try {
            if ("login".equals(action)) {
                System.out.println("DRIVER logged in as " + b.phone + " userId=" + b.userId);
            } else if ("send".equals(action)) {
                send(b);
            } else if ("history".equals(action)) {
                history(b);
            } else if ("createChannel".equals(action)) {
                createChannel(b, state);
            } else if ("post".equals(action)) {
                post(b, state);
            } else if ("sendPhoto".equals(action)) {
                sendPhoto(b);
            } else if ("fetchPhoto".equals(action)) {
                fetchPhoto(b);
            } else if ("call".equals(action)) {
                call(b);
            } else if ("burst".equals(action)) {
                burst(b);
            } else {
                throw new IllegalArgumentException("unknown driver.action " + action);
            }
        } finally {
            SessionTokens saved = live.currentSession(b);
            if (saved != null) {
                state.setProperty("access", saved.accessToken);
                state.setProperty("refresh", saved.refreshToken);
                state.setProperty("userId", Long.toString(saved.userId));
            }
            save(stateFile, state);
        }
    }


    private List<Object> historyList(LiveAccounts.Account b) throws IOException {
        Map<String, Object> a = peer(b);
        Map<String, Object> history = live.call(
            b,
            "messages.getHistory",
            TlBuilder.method("messages.getHistory")
                .put("peer", live.userPeer((Long) a.get("id"), (Long) a.get("access_hash")))
                .put("offset_id", 0)
                .put("offset_date", 0)
                .put("add_offset", 0)
                .put("limit", 50)
                .put("max_id", 0)
                .put("min_id", 0)
                .put("hash", 0L)
        );
        return new ArrayList<Object>((List<?>) history.get("messages"));
    }


    /** Sends a JPEG to the device account as a photo. */
    private void sendPhoto(LiveAccounts.Account b) throws IOException {
        Map<String, Object> a = peer(b);
        long fileId = random.nextLong();
        live.result(
            b,
            "upload.saveFilePart",
            TlBuilder.method("upload.saveFilePart")
                .put("file_id", fileId)
                .put("file_part", 0)
                .put("bytes", LiveRpcTest.deterministicJpeg())
        );
        live.call(
            b,
            "messages.sendMedia",
            TlBuilder.method("messages.sendMedia")
                .put("peer", live.userPeer((Long) a.get("id"), (Long) a.get("access_hash")))
                .put(
                    "media",
                    TlBuilder.object("inputMediaUploadedPhoto").put(
                        "file",
                        TlBuilder.object("inputFile")
                            .put("id", fileId)
                            .put("parts", 1)
                            .put("name", "p.jpg")
                            .put("md5_checksum", "")
                    )
                )
                .put("message", "")
                .put("random_id", random.nextLong())
        );
        System.out.println("DRIVER sent photo to " + a.get("id"));
    }


    /** Downloads the newest photo in the chat with the device account; it must be a non-empty JPEG. */
    private void fetchPhoto(LiveAccounts.Account b) throws IOException {
        for (Object item : historyList(b)) {
            Map<String, Object> message = asMap(item);
            Object media = message.get("media");
            if (media == null || !"messageMediaPhoto".equals(asMap(media).get("_"))) {
                continue;
            }
            Map<String, Object> photo = asMap(asMap(media).get("photo"));
            Map<String, Object> file = live.call(
                b,
                "upload.getFile",
                TlBuilder.method("upload.getFile")
                    .put(
                        "location",
                        TlBuilder.object("inputPhotoFileLocation")
                            .put("id", photo.get("id"))
                            .put("access_hash", photo.get("access_hash"))
                            .put("file_reference", photo.get("file_reference"))
                            .put("thumb_size", "x")
                    )
                    .put("offset", 0)
                    .put("limit", 524288)
            );
            byte[] bytes = (byte[]) file.get("bytes");
            assertTrue("empty photo", bytes.length > 2);
            assertTrue("not a JPEG", (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8);
            System.out.println("DRIVER fetched photo message=" + message.get("id") + " out=" + message.get("out") + " bytes=" + bytes.length);
            return;
        }
        throw new AssertionError("no photo in the chat");
    }


    /**
     * Asserts that the messages with the prefix {@code driver.prefix} are exactly prefix + sep + 1 to count
     * ({@code driver.count}), once each and in that order.
     */
    private void burst(LiveAccounts.Account b) throws IOException {
        String prefix = System.getProperty("driver.prefix", "burst");
        String separator = System.getProperty("driver.sep", " ");
        int count = Integer.parseInt(System.getProperty("driver.count", "5"));
        List<Object> messages = historyList(b);
        List<String> seen = new ArrayList<String>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> message = asMap(messages.get(i));
            Object text = message.get("message");
            if (text instanceof String && ((String) text).startsWith(prefix)) {
                seen.add((String) text);
                System.out.println("DRIVER burst id=" + message.get("id") + " out=" + message.get("out") + " text=" + text);
            }
        }
        List<String> want = new ArrayList<String>();
        for (int i = 1; i <= count; i++) {
            want.add(prefix + separator + i);
        }
        assertEquals(want, seen);
        System.out.println("DRIVER burst order ok, no duplicates");
    }


    /** Uses a refresh token the device has invalidated; the server must reject it. */
    private void oldRefresh() throws IOException {
        String old = System.getProperty("driver.oldRefresh");
        assertNotNull("driver.oldRefresh is required", old);
        LiveAccounts.Account stale = live.restoreAccount("0", "0", new SessionTokens("x", old, 1L));
        boolean refreshed = stale.tokens.refreshBlocking();
        System.out.println("DRIVER old refresh token accepted=" + refreshed);
        assertFalse("old refresh token still works", refreshed);
    }


    /** Calls the device account, completes the DH exchange once it answers, holds the call, then hangs up. */
    private void call(LiveAccounts.Account b) throws Exception {
        Map<String, Object> a = peer(b);
        Map<String, Object> state = live.call(b, "updates.getState", TlBuilder.method("updates.getState"));
        Map<String, Object> dh = live.call(
            b,
            "messages.getDhConfig",
            TlBuilder.method("messages.getDhConfig").put("version", 0).put("random_length", 256)
        );
        BigInteger p = new BigInteger(1, (byte[]) dh.get("p"));
        BigInteger g = BigInteger.valueOf(((Number) dh.get("g")).longValue());
        byte[] salt = new byte[256];
        random.nextBytes(salt);
        BigInteger exponent = new BigInteger(1, salt);
        byte[] ga = pad256(g.modPow(exponent, p));
        byte[] gaHash = MessageDigest.getInstance("SHA-256").digest(ga);
        TlBuilder protocol = TlBuilder.object("phoneCallProtocol")
            .put("udp_p2p", Boolean.TRUE)
            .put("udp_reflector", Boolean.TRUE)
            .put("min_layer", 65)
            .put("max_layer", 92)
            .put("library_versions", TlBuilder.list("impulsem-livekit-1"));
        Map<String, Object> requested = live.call(
            b,
            "phone.requestCall",
            TlBuilder.method("phone.requestCall")
                .put("user_id", TlBuilder.object("inputUser").put("user_id", a.get("id")).put("access_hash", a.get("access_hash")))
                .put("random_id", random.nextInt())
                .put("g_a_hash", gaHash)
                .put("protocol", protocol)
        );
        Map<String, Object> call = asMap(requested.get("phone_call"));
        long callId = (Long) call.get("id");
        long accessHash = (Long) call.get("access_hash");
        System.out.println("DRIVER call requested id=" + callId + " type=" + call.get("_"));
        Map<String, Object> accepted = null;
        long deadline = System.currentTimeMillis() + 240000L;
        while (accepted == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(1000L);
            Map<String, Object> diff = live.call(
                b,
                "updates.getDifference",
                TlBuilder.method("updates.getDifference")
                    .put("pts", state.get("pts"))
                    .put("date", state.get("date"))
                    .put("qts", state.get("qts"))
            );
            if ("updates.differenceEmpty".equals(diff.get("_"))) {
                continue;
            }
            Object nextState = diff.containsKey("state") ? diff.get("state") : diff.get("intermediate_state");
            if (nextState != null) {
                state = asMap(nextState);
            }
            for (Object item : (List<?>) diff.get("other_updates")) {
                Map<String, Object> update = asMap(item);
                System.out.println("DRIVER update " + update.get("_"));
                if (!"updatePhoneCall".equals(update.get("_"))) {
                    continue;
                }
                Map<String, Object> pc = asMap(update.get("phone_call"));
                System.out.println("DRIVER phone_call " + pc.get("_"));
                if ("phoneCallAccepted".equals(pc.get("_"))) {
                    accepted = pc;
                } else if ("phoneCallDiscarded".equals(pc.get("_"))) {
                    throw new AssertionError("call discarded");
                }
            }
        }
        assertNotNull("not accepted in time", accepted);
        BigInteger key = new BigInteger(1, (byte[]) accepted.get("g_b")).modPow(exponent, p);
        byte[] authKey = pad256(key);
        byte[] sha = MessageDigest.getInstance("SHA-1").digest(authKey);
        long fingerprint = 0;
        for (int i = 0; i < 8; i++) {
            fingerprint |= (sha[sha.length - 8 + i] & 0xFFL) << (8 * i);
        }
        Map<String, Object> confirmed = live.call(
            b,
            "phone.confirmCall",
            TlBuilder.method("phone.confirmCall")
                .put("peer", TlBuilder.object("inputPhoneCall").put("id", callId).put("access_hash", accessHash))
                .put("g_a", ga)
                .put("key_fingerprint", fingerprint)
                .put("protocol", protocol)
        );
        System.out.println("DRIVER confirmed " + asMap(confirmed.get("phone_call")).get("_"));
        long hold = Long.parseLong(System.getProperty("driver.hold", "90"));
        System.out.println("DRIVER holding " + hold + "s");
        Thread.sleep(hold * 1000L);
        live.call(
            b,
            "phone.discardCall",
            TlBuilder.method("phone.discardCall")
                .put("peer", TlBuilder.object("inputPhoneCall").put("id", callId).put("access_hash", accessHash))
                .put("duration", (int) hold)
                .put("reason", TlBuilder.object("phoneCallDiscardReasonHangup"))
                .put("connection_id", 0L)
        );
        System.out.println("DRIVER call discarded");
    }


    private static byte[] pad256(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[256];
        if (raw.length > 256) {
            System.arraycopy(raw, raw.length - 256, out, 0, 256);
        } else {
            System.arraycopy(raw, 0, out, 256 - raw.length, raw.length);
        }
        return out;
    }


    private Map<String, Object> peer(LiveAccounts.Account b) throws IOException {
        String phone = System.getProperty("driver.peerPhone");
        assertNotNull("driver.peerPhone is required", phone);
        return live.importContact(b, phone, "Device");
    }


    private void send(LiveAccounts.Account b) throws IOException {
        Map<String, Object> a = peer(b);
        String text = System.getProperty("driver.text", "ping");
        live.sendText(b, live.userPeer((Long) a.get("id"), (Long) a.get("access_hash")), text);
        System.out.println("DRIVER sent '" + text + "' to " + a.get("id") + " at " + System.currentTimeMillis());
    }


    private void history(LiveAccounts.Account b) throws IOException {
        Map<String, Object> a = peer(b);
        Map<String, Object> history = live.call(
            b,
            "messages.getHistory",
            TlBuilder.method("messages.getHistory")
                .put("peer", live.userPeer((Long) a.get("id"), (Long) a.get("access_hash")))
                .put("offset_id", 0)
                .put("offset_date", 0)
                .put("add_offset", 0)
                .put("limit", 20)
                .put("max_id", 0)
                .put("min_id", 0)
                .put("hash", 0L)
        );
        String expect = System.getProperty("driver.expect");
        boolean found = false;
        for (Object item : (List<?>) history.get("messages")) {
            Map<String, Object> message = asMap(item);
            System.out.println("DRIVER history id=" + message.get("id") + " out=" + message.get("out") + " text=" + message.get("message"));
            if (expect != null && expect.equals(message.get("message"))) {
                found = true;
            }
        }
        if (expect != null) {
            assertTrue("history lacks '" + expect + "'", found);
            System.out.println("DRIVER history contains '" + expect + "'");
        }
    }


    private void createChannel(
        LiveAccounts.Account b,
        Properties state
    ) throws IOException {
        Map<String, Object> a = peer(b);
        Map<String, Object> created = live.call(
            b,
            "channels.createChannel",
            TlBuilder.method("channels.createChannel")
                .put("megagroup", Boolean.TRUE)
                .put("title", "drv " + Long.toHexString(random.nextLong()))
                .put("about", "")
        );
        Map<String, Object> channel = asMap(((List<?>) created.get("chats")).get(0));
        long channelId = (Long) channel.get("id");
        long channelHash = (Long) channel.get("access_hash");
        state.setProperty(ChannelIdKey, Long.toString(channelId));
        state.setProperty(ChannelHashKey, Long.toString(channelHash));
        System.out.println("DRIVER created megagroup " + channelId + " title=" + channel.get("title"));
        live.call(
            b,
            "channels.inviteToChannel",
            TlBuilder.method("channels.inviteToChannel")
                .put("channel", TlBuilder.object("inputChannel").put("channel_id", channelId).put("access_hash", channelHash))
                .put(
                    "users",
                    TlBuilder.list(TlBuilder.object("inputUser").put("user_id", a.get("id")).put("access_hash", a.get("access_hash")))
                )
        );
        System.out.println("DRIVER invited " + a.get("id"));
    }


    private void post(
        LiveAccounts.Account b,
        Properties state
    ) throws IOException {
        long channelId = Long.parseLong(state.getProperty(ChannelIdKey));
        long channelHash = Long.parseLong(state.getProperty(ChannelHashKey));
        String text = System.getProperty("driver.text", "post");
        live.sendText(b, TlBuilder.object("inputPeerChannel").put("channel_id", channelId).put("access_hash", channelHash), text);
        System.out.println("DRIVER posted '" + text + "' to " + channelId + " at " + System.currentTimeMillis());
    }


    private static void save(
        File file,
        Properties state
    ) throws IOException {
        file.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(file);
        try {
            state.store(out, "device driver state");
        } finally {
            out.close();
        }
    }
}
