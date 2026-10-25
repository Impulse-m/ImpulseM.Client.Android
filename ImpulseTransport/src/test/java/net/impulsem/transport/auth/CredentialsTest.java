package net.impulsem.transport.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.impulsem.transport.rpc.FakeCarrier;
import org.junit.Before;
import org.junit.Test;


public class CredentialsTest {

    private static final long NowMillis = 1_700_000_000_000L;
    private static final long TokenExpSeconds = 1_700_003_600L;

    private static final class MemoryStore implements SessionStore {

        volatile SessionTokens tokens;


        @Override
        public SessionTokens load() {
            return tokens;
        }


        @Override
        public synchronized void save(SessionTokens value) {
            tokens = value;
        }


        @Override
        public synchronized void clear() {
            tokens = null;
        }
    }


    private final FakeCarrier pool = new FakeCarrier();
    private final List<String> identityEvents = new ArrayList<String>();
    private MemoryStore store;
    private TokenManager tokens;
    private IdentityManager identity;
    private Credentials credentials;


    @Before
    public void setUp() {
        store = new MemoryStore();
        store.tokens = new SessionTokens("old", "r1", 5L);
        build();
    }


    private void build() {
        Clock clock = new Clock() {
            @Override
            public long nowMillis() {
                return NowMillis;
            }
        };
        tokens = new TokenManager(store, clock);
        identity = TestIdentity.manager(pool, clock);
        identity.setListener(new IdentityManager.Listener() {
            @Override
            public void onIdentityChanged() {
                identityEvents.add("changed");
            }


            @Override
            public void onIdentityRenewed(String identityToken) {
                identityEvents.add("renewed");
            }
        });
        credentials = new Credentials(tokens, identity);
    }


    @Test
    public void currentIsTheAccessTokenWithASession() {
        assertTrue(credentials.signedIn());
        assertEquals("old", credentials.current());
    }


    @Test
    public void currentIsTheIdentityWithoutASession() throws Exception {
        store.tokens = null;
        build();
        String minted = TestIdentity.jwt("7", TokenExpSeconds);
        pool.reply(TestIdentity.identityReply(minted));
        assertFalse(credentials.signedIn());
        assertNull(credentials.current());

        identity.obtainBlocking();

        assertEquals(minted, credentials.current());
    }


    @Test
    public void connectTokenWithASessionIsTheAccessToken() throws Exception {
        assertEquals("old", credentials.connectTokenBlocking());
        assertEquals(0, pool.callCount());
    }


    @Test
    public void connectTokenWithoutASessionMintsAnIdentity() throws Exception {
        store.tokens = null;
        build();
        String minted = TestIdentity.jwt("7", TokenExpSeconds);
        pool.reply(TestIdentity.identityReply(minted));

        assertEquals(minted, credentials.connectTokenBlocking());
        assertEquals(ImpulseRpcPaths.ConnectionIdentityMethod, pool.takeSent().method);
    }


    @Test
    public void refusedAccessTokenIsMarkedUnusable() {
        assertFalse(tokens.accessUnusable());

        credentials.refused("old");

        assertTrue(tokens.accessUnusable());
    }


    @Test
    public void refusedIdentityIsDiscardedAndReported() throws Exception {
        store.tokens = null;
        build();
        String minted = TestIdentity.jwt("7", TokenExpSeconds);
        pool.reply(TestIdentity.identityReply(minted));
        identity.obtainBlocking();

        credentials.refused(minted);

        assertEquals(1, identityEvents.size());
        assertEquals("changed", identityEvents.get(0));
        assertNull(credentials.current());
    }


    @Test
    public void refusedNullDoesNothing() {
        credentials.refused(null);

        assertFalse(tokens.accessUnusable());
        assertTrue(identityEvents.isEmpty());
    }
}
