package net.impulsem.transport.rpc;

import io.github.centrifugal.centrifuge.ReplyError;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Records the rpcs it is asked to send and answers them from a script, on its own thread as the SDK does. A call the
 * script does not answer is held until it is cancelled.
 */
public final class FakeCarrier implements RpcCarrier {

    private static final long WaitSeconds = 5L;

    /** One rpc the carrier was asked to send. */
    public static final class Sent {

        public final String method;
        public final byte[] data;
        public final long timeoutMillis;


        Sent(
            String method,
            byte[] data,
            long timeoutMillis
        ) {
            this.method = method;
            this.data = data;
            this.timeoutMillis = timeoutMillis;
        }
    }


    /** What the next call does: a reply, a failure, or nothing at all when both are null. */
    private static final class Script {

        final byte[] reply;
        final Throwable failure;


        Script(
            byte[] reply,
            Throwable failure
        ) {
            this.reply = reply;
            this.failure = failure;
        }
    }


    private final BlockingQueue<Sent> sent = new LinkedBlockingQueue<Sent>();
    private final BlockingQueue<Script> scripts = new LinkedBlockingQueue<Script>();
    private final AtomicInteger callCount = new AtomicInteger();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();


    public void reply(byte[] data) {
        scripts.add(new Script(data, null));
    }


    public void fail(Throwable failure) {
        scripts.add(new Script(null, failure));
    }


    public void hold() {
        scripts.add(new Script(null, null));
    }


    @Override
    public CarrierCall call(
        String method,
        byte[] data,
        long timeoutMillis,
        CarrierCallback callback
    ) {
        sent.add(new Sent(method, data, timeoutMillis));
        callCount.incrementAndGet();
        FakeCall call = new FakeCall(callback);
        Script script = scripts.poll();
        if (script != null && (script.reply != null || script.failure != null)) {
            call.answer(script);
        }
        return call;
    }


    public int callCount() {
        return callCount.get();
    }


    public Sent takeSent() throws InterruptedException {
        Sent next = sent.poll(WaitSeconds, TimeUnit.SECONDS);
        if (next == null) {
            throw new AssertionError("no rpc was sent");
        }
        return next;
    }


    public void shutdown() {
        executor.shutdownNow();
    }


    private final class FakeCall implements CarrierCall {

        private final CarrierCallback callback;
        private final AtomicBoolean settled = new AtomicBoolean();


        FakeCall(CarrierCallback callback) {
            this.callback = callback;
        }


        void answer(final Script script) {
            if (!settled.compareAndSet(false, true)) {
                return;
            }
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    if (script.failure != null) {
                        callback.onFailure(CarrierFailure.of(script.failure));
                    } else {
                        callback.onReply(script.reply);
                    }
                }
            });
        }


        @Override
        public void cancel() {
            answer(new Script(null, new ReplyError(CarrierErrors.CancelledCode, "cancelled", false)));
        }
    }
}
