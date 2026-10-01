package net.impulsem.transport.live;

import static net.impulsem.transport.live.LiveAccounts.asMap;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import net.impulsem.transport.auth.SessionTokens;
import org.junit.Assume;
import org.junit.Test;


/**
 * Acts as account B for the on-device end-to-end checks. Opt-in with {@code -Dimpulse.live=true}; the action comes from
 * {@code -Ddriver.action=login|send|history|createChannel|post}. Account B persists between runs in the file named by
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
