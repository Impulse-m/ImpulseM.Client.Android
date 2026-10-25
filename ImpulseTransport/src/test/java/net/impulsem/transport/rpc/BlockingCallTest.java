package net.impulsem.transport.rpc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.centrifugal.centrifuge.ReplyError;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;


public class BlockingCallTest {

    /** A call that never settles on its own and records whether it was cancelled. */
    private static final class SilentCarrier implements RpcCarrier {

        final AtomicBoolean cancelled = new AtomicBoolean();


        @Override
        public CarrierCall call(
            String method,
            byte[] data,
            long timeoutMillis,
            CarrierCallback callback
        ) {
            return new CarrierCall() {
                @Override
                public void cancel() {
                    cancelled.set(true);
                }
            };
        }
    }


    @Test
    public void replyIsReturnedAsIs() throws IOException {
        FakeCarrier carrier = new FakeCarrier();
        byte[] reply = {1, 2, 3};
        carrier.reply(reply);
        BlockingCall.Outcome outcome = BlockingCall.send(carrier, "ping", new byte[0], 1_000L, null);
        assertArrayEquals(reply, outcome.reply);
        assertNull(outcome.failure);
        carrier.shutdown();
    }


    @Test
    public void failureIsReturnedWithItsCause() throws IOException {
        FakeCarrier carrier = new FakeCarrier();
        IOException boom = new IOException("boom");
        carrier.fail(boom);
        BlockingCall.Outcome outcome = BlockingCall.send(carrier, "ping", new byte[0], 1_000L, null);
        assertNull(outcome.reply);
        assertNotNull(outcome.failure);
        assertSame(boom, outcome.failure.cause);
        carrier.shutdown();
    }


    @Test
    public void sinkReceivesTheCarrierCall() throws IOException {
        FakeCarrier carrier = new FakeCarrier();
        carrier.reply(new byte[0]);
        final AtomicReference<CarrierCall> received = new AtomicReference<CarrierCall>();
        BlockingCall.send(carrier, "ping", new byte[0], 1_000L, new BlockingCall.SentSink() {
            @Override
            public void onSent(CarrierCall call) {
                received.set(call);
            }
        });
        assertNotNull(received.get());
        carrier.shutdown();
    }


    @Test
    public void unsettledCallIsCancelledAndReportedAsIoException() throws InterruptedException {
        SilentCarrier carrier = new SilentCarrier();
        try {
            BlockingCall.send(carrier, "ping", new byte[0], 50L, null);
            fail("an unsettled call must throw");
        } catch (IOException e) {
            assertEquals("no answer to ping", e.getMessage());
        }
        assertTrue("the carrier call was not cancelled", carrier.cancelled.get());
    }


    @Test
    public void replyErrorFailureKeepsItsCode() throws IOException {
        FakeCarrier carrier = new FakeCarrier();
        carrier.fail(new ReplyError(CarrierErrors.MethodInvalidCode, "method", false));
        BlockingCall.Outcome outcome = BlockingCall.send(carrier, "ping", new byte[0], 1_000L, null);
        assertNotNull(outcome.failure);
        assertTrue(outcome.failure.sdkCode == CarrierErrors.MethodInvalidCode);
        carrier.shutdown();
    }
}
