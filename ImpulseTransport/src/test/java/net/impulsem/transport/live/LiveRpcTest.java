package net.impulsem.transport.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import net.impulsem.transport.auth.Clock;
import net.impulsem.transport.auth.SessionStore;
import net.impulsem.transport.auth.SessionTokens;
import net.impulsem.transport.auth.TokenManager;
import net.impulsem.transport.codec.Transcoder;
import net.impulsem.transport.grpcweb.GrpcWebClient;
import net.impulsem.transport.rpc.RpcClient;
import net.impulsem.transport.rpc.RpcOutcome;
import net.impulsem.transport.schema.TlProtoSchema;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;


/** Opt-in live contract test: {@code -Dimpulse.live=true}. Talks to the public ImpulseM stand. */
public class LiveRpcTest {

    private static final String Endpoint = "https://rpc.impulsem.net";
    private static final int ApiId = 3;
    private static final String ApiHash = "85e6b3be52309e397e6b4b2d688c7ca4";
    private static final long PreAuthPauseMillis = 300L;

    private static final class MemoryStore implements SessionStore {

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


    private static final class Account {

        final String phone;
        final String code;
        final MemoryStore store = new MemoryStore();
        TokenManager tokens;
        RpcClient rpc;
        long userId;
        long accessHash;


        Account(
            String phone,
            String code
        ) {
            this.phone = phone;
            this.code = code;
        }
    }


    private TlProtoSchema schema;
    private TlView view;
    private Transcoder transcoder;
    private GrpcWebClient grpc;
    private final Random random = new Random();


    @Before
    public void setUp() {
        Assume.assumeTrue(Boolean.getBoolean("impulse.live"));
        schema = TlProtoSchema.load();
        view = new TlView(schema);
        transcoder = new Transcoder(schema);
        OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
        grpc = new GrpcWebClient(http, HttpUrl.get(Endpoint));
    }


    @Test
    public void fullScenario() throws Exception {
        String nonce = Long.toHexString(random.nextLong());

        // 1. help.getConfig, pre-auth.
        Account anonymous = newAccount("0000000000", "00000");
        Map<String, Object> config = call(anonymous, "help.getConfig", TlBuilder.method("help.getConfig"));
        assertEquals("config", config.get("_"));
        assertEquals(1, config.get("this_dc"));
        assertEquals(Boolean.TRUE, config.get("test_mode"));
        pause();

        // 2. Log in two fresh accounts.
        Account a = newAccount(randomPhone(1), "11111");
        Account b = newAccount(randomPhone(2), "22222");
        login(a, "A");
        login(b, "B");
        assertTrue(a.tokens.hasSession());
        assertTrue(b.tokens.hasSession());
        assertNotNull(a.store.load());
        assertFalse(a.store.load().accessToken.isEmpty());
        assertFalse(a.store.load().refreshToken.isEmpty());
        assertFalse(b.store.load().accessToken.equals(a.store.load().accessToken));

        // 3. users.getUsers([inputUserSelf]).
        for (Account account : new Account[] {a, b}) {
            Map<String, Object> self = selfUser(account);
            assertEquals("user", self.get("_"));
            assertEquals(Boolean.TRUE, self.get("self"));
            account.userId = (Long) self.get("id");
            assertEquals(account.userId, account.store.load().userId);
        }

        // 4. updates.getState.
        Map<String, Object> state = call(a, "updates.getState", TlBuilder.method("updates.getState"));
        assertEquals("updates.state", state.get("_"));
        assertTrue((Integer) state.get("pts") >= 0);

        // 5. A resolves B (and B resolves A, to obtain the access hash for the history peer).
        Map<String, Object> bUser = importContact(a, b.phone, "B");
        assertEquals(b.userId, bUser.get("id"));
        b.accessHash = (Long) bUser.get("access_hash");
        Map<String, Object> aUser = importContact(b, a.phone, "A");
        assertEquals(a.userId, aUser.get("id"));
        a.accessHash = (Long) aUser.get("access_hash");

        // 6. A sends text to B.
        String text = "hello " + nonce;
        Map<String, Object> sent = call(
            a,
            "messages.sendMessage",
            TlBuilder.method("messages.sendMessage")
                .put("peer", peer(b))
                .put("message", text)
                .put("random_id", random.nextLong())
        );
        System.out.println("LIVE sendMessage -> " + sent.get("_"));
        assertSentUpdates(sent);

        // 7. B reads the history.
        Map<String, Object> history = getHistory(b, a);
        assertTrue("history of B lacks the text: " + history, findMessage(history, text) != null);

        // 8. Photo upload and download.
        byte[] jpeg = deterministicJpeg();
        System.out.println("LIVE jpeg bytes = " + jpeg.length);
        long fileId = random.nextLong();
        Object saved = result(
            a,
            "upload.saveFilePart",
            TlBuilder.method("upload.saveFilePart")
                .put("file_id", fileId)
                .put("file_part", 0)
                .put("bytes", jpeg)
        );
        assertEquals(Boolean.TRUE, saved);
        Map<String, Object> mediaSent = call(
            a,
            "messages.sendMedia",
            TlBuilder.method("messages.sendMedia")
                .put("peer", peer(b))
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
        System.out.println("LIVE sendMedia -> " + mediaSent.get("_"));
        assertSentUpdates(mediaSent);

        Map<String, Object> bHistory = getHistory(b, a);
        Map<String, Object> photoMessage = findMessageWithMedia(bHistory, "messageMediaPhoto");
        assertNotNull("no messageMediaPhoto in B history: " + bHistory, photoMessage);
        @SuppressWarnings("unchecked")
        Map<String, Object> media = (Map<String, Object>) photoMessage.get("media");
        @SuppressWarnings("unchecked")
        Map<String, Object> photo = (Map<String, Object>) media.get("photo");
        assertEquals("photo", photo.get("_"));
        System.out.println("LIVE photo sizes = " + photo.get("sizes"));
        Map<String, Object> file = call(
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
        assertEquals("upload.file", file.get("_"));
        byte[] downloaded = (byte[]) file.get("bytes");
        assertTrue("empty upload.file bytes", downloaded.length > 0);
        System.out.println("LIVE downloaded bytes = " + downloaded.length);

        // 9. Refresh the session of A.
        String before = a.tokens.bearer();
        assertTrue(a.tokens.refreshBlocking());
        assertNotNull(a.tokens.bearer());
        System.out.println("LIVE refresh rotated access token = " + !before.equals(a.tokens.bearer()));
        Map<String, Object> stateAfter = call(a, "updates.getState", TlBuilder.method("updates.getState"));
        assertEquals("updates.state", stateAfter.get("_"));

        // 10. B logs out; the session must die.
        Map<String, Object> loggedOut = call(b, "auth.logOut", TlBuilder.method("auth.logOut"));
        assertEquals("auth.loggedOut", loggedOut.get("_"));
        RpcOutcome dead = b.rpc.callBlocking(TlBuilder.method("updates.getState").toBytes());
        System.out.println("LIVE after logOut: error=" + dead.error + " forceLogout=" + dead.forceLogout);
        assertNull(dead.tlResult);
        assertTrue("expected forceLogout or 401, got " + dead.error, dead.forceLogout || (dead.error != null && dead.error.code == 401));
    }


    private Account newAccount(
        String phone,
        String code
    ) {
        Account account = new Account(phone, code);
        account.tokens = new TokenManager(account.store, grpc, Clock.SYSTEM);
        account.rpc = new RpcClient(transcoder, grpc, account.tokens);
        return account;
    }


    private String randomPhone(int x) {
        return "99966" + x + String.format("%04d", random.nextInt(10000));
    }


    private static void pause() throws InterruptedException {
        Thread.sleep(PreAuthPauseMillis);
    }


    private void login(
        Account account,
        String lastName
    ) throws Exception {
        Map<String, Object> sentCode = call(
            account,
            "auth.sendCode",
            TlBuilder.method("auth.sendCode")
                .put("phone_number", account.phone)
                .put("api_id", ApiId)
                .put("api_hash", ApiHash)
                .put("settings", TlBuilder.object("codeSettings"))
        );
        assertEquals("auth.sentCode", sentCode.get("_"));
        String hash = (String) sentCode.get("phone_code_hash");
        assertFalse(hash.isEmpty());
        pause();

        Map<String, Object> signIn = call(
            account,
            "auth.signIn",
            TlBuilder.method("auth.signIn")
                .put("phone_number", account.phone)
                .put("phone_code_hash", hash)
                .put("phone_code", account.code)
        );
        pause();
        Map<String, Object> authorization = signIn;
        if ("auth.authorizationSignUpRequired".equals(signIn.get("_"))) {
            authorization = call(
                account,
                "auth.signUp",
                TlBuilder.method("auth.signUp")
                    .put("phone_number", account.phone)
                    .put("phone_code_hash", hash)
                    .put("first_name", "Live")
                    .put("last_name", lastName)
            );
            pause();
        }
        assertEquals("auth.authorization", authorization.get("_"));
        assertTrue("no token pair captured", account.tokens.hasSession());
    }


    private Map<String, Object> selfUser(Account account) throws IOException {
        Object users = result(
            account,
            "users.getUsers",
            TlBuilder.method("users.getUsers").put("id", TlBuilder.list(TlBuilder.object("inputUserSelf")))
        );
        List<?> list = (List<?>) users;
        assertEquals(1, list.size());
        return asMap(list.get(0));
    }


    private Map<String, Object> importContact(
        Account account,
        String phone,
        String name
    ) throws IOException {
        Map<String, Object> imported = call(
            account,
            "contacts.importContacts",
            TlBuilder.method("contacts.importContacts").put(
                "contacts",
                TlBuilder.list(
                    TlBuilder.object("inputPhoneContact")
                        .put("client_id", 1L)
                        .put("phone", phone)
                        .put("first_name", name)
                        .put("last_name", "")
                )
            )
        );
        assertEquals("contacts.importedContacts", imported.get("_"));
        List<?> users = (List<?>) imported.get("users");
        assertEquals(1, users.size());
        return asMap(users.get(0));
    }


    private TlBuilder peer(Account target) {
        return TlBuilder.object("inputPeerUser")
            .put("user_id", target.userId)
            .put("access_hash", target.accessHash);
    }


    private Map<String, Object> getHistory(
        Account reader,
        Account other
    ) throws IOException {
        return call(
            reader,
            "messages.getHistory",
            TlBuilder.method("messages.getHistory")
                .put("peer", peer(other))
                .put("offset_id", 0)
                .put("offset_date", 0)
                .put("add_offset", 0)
                .put("limit", 10)
                .put("max_id", 0)
                .put("min_id", 0)
                .put("hash", 0L)
        );
    }


    private void assertSentUpdates(Map<String, Object> sent) {
        String kind = (String) sent.get("_");
        if ("updateShortSentMessage".equals(kind)) {
            return;
        }
        assertTrue("unexpected result " + kind, "updates".equals(kind) || "updatesCombined".equals(kind));
        boolean hasMessageId = false;
        for (Object update : (List<?>) sent.get("updates")) {
            if ("updateMessageID".equals(asMap(update).get("_"))) {
                hasMessageId = true;
            }
        }
        assertTrue("no updateMessageID in " + sent, hasMessageId);
    }


    private Map<String, Object> findMessage(
        Map<String, Object> history,
        String text
    ) {
        for (Object message : (List<?>) history.get("messages")) {
            Map<String, Object> map = asMap(message);
            if (text.equals(map.get("message"))) {
                return map;
            }
        }
        return null;
    }


    private Map<String, Object> findMessageWithMedia(
        Map<String, Object> history,
        String mediaPredicate
    ) {
        for (Object message : (List<?>) history.get("messages")) {
            Map<String, Object> map = asMap(message);
            Object media = map.get("media");
            if (media != null && mediaPredicate.equals(asMap(media).get("_"))) {
                return map;
            }
        }
        return null;
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }


    private Map<String, Object> call(
        Account account,
        String method,
        TlBuilder request
    ) throws IOException {
        return asMap(result(account, method, request));
    }


    private Object result(
        Account account,
        String method,
        TlBuilder request
    ) throws IOException {
        RpcOutcome outcome = account.rpc.callBlocking(request.toBytes());
        if (outcome.error != null) {
            throw new AssertionError(method + " failed: " + outcome.error + " forceLogout=" + outcome.forceLogout);
        }
        assertNotNull(method, outcome.tlResult);
        return view.result(method, outcome.tlResult);
    }


    /** About 40 KB of seeded block noise, 400 px so the server derives the x size, so the bytes are identical on every run. */
    static byte[] deterministicJpeg() throws IOException {
        int side = 400;
        BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        Random noise = new Random(42L);
        int[] blocks = new int[(side / 4) * (side / 4)];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = noise.nextInt(0x1000000);
        }
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                image.setRGB(x, y, blocks[(y / 4) * (side / 4) + (x / 4)]);
            }
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.4f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out);
        writer.setOutput(stream);
        writer.write(null, new IIOImage(image, new ArrayList<BufferedImage>(), null), param);
        stream.close();
        writer.dispose();
        return out.toByteArray();
    }
}
