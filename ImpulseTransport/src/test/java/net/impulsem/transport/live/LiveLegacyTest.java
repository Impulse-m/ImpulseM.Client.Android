package net.impulsem.transport.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.impulsem.transport.live.LiveAccounts.Account;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;


/**
 * Opt-in live test of legacy constructor support: {@code -Dimpulse.live=true}. Requests are built
 * with the layouts of older layers and sent through the same RpcClient the app uses.
 */
public class LiveLegacyTest {

    private static final int GetDifferenceLegacy = 0x25939651;
    private static final int GetMessagesLegacy = 0x4222fa74;
    private static final int ChannelsGetMessagesLegacy = 0x93d7b347;
    private static final int UploadMediaLegacy = 0x519bc2b1;

    private LiveAccounts live;
    private final Random random = new Random();


    @Before
    public void setUp() {
        Assume.assumeTrue(Boolean.getBoolean("impulse.live"));
        live = new LiveAccounts();
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }


    private static Map<String, Object> findText(
        List<?> messages,
        String text
    ) {
        for (Object message : messages) {
            Map<String, Object> map = asMap(message);
            if (text.equals(map.get("message"))) {
                return map;
            }
        }
        return null;
    }


    private Map<String, Object> legacyCall(
        Account account,
        String method,
        TlBuilder request
    ) throws IOException {
        return live.call(account, method, request);
    }


    @Test
    public void legacyRequestsWork() throws Exception {
        String nonce = Long.toHexString(random.nextLong());
        Account a = live.freshAccount(1, "A");
        Account b = live.freshAccount(2, "B");
        a.accessHash = (Long) live.importContact(a, b.phone, "B").get("access_hash");

        // B remembers its state, then A sends a message to B.
        Map<String, Object> state = live.call(b, "updates.getState", TlBuilder.method("updates.getState"));
        String text = "legacy " + nonce;
        live.sendText(a, live.userPeer(b.userId, a.accessHash), text);

        // 1. updates.getDifference#25939651 returns the message (delivery to B is asynchronous).
        Map<String, Object> difference = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            Thread.sleep(1000L);
            difference = legacyCall(
                b,
                "updates.getDifference",
                TlBuilder.legacyMethod(GetDifferenceLegacy)
                    .put("pts", state.get("pts"))
                    .put("date", state.get("date"))
                    .put("qts", state.get("qts"))
            );
            if (!"updates.differenceEmpty".equals(difference.get("_"))) {
                break;
            }
        }
        System.out.println("LIVE legacy getDifference -> " + difference.get("_"));
        String kind = (String) difference.get("_");
        assertTrue("unexpected " + kind, "updates.difference".equals(kind) || "updates.differenceSlice".equals(kind));
        Map<String, Object> received = findText((List<?>) difference.get("new_messages"), text);
        assertNotNull("difference lacks the sent message: " + difference, received);
        int messageId = ((Number) received.get("id")).intValue();

        // 2. messages.getMessages#4222fa74 with the plain id returns it.
        Map<String, Object> messages = legacyCall(
            b,
            "messages.getMessages",
            TlBuilder.legacyMethod(GetMessagesLegacy).put("id", TlBuilder.list(Integer.valueOf(messageId)))
        );
        System.out.println("LIVE legacy getMessages -> " + messages.get("_"));
        assertNotNull("getMessages lacks the message: " + messages, findText((List<?>) messages.get("messages"), text));

        // 3. messages.uploadMedia#519bc2b1 (no flags, no business connection) accepts an uploaded photo.
        long fileId = random.nextLong();
        Object saved = live.result(
            a,
            "upload.saveFilePart",
            TlBuilder.method("upload.saveFilePart")
                .put("file_id", fileId)
                .put("file_part", 0)
                .put("bytes", LiveRpcTest.deterministicJpeg())
        );
        assertEquals(Boolean.TRUE, saved);
        Map<String, Object> uploaded = legacyCall(
            a,
            "messages.uploadMedia",
            TlBuilder.legacyMethod(UploadMediaLegacy)
                .put("peer", TlBuilder.object("inputPeerSelf"))
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
        );
        System.out.println("LIVE legacy uploadMedia -> " + uploaded.get("_"));
        assertEquals("messageMediaPhoto", uploaded.get("_"));
    }


    @Test
    public void legacyChannelsGetMessages() throws Exception {
        String nonce = Long.toHexString(random.nextLong());
        Account a = live.freshAccount(3, "C");
        Map<String, Object> created = live.call(
            a,
            "channels.createChannel",
            TlBuilder.method("channels.createChannel")
                .put("megagroup", Boolean.TRUE)
                .put("title", "legacy " + nonce)
                .put("about", "")
        );
        List<?> chats = (List<?>) created.get("chats");
        Map<String, Object> channel = asMap(chats.get(0));
        long channelId = (Long) channel.get("id");
        long accessHash = (Long) channel.get("access_hash");
        String text = "channel " + nonce;
        live.call(
            a,
            "messages.sendMessage",
            TlBuilder.method("messages.sendMessage")
                .put("peer", TlBuilder.object("inputPeerChannel").put("channel_id", channelId).put("access_hash", accessHash))
                .put("message", text)
                .put("random_id", random.nextLong())
        );
        Map<String, Object> history = live.call(
            a,
            "messages.getHistory",
            TlBuilder.method("messages.getHistory")
                .put("peer", TlBuilder.object("inputPeerChannel").put("channel_id", channelId).put("access_hash", accessHash))
                .put("offset_id", 0)
                .put("offset_date", 0)
                .put("add_offset", 0)
                .put("limit", 10)
                .put("max_id", 0)
                .put("min_id", 0)
                .put("hash", 0L)
        );
        Map<String, Object> sent = findText((List<?>) history.get("messages"), text);
        assertNotNull("history lacks the channel message: " + history, sent);
        int messageId = ((Number) sent.get("id")).intValue();

        Map<String, Object> messages = legacyCall(
            a,
            "channels.getMessages",
            TlBuilder.legacyMethod(ChannelsGetMessagesLegacy)
                .put("channel", TlBuilder.object("inputChannel").put("channel_id", channelId).put("access_hash", accessHash))
                .put("id", TlBuilder.list(Integer.valueOf(messageId)))
        );
        System.out.println("LIVE legacy channels.getMessages -> " + messages.get("_"));
        assertNotNull("channels.getMessages lacks the message: " + messages, findText((List<?>) messages.get("messages"), text));
    }
}
