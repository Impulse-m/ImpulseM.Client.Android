package net.impulsem.transport.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.Executor;
import net.impulsem.transport.rpc.ConnectionDeclarer;
import net.impulsem.transport.rpc.RpcCarrier;
import net.impulsem.transport.wire.ProtoWriter;


/** Builds identity managers and identity tokens for tests outside the auth package. */
public final class TestIdentity {

    private static final int IdentityLifetimeSeconds = 3600;


    private TestIdentity() {
    }


    /** An identity manager with nothing stored. Mints go through the pool carrier; renewals never fire. */
    public static IdentityManager manager(
        RpcCarrier pool,
        Clock clock
    ) {
        return new IdentityManager(
            new MemoryStore(),
            pool,
            clock,
            new Random(1L),
            new ConnectionDeclarer.Delayer() {
                @Override
                public void schedule(
                    Runnable task,
                    long delayMillis
                ) {
                }
            },
            new Executor() {
                @Override
                public void execute(Runnable command) {
                    command.run();
                }
            }
        );
    }


    /** A JWT with the claims the identity rules read, valid until expSeconds; the signature is not checked. */
    public static String jwt(
        String sub,
        long expSeconds
    ) {
        long iat = expSeconds - IdentityLifetimeSeconds;
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString((
            "{\"sub\":\"" + sub + "\",\"exp\":" + expSeconds + ",\"iat\":" + iat + ",\"orig_iat\":" + iat + "}"
        ).getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }


    /** ConnectionIdentityResponse { string identity_token = 1; } */
    public static byte[] identityReply(String identityToken) {
        ProtoWriter writer = new ProtoWriter();
        writer.writeBytesField(1, identityToken.getBytes(StandardCharsets.UTF_8));
        return writer.toByteArray();
    }


    /** Holds the identity token in memory. */
    private static final class MemoryStore implements IdentityManager.Store {

        private volatile String token;


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
        }
    }
}
