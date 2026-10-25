package net.impulsem.transport.live;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import net.impulsem.transport.live.LiveAccounts.Account;
import net.impulsem.transport.realtime.Publication;
import net.impulsem.transport.rpc.RpcOutcome;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;


/**
 * Opt-in live contract test: {@code -Dimpulse.live=true}. Talks to the public ImpulseM stand over the same transport
 * the app runs: the update socket, the pool fleet and the media fleet.
 */
public class LiveRpcTest {

    private static final long PreAuthPauseMillis = 300L;
    private static final long PublicationWaitMillis = 15000L;
    private static final long DownloadWaitMillis = 60000L;
    private static final int PartBytes = 512 * 1024;
    private static final int ChunkBytes = 512 * 1024;
    private static final int BlobBytes = 600000;

    private LiveAccounts live;
    private final Random random = new Random();


    @Before
    public void setUp() {
        Assume.assumeTrue(Boolean.getBoolean("impulse.live"));
        live = new LiveAccounts();
    }


    @After
    public void tearDown() {
        if (live != null) {
            live.close();
        }
    }


    @Test
    public void fullScenario() throws Exception {
        String nonce = Long.toHexString(random.nextLong());

        // 1. help.getConfig, pre-auth, over the identity socket.
        Account anonymous = live.newAccount("0000000000", "00000");
        assertFalse("a signed-out account must not hold a session", anonymous.stack.credentials.signedIn());
        Map<String, Object> config = live.fetchConfig(anonymous);
        assertNotNull("the signed-out socket presented no identity", anonymous.stack.identity.current());
        assertEquals("config", config.get("_"));
        assertEquals(1, config.get("this_dc"));
        assertEquals(Boolean.TRUE, config.get("test_mode"));
        assertNotNull("help.getConfig has no media_only option for this_dc", LiveStack.mediaEndpoint(config));
        pause();

        // 2. Log in two fresh accounts over the identity socket.
        Account a = live.newAccount(randomPhone(1), "11111");
        Account b = live.newAccount(randomPhone(2), "22222");
        a.stack.applyConfig(config);
        b.stack.applyConfig(config);
        live.login(a, "A");
        live.login(b, "B");
        assertTrue(a.stack.tokens.hasSession());
        assertTrue(b.stack.tokens.hasSession());
        assertNotNull(a.stack.session());
        assertFalse(a.stack.session().accessToken.isEmpty());
        assertFalse(a.stack.session().refreshToken.isEmpty());
        assertFalse(b.stack.session().accessToken.equals(a.stack.session().accessToken));
        for (Account account : new Account[] {a, b}) {
            // The sign-in rekeys the socket: it now presents the access token, not the identity.
            assertTrue("the socket did not reconnect with the session", account.stack.awaitConnected(LiveAccounts.ConnectTimeoutMillis));
            assertTrue(account.stack.credentials.signedIn());
            assertEquals(account.stack.tokens.bearer(), account.stack.credentials.current());
        }

        // 3. users.getUsers([inputUserSelf]); then B's user lane is subscribed.
        for (Account account : new Account[] {a, b}) {
            Map<String, Object> self = live.selfUser(account);
            assertEquals("user", self.get("_"));
            assertEquals(Boolean.TRUE, self.get("self"));
            account.userId = (Long) self.get("id");
            assertEquals(account.userId, account.stack.session().userId);
        }
        b.stack.subscribeUser(b.userId);
        assertTrue("B's user lane is not subscribed", b.stack.awaitSubscribed("user:" + b.userId, PublicationWaitMillis));

        // 4. updates.getState.
        Map<String, Object> state = live.call(a, "updates.getState", TlBuilder.method("updates.getState"));
        assertEquals("updates.state", state.get("_"));
        assertTrue((Integer) state.get("pts") >= 0);

        // 5. A resolves B (and B resolves A, to obtain the access hash for the history peer).
        Map<String, Object> bUser = live.importContact(a, b.phone, "B");
        assertEquals(b.userId, bUser.get("id"));
        b.accessHash = (Long) bUser.get("access_hash");
        Map<String, Object> aUser = live.importContact(b, a.phone, "A");
        assertEquals(a.userId, aUser.get("id"));
        a.accessHash = (Long) aUser.get("access_hash");

        // 6. A sends text to B; B's user lane delivers a publication carrying it.
        String text = "hello " + nonce;
        Map<String, Object> sent = live.call(
            a,
            "messages.sendMessage",
            TlBuilder.method("messages.sendMessage")
                .put("peer", peer(b))
                .put("message", text)
                .put("random_id", random.nextLong())
        );
        System.out.println("LIVE sendMessage -> " + sent.get("_"));
        assertSentUpdates(sent);
        Publication delivered = awaitText(b, "user:" + b.userId, text, PublicationWaitMillis);
        assertNotNull("B's user lane delivered no publication carrying the text", delivered);

        // 7. B reads the history over the socket.
        Map<String, Object> history = getHistory(b, a);
        assertTrue("history of B lacks the text: " + history, findMessage(history, text) != null);

        // 8. Photo upload and download over the media fleet.
        byte[] jpeg = deterministicJpeg();
        System.out.println("LIVE jpeg bytes = " + jpeg.length);
        long fileId = random.nextLong();
        Object saved = live.result(
            a,
            "upload.saveFilePart",
            TlBuilder.method("upload.saveFilePart")
                .put("file_id", fileId)
                .put("file_part", 0)
                .put("bytes", jpeg)
        );
        assertEquals(Boolean.TRUE, saved);
        Map<String, Object> mediaSent = live.call(
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
        assertEquals("upload.file", file.get("_"));
        byte[] downloaded = (byte[]) file.get("bytes");
        assertTrue("empty upload.file bytes", downloaded.length > 0);
        System.out.println("LIVE downloaded bytes = " + downloaded.length);

        // 8b. A 2-part document: uploaded in two saveFilePart parts, downloaded in full by B while A sends another text.
        byte[] blob = deterministicBlob(BlobBytes);
        long blobId = random.nextLong();
        int parts = (blob.length + PartBytes - 1) / PartBytes;
        assertEquals("the scenario expects a 2-part upload", 2, parts);
        for (int part = 0; part < parts; part++) {
            int from = part * PartBytes;
            int to = Math.min(blob.length, from + PartBytes);
            Object partSaved = live.result(
                a,
                "upload.saveFilePart",
                TlBuilder.method("upload.saveFilePart")
                    .put("file_id", blobId)
                    .put("file_part", part)
                    .put("bytes", Arrays.copyOfRange(blob, from, to))
            );
            assertEquals(Boolean.TRUE, partSaved);
        }
        Map<String, Object> blobSent = live.call(
            a,
            "messages.sendMedia",
            TlBuilder.method("messages.sendMedia")
                .put("peer", peer(b))
                .put(
                    "media",
                    TlBuilder.object("inputMediaUploadedDocument")
                        .put(
                            "file",
                            TlBuilder.object("inputFile")
                                .put("id", blobId)
                                .put("parts", parts)
                                .put("name", "blob.bin")
                                .put("md5_checksum", "")
                        )
                        .put("mime_type", "application/octet-stream")
                        .put(
                            "attributes",
                            TlBuilder.list(TlBuilder.object("documentAttributeFilename").put("file_name", "blob.bin"))
                        )
                )
                .put("message", "")
                .put("random_id", random.nextLong())
        );
        System.out.println("LIVE blob sendMedia -> " + blobSent.get("_"));
        assertSentUpdates(blobSent);
        final Map<String, Object> document = findDocument(getHistory(b, a), blob.length);
        assertNotNull("B's history holds no document of the uploaded size", document);
        FutureTask<byte[]> download = new FutureTask<byte[]>(new Callable<byte[]>() {
            @Override
            public byte[] call() throws Exception {
                return download(b, document);
            }
        });
        Thread downloadThread = new Thread(download, "blob-download");
        downloadThread.setDaemon(true);
        downloadThread.start();
        String during = "during download " + nonce;
        live.sendText(a, peer(b), during);
        Publication duringDelivery = awaitText(b, "user:" + b.userId, during, PublicationWaitMillis);
        assertNotNull("B's user lane did not deliver the text sent during the download", duringDelivery);
        byte[] blobDownloaded = awaitDownload(download);
        assertArrayEquals("the downloaded document differs from the uploaded bytes", blob, blobDownloaded);
        System.out.println("LIVE 2-part document downloaded in full: " + blobDownloaded.length + " bytes");

        // 9. Refresh the session of A, then the state call must still work.
        String before = a.stack.tokens.bearer();
        assertTrue(a.stack.tokens.refreshBlocking());
        assertNotNull(a.stack.tokens.bearer());
        System.out.println("LIVE refresh rotated access token = " + !before.equals(a.stack.tokens.bearer()));
        Map<String, Object> stateAfter = live.call(a, "updates.getState", TlBuilder.method("updates.getState"));
        assertEquals("updates.state", stateAfter.get("_"));

        // 10. B logs out; the session must die on B's stack.
        Map<String, Object> loggedOut = live.call(b, "auth.logOut", TlBuilder.method("auth.logOut"));
        assertEquals("auth.loggedOut", loggedOut.get("_"));
        b.stack.afterLogOut();
        RpcOutcome dead = b.stack.rpc.callBlocking(TlBuilder.method("updates.getState").toBytes());
        System.out.println("LIVE after logOut: error=" + dead.error + " forceLogout=" + dead.forceLogout);
        assertNull(dead.tlResult);
        assertNotNull("a call without a session succeeded", dead.error);
        // Signed out, updates.getState is refused on the identity socket before it is sent, so the server's own
        // session-loss answer (forceLogout, 401) is not reached. The refusal is METHOD_INVALID.
        assertTrue(
            "expected forceLogout, a 401, or the signed-out refusal, got " + dead.error,
            dead.forceLogout || dead.error.code == 401 || (dead.error.code == 400 && "METHOD_INVALID".equals(dead.error.text))
        );
        assertFalse("B's stack still holds a session", b.stack.tokens.hasSession());
        assertNull(b.stack.session());
    }


    private String randomPhone(int x) {
        return "99966" + x + String.format("%04d", random.nextInt(10000));
    }


    private static void pause() throws InterruptedException {
        Thread.sleep(PreAuthPauseMillis);
    }


    private static TlBuilder peer(Account target) {
        return TlBuilder.object("inputPeerUser")
            .put("user_id", target.userId)
            .put("access_hash", target.accessHash);
    }


    private Map<String, Object> getHistory(
        Account reader,
        Account other
    ) throws IOException {
        return live.call(
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


    private static void assertSentUpdates(Map<String, Object> sent) {
        String kind = (String) sent.get("_");
        if ("updateShortSentMessage".equals(kind)) {
            return;
        }
        assertTrue("unexpected result " + kind, "updates".equals(kind) || "updatesCombined".equals(kind));
        boolean hasMessageId = false;
        for (Object update : (List<?>) sent.get("updates")) {
            if ("updateMessageID".equals(LiveAccounts.asMap(update).get("_"))) {
                hasMessageId = true;
            }
        }
        assertTrue("no updateMessageID in " + sent, hasMessageId);
    }


    private static Map<String, Object> findMessage(
        Map<String, Object> history,
        String text
    ) {
        for (Object message : (List<?>) history.get("messages")) {
            Map<String, Object> map = LiveAccounts.asMap(message);
            if (text.equals(map.get("message"))) {
                return map;
            }
        }
        return null;
    }


    private static Map<String, Object> findMessageWithMedia(
        Map<String, Object> history,
        String mediaPredicate
    ) {
        for (Object message : (List<?>) history.get("messages")) {
            Map<String, Object> map = LiveAccounts.asMap(message);
            Object media = map.get("media");
            if (media != null && mediaPredicate.equals(LiveAccounts.asMap(media).get("_"))) {
                return map;
            }
        }
        return null;
    }


    /** The document of a messageMediaDocument whose size is the given one. */
    private static Map<String, Object> findDocument(
        Map<String, Object> history,
        long size
    ) {
        for (Object message : (List<?>) history.get("messages")) {
            Map<String, Object> media = LiveAccounts.asMap(LiveAccounts.asMap(message).get("media"));
            if (media == null || !"messageMediaDocument".equals(media.get("_"))) {
                continue;
            }
            Map<String, Object> document = LiveAccounts.asMap(media.get("document"));
            Object documentSize = document.get("size");
            if (documentSize instanceof Number && ((Number) documentSize).longValue() == size) {
                return document;
            }
        }
        return null;
    }


    /** Downloads the whole document in chunks until a short chunk, as a client does. */
    private byte[] download(
        Account reader,
        Map<String, Object> document
    ) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long offset = 0L;
        while (true) {
            Map<String, Object> chunkReply = live.call(
                reader,
                "upload.getFile",
                TlBuilder.method("upload.getFile")
                    .put(
                        "location",
                        TlBuilder.object("inputDocumentFileLocation")
                            .put("id", document.get("id"))
                            .put("access_hash", document.get("access_hash"))
                            .put("file_reference", document.get("file_reference"))
                            .put("thumb_size", "")
                    )
                    .put("offset", offset)
                    .put("limit", ChunkBytes)
            );
            assertEquals("upload.file", chunkReply.get("_"));
            byte[] chunk = (byte[]) chunkReply.get("bytes");
            out.write(chunk);
            offset += chunk.length;
            if (chunk.length < ChunkBytes) {
                return out.toByteArray();
            }
        }
    }


    private static byte[] awaitDownload(FutureTask<byte[]> download) throws Exception {
        try {
            return download.get(DownloadWaitMillis, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw (Error) cause;
        }
    }


    /** Waits for a publication on the channel whose decoded updates carry the text; other publications are skipped. */
    private Publication awaitText(
        Account target,
        String channel,
        String text,
        long timeoutMillis
    ) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Publication publication = target.stack.publications().poll(200L, TimeUnit.MILLISECONDS);
            if (publication != null && channel.equals(publication.channel) && carries(publication, text)) {
                return publication;
            }
        }
        return null;
    }


    /** True when the publication decodes to updates holding a message with the text. */
    private boolean carries(
        Publication publication,
        String text
    ) {
        if (publication.envelope.updatesProto == null) {
            return false;
        }
        byte[] tl = live.transcoder.decodeUpdates(publication.envelope.updatesProto, null);
        Map<String, Object> updates = live.view.decodeObject(tl);
        return containsMessage(updates, text);
    }


    private static boolean containsMessage(
        Object node,
        String text
    ) {
        if (node instanceof Map) {
            Map<String, Object> map = LiveAccounts.asMap(node);
            if (text.equals(map.get("message"))) {
                return true;
            }
            for (Object child : map.values()) {
                if (containsMessage(child, text)) {
                    return true;
                }
            }
        } else if (node instanceof List) {
            for (Object child : (List<?>) node) {
                if (containsMessage(child, text)) {
                    return true;
                }
            }
        }
        return false;
    }


    /** A repeated pattern of a prime period, so a misordered or dropped part cannot pass the byte comparison. */
    static byte[] deterministicBlob(int length) {
        byte[] blob = new byte[length];
        for (int i = 0; i < length; i++) {
            blob[i] = (byte) (i % 251);
        }
        return blob;
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
