package net.impulsem.transport.fleet;

import static org.junit.Assert.assertNotNull;

import java.util.concurrent.atomic.AtomicReference;
import net.impulsem.transport.rpc.CarrierCall;
import net.impulsem.transport.rpc.CarrierCallback;
import net.impulsem.transport.rpc.CarrierFailure;
import okhttp3.OkHttpClient;
import org.junit.Test;


/** A pool fleet built without an endpoint (a build with no IMPULSEM_RPC_URL) fails every call at once and dials nothing. */
public class UnconfiguredPoolCarrierTest {

    @Test
    public void callFailsAtOnceWithoutAnEndpoint() {
        RpcPoolCarrier carrier = RpcPoolCarrier.poolFleet(new OkHttpClient(), null);
        final AtomicReference<CarrierFailure> failure = new AtomicReference<CarrierFailure>();
        CarrierCall call = carrier.call("connectionIdentity", new byte[0], 1000L, new CarrierCallback() {
            @Override
            public void onReply(byte[] data) {
                throw new AssertionError("an unconfigured fleet cannot reply");
            }


            @Override
            public void onFailure(CarrierFailure settled) {
                failure.set(settled);
            }
        });
        assertNotNull(call);
        assertNotNull(failure.get());
        call.cancel();
    }
}
