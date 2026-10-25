package net.impulsem.transport.rpc;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


/** Sends one rpc on a carrier and waits for it; for callers that run on a worker thread of their own. */
public final class BlockingCall {

    /** Extra wait past the call's own timeout, so the carrier's timeout error normally arrives first. */
    public static final long SettleGraceMillis = 5_000L;


    /** Receives the carrier call as soon as it exists, e.g. to make it the target of a cancel. */
    public interface SentSink {

        void onSent(CarrierCall call);
    }


    /** What the carrier settled with: exactly one of reply and failure is non-null. */
    public static final class Outcome {

        public final byte[] reply;
        public final CarrierFailure failure;


        private Outcome(
            byte[] reply,
            CarrierFailure failure
        ) {
            this.reply = reply;
            this.failure = failure;
        }
    }


    /** Settles one carrier call: exactly one of a reply or a failure is recorded before the latch opens. */
    private static final class Settlement implements CarrierCallback {

        private final CountDownLatch latch = new CountDownLatch(1);
        private byte[] reply;
        private CarrierFailure failure;


        @Override
        public void onReply(byte[] data) {
            reply = data;
            latch.countDown();
        }


        @Override
        public void onFailure(CarrierFailure failure) {
            this.failure = failure;
            latch.countDown();
        }


        boolean await(long millis) throws IOException {
            try {
                return latch.await(millis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while waiting for the carrier");
            }
        }
    }


    private BlockingCall() {
    }


    /**
     * Sends and waits up to timeoutMillis + SettleGraceMillis. A call that does not settle by then is cancelled and
     * reported as IOException("no answer to " + method); an interrupt is InterruptedIOException.
     *
     * @throws IOException when the call did not settle in time, or the wait was interrupted.
     */
    public static Outcome send(
        RpcCarrier carrier,
        String method,
        byte[] data,
        long timeoutMillis,
        SentSink sink
    ) throws IOException {
        Settlement settlement = new Settlement();
        CarrierCall call = carrier.call(method, data, timeoutMillis, settlement);
        if (sink != null) {
            sink.onSent(call);
        }
        if (!settlement.await(timeoutMillis + SettleGraceMillis)) {
            call.cancel();
            throw new IOException("no answer to " + method);
        }
        return new Outcome(settlement.reply, settlement.failure);
    }
}
