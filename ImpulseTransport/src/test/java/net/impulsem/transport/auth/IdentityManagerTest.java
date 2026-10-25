package net.impulsem.transport.auth;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.centrifugal.centrifuge.ReplyError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.impulsem.transport.auth.TokenManagerTest.FakeClock;
import net.impulsem.transport.rpc.CarrierCall;
import net.impulsem.transport.rpc.CarrierCallback;
import net.impulsem.transport.rpc.ConnectionDeclarer;
import net.impulsem.transport.rpc.FakeCarrier;
import net.impulsem.transport.rpc.RpcCarrier;
import net.impulsem.transport.rpc.RpcStatusException;
import net.impulsem.transport.wire.ProtoWriter;
import org.junit.Test;


public class IdentityManagerTest {

    private static final int ApiId = 6;

    /** In-memory identity store that counts its writes. */
    static final class MemoryStore implements IdentityManager.Store {

        volatile String token;
        volatile int clears;


        @Override
        public String load() {
            return token;
        }


        @Override
        public synchronized void save(String identityToken) {
            token = identityToken;
        }


        @Override
        public synchronized void clear() {
            token = null;
            clears++;
        }
    }


    /** Records each scheduled task with its delay and never runs one by itself. */
    static final class RecordingDelayer implements ConnectionDeclarer.Delayer {

        final List<Scheduled> scheduled = Collections.synchronizedList(new ArrayList<Scheduled>());


        @Override
        public void schedule(
            Runnable task,
            long delayMillis
        ) {
            scheduled.add(new Scheduled(task, delayMillis));
        }
    }


    static final class Scheduled {

        final Runnable task;
        final long delayMillis;


        Scheduled(
            Runnable task,
            long delayMillis
        ) {
            this.task = task;
            this.delayMillis = delayMillis;
        }
    }


    /** Records what the listener was told, in order. */
    static final class RecordingListener implements IdentityManager.Listener {

        final List<String> events = Collections.synchronizedList(new ArrayList<String>());


        @Override
        public void onIdentityChanged() {
            events.add("changed");
        }


        @Override
        public void onIdentityRenewed(String identityToken) {
            events.add("renewed:" + identityToken);
        }
    }


    /** Delays each call before handing it to the wrapped carrier, so concurrent callers overlap the mint. */
    static final class DelayedCarrier implements RpcCarrier {

        private final RpcCarrier inner;
        private final long delayMillis;


        DelayedCarrier(
            RpcCarrier inner,
            long delayMillis
        ) {
            this.inner = inner;
            this.delayMillis = delayMillis;
        }


        @Override
        public CarrierCall call(
            String method,
            byte[] data,
            long timeoutMillis,
            CarrierCallback callback
        ) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return inner.call(method, data, timeoutMillis, callback);
        }
    }


    /** Announces the call, then holds it until released. */
    static final class GatedCarrier implements RpcCarrier {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final RpcCarrier inner;


        GatedCarrier(RpcCarrier inner) {
            this.inner = inner;
        }


        @Override
        public CarrierCall call(
            String method,
            byte[] data,
            long timeoutMillis,
            CarrierCallback callback
        ) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return inner.call(method, data, timeoutMillis, callback);
        }
    }


    private final MemoryStore store = new MemoryStore();
    private final FakeClock clock = new FakeClock();
    private final RecordingDelayer delayer = new RecordingDelayer();
    private final FakeCarrier pool = new FakeCarrier();
    private final FakeCarrier socket = new FakeCarrier();
    private final Executor inline = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };


    private long nowSeconds() {
        return clock.nowMillis() / 1000L;
    }


    /** A JWT with the claims the identity rules read; the signature is not checked. */
    static String jwt(
        String sub,
        long exp,
        long iat,
        long origIat
    ) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString((
            "{\"sub\":\"" + sub + "\",\"exp\":" + exp + ",\"iat\":" + iat + ",\"orig_iat\":" + origIat + "}"
        ).getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }


    /** ConnectionIdentityResponse { string identity_token = 1; } */
    static byte[] identityReply(String identityToken) {
        ProtoWriter writer = new ProtoWriter();
        writer.writeBytesField(1, identityToken.getBytes(StandardCharsets.UTF_8));
        return writer.toByteArray();
    }


    private IdentityManager newManager(RpcCarrier poolCarrier) {
        IdentityManager manager = new IdentityManager(store, poolCarrier, clock, new Random(1), delayer, inline);
        manager.setApiId(ApiId);
        manager.setRenewalCarrier(socket);
        return manager;
    }


    @Test
    public void obtainMintsThroughThePoolOnceAndStores() throws Exception {
        String minted = jwt("7", nowSeconds() + 3600, nowSeconds(), nowSeconds());
        pool.reply(identityReply(minted));
        IdentityManager manager = newManager(pool);

        assertEquals(minted, manager.obtainBlocking());
        FakeCarrier.Sent sent = pool.takeSent();
        assertEquals(ImpulseRpcPaths.ConnectionIdentityMethod, sent.method);
        assertArrayEquals(ImpulseRpcPaths.encodeConnectionIdentity("", ApiId), sent.data);
        assertEquals(minted, store.token);

        assertEquals(minted, manager.obtainBlocking());
        assertEquals(1, pool.callCount());
        pool.shutdown();
        socket.shutdown();
    }


    @Test
    public void storedValidTokenIsUsedWithoutMint() throws Exception {
        String stored = jwt("7", nowSeconds() + 600, nowSeconds(), nowSeconds());
        store.token = stored;
        IdentityManager manager = newManager(pool);

        assertEquals(stored, manager.obtainBlocking());
        assertEquals(stored, manager.current());
        assertEquals(0, pool.callCount());
        pool.shutdown();
        socket.shutdown();
    }


    @Test
    public void storedTokenNearExpiryIsReplaced() throws Exception {
        store.token = jwt("7", nowSeconds() + 30, nowSeconds(), nowSeconds());
        String fresh = jwt("7", nowSeconds() + 3600, nowSeconds(), nowSeconds());
        pool.reply(identityReply(fresh));
        IdentityManager manager = newManager(pool);

        assertNull("a token inside the 60 s margin is cleared at load", store.token);
        assertNull(manager.current());
        assertEquals(fresh, manager.obtainBlocking());
        assertEquals(1, pool.callCount());
        pool.shutdown();
        socket.shutdown();
    }


    @Test
    public void concurrentObtainSharesOneMint() throws Exception {
        String minted = jwt("7", nowSeconds() + 3600, nowSeconds(), nowSeconds());
        pool.reply(identityReply(minted));
        IdentityManager manager = newManager(new DelayedCarrier(pool, 200L));
        int threads = 8;
        ExecutorService workers = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<Future<String>>();
        for (int i = 0; i < threads; i++) {
            results.add(workers.submit(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    start.await();
                    return manager.obtainBlocking();
                }
            }));
        }
        start.countDown();
        for (Future<String> result : results) {
            assertEquals(minted, result.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, pool.callCount());
        workers.shutdownNow();
        pool.shutdown();
        socket.shutdown();
    }


    @Test
    public void poolErrorIsRpcStatusException() throws Exception {
        pool.fail(new ReplyError(100, "", false, 400, "API_ID_INVALID", Collections.<String, String>emptyMap()));
        IdentityManager manager = newManager(pool);
        try {
            manager.obtainBlocking();
            throw new AssertionError("a pool error must throw");
        } catch (RpcStatusException e) {
            assertEquals("API_ID_INVALID", e.error.text);
        }
        pool.shutdown();
        socket.shutdown();
    }


    @Test
    public void poolFaultIsIOException() throws Exception {
        pool.fail(new IOException("socket down"));
        IdentityManager manager = newManager(pool);
        try {
            manager.obtainBlocking();
            throw new AssertionError("a pool fault must throw");
        } catch (RpcStatusException e) {
            throw new AssertionError("a carrier fault is not an answer from the server", e);
        } catch (IOException e) {
            assertNotNull(e.getMessage());
        }
        pool.shutdown();
        socket.shutdown();
    }


    @Test
    public void renewalIsScheduledInsideTheWindow() throws Exception {
        long exp = nowSeconds() + 600;
        store.token = jwt("7", exp, nowSeconds(), nowSeconds());
        newManager(pool);

        assertEquals(1, delayer.scheduled.size());
        long delay = delayer.scheduled.get(0).delayMillis;
        long now = clock.nowMillis();
        assertTrue("delay " + delay, delay >= (exp - 300) * 1000L - now);
        assertTrue("delay " + delay, delay <= (exp - 120) * 1000L - now);
        pool.shutdown();
        socket.shutdown();
    }


    @Test
    public void renewalOfTheSameIdentityIsHandedToTheSocket() throws Exception {
        long exp = nowSeconds() + 600;
        String first = jwt("7", exp, nowSeconds() - 100, nowSeconds() - 100);
        String renewed = jwt("7", exp + 300, nowSeconds(), nowSeconds() - 100);
        store.token = first;
        RecordingListener listener = new RecordingListener();
        IdentityManager manager = newManager(pool);
        manager.setListener(listener);
        socket.reply(identityReply(renewed));

        delayer.scheduled.get(0).task.run();

        FakeCarrier.Sent sent = socket.takeSent();
        assertEquals(ImpulseRpcPaths.ConnectionIdentityMethod, sent.method);
        assertArrayEquals(ImpulseRpcPaths.encodeConnectionIdentity(first, ApiId), sent.data);
        assertEquals(Collections.singletonList("renewed:" + renewed), listener.events);
        assertEquals(renewed, store.token);
        assertEquals(renewed, manager.current());
        pool.shutdown();
        socket.shutdown();
    }


    @Test
    public void renewalToAnotherIdentityChangesIt() throws Exception {
        long exp = nowSeconds() + 600;
        store.token = jwt("7", exp, nowSeconds() - 100, nowSeconds() - 100);
        String other = jwt("8", exp + 300, nowSeconds(), nowSeconds());
        RecordingListener listener = new RecordingListener();
        IdentityManager manager = newManager(pool);
        manager.setListener(listener);
        socket.reply(identityReply(other));

        delayer.scheduled.get(0).task.run();

        assertEquals(Collections.singletonList("changed"), listener.events);
        assertEquals(other, store.token);
        assertEquals(other, manager.obtainBlocking());
        assertEquals(0, pool.callCount());
        pool.shutdown();
        socket.shutdown();
    }


    @Test
    public void failedRenewalRetriesUntilExpiryMinus120() throws Exception {
        long exp = nowSeconds() + 600;
        store.token = jwt("7", exp, nowSeconds() - 100, nowSeconds() - 100);
        newManager(pool);
        for (int i = 0; i < 20; i++) {
            socket.fail(new IOException("socket down"));
        }

        int runs = 0;
        while (!delayer.scheduled.isEmpty() && runs < 50) {
            Scheduled next = delayer.scheduled.remove(0);
            clock.now += next.delayMillis;
            assertTrue("a retry ran at or after exp-120 s", clock.now < (exp - 120) * 1000L);
            next.task.run();
            runs++;
        }

        assertTrue("retries happened", socket.callCount() > 1);
        assertTrue("retries stopped before the window closed", socket.callCount() < 20);
        assertEquals(0, delayer.scheduled.size());
        assertNotNull("a failed renewal keeps the stored token", store.token);
        pool.shutdown();
        socket.shutdown();
    }


    @Test
    public void discardDuringMintFailsTheMint() throws Exception {
        String minted = jwt("7", nowSeconds() + 3600, nowSeconds(), nowSeconds());
        pool.reply(identityReply(minted));
        GatedCarrier gated = new GatedCarrier(pool);
        final IdentityManager manager = newManager(gated);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<String> mint = worker.submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                return manager.obtainBlocking();
            }
        });
        assertTrue(gated.entered.await(5, TimeUnit.SECONDS));

        manager.discard();
        gated.release.countDown();

        try {
            mint.get(10, TimeUnit.SECONDS);
            throw new AssertionError("a mint discarded in flight must fail");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof IOException);
        }
        assertNull("the discarded mint must not be stored", store.token);
        assertNull(manager.current());
        worker.shutdownNow();
        pool.shutdown();
        socket.shutdown();
    }


    @Test
    public void discardClearsTheStore() throws Exception {
        store.token = jwt("7", nowSeconds() + 600, nowSeconds(), nowSeconds());
        IdentityManager manager = newManager(pool);

        manager.discard();

        assertNull(store.token);
        assertNull(manager.current());
        delayer.scheduled.get(0).task.run();
        assertEquals("a renewal scheduled before discard must do nothing", 0, socket.callCount());
        pool.shutdown();
        socket.shutdown();
    }


    @Test
    public void refuseOfTheCurrentTokenDiscardsAndNotifies() throws Exception {
        String stored = jwt("7", nowSeconds() + 600, nowSeconds(), nowSeconds());
        store.token = stored;
        RecordingListener listener = new RecordingListener();
        IdentityManager manager = newManager(pool);
        manager.setListener(listener);

        manager.refuse(stored);

        assertEquals(Collections.singletonList("changed"), listener.events);
        assertNull(store.token);
        assertNull(manager.current());
        pool.shutdown();
        socket.shutdown();
    }


    @Test
    public void refuseOfAnotherTokenIsIgnored() throws Exception {
        String stored = jwt("7", nowSeconds() + 600, nowSeconds(), nowSeconds());
        store.token = stored;
        RecordingListener listener = new RecordingListener();
        IdentityManager manager = newManager(pool);
        manager.setListener(listener);

        manager.refuse("some-other-token");

        assertFalse(listener.events.size() > 0);
        assertEquals(stored, store.token);
        assertEquals(stored, manager.current());
        pool.shutdown();
        socket.shutdown();
    }
}
