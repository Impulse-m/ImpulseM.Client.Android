package net.impulsem.transport.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
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


/** Login and call helpers for live tests; the same flow as {@link LiveRpcTest}. */
final class LiveAccounts {

    static final String Endpoint = "https://rpc.impulsem.net";
    static final String WsUrl = "wss://rpc.impulsem.net/connection/websocket?cf_ws_frame_ping_pong=true";
    private static final int ApiId = 3;
    private static final String ApiHash = "85e6b3be52309e397e6b4b2d688c7ca4";
    private static final long PauseMillis = 300L;


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


    static final class Account {

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


    final OkHttpClient http;
    final TlView view;
    final Transcoder transcoder;
    private final GrpcWebClient grpc;
    private final Random random = new Random();


    LiveAccounts() {
        TlProtoSchema schema = TlProtoSchema.load();
        view = new TlView(schema);
        transcoder = new Transcoder(schema);
        http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
        grpc = new GrpcWebClient(http, HttpUrl.get(Endpoint));
    }


    /** Creates, signs in and identifies a fresh account; x is the reserved-number digit 1, 2 or 3. */
    Account freshAccount(
        int x,
        String lastName
    ) throws Exception {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            code.append(x);
        }
        Account account = new Account("99966" + x + String.format("%04d", random.nextInt(10000)), code.toString());
        account.tokens = new TokenManager(account.store, grpc, Clock.SYSTEM);
        account.rpc = new RpcClient(transcoder, grpc, account.tokens);
        login(account, lastName);
        List<?> users = (List<?>) result(
            account,
            "users.getUsers",
            TlBuilder.method("users.getUsers").put("id", TlBuilder.list(TlBuilder.object("inputUserSelf")))
        );
        Map<String, Object> self = asMap(users.get(0));
        account.userId = (Long) self.get("id");
        return account;
    }


    /** Rebuilds an account from a session saved by an earlier run; the user id comes with the tokens. */
    Account restoreAccount(
        String phone,
        String code,
        SessionTokens saved
    ) {
        Account account = new Account(phone, code);
        account.store.save(saved);
        account.tokens = new TokenManager(account.store, grpc, Clock.SYSTEM);
        account.rpc = new RpcClient(transcoder, grpc, account.tokens);
        account.userId = saved.userId;
        return account;
    }


    /** The current session of the account, for saving between runs. */
    SessionTokens currentSession(Account account) {
        SessionStore store = account.store;
        return store.load();
    }


    /** Resolves the other account by phone, to learn its access hash. */
    Map<String, Object> importContact(
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
        List<?> users = (List<?>) imported.get("users");
        assertEquals(1, users.size());
        return asMap(users.get(0));
    }


    TlBuilder userPeer(
        long userId,
        long accessHash
    ) {
        return TlBuilder.object("inputPeerUser").put("user_id", userId).put("access_hash", accessHash);
    }


    void sendText(
        Account from,
        TlBuilder peer,
        String text
    ) throws IOException {
        call(
            from,
            "messages.sendMessage",
            TlBuilder.method("messages.sendMessage")
                .put("peer", peer)
                .put("message", text)
                .put("random_id", random.nextLong())
        );
    }


    Map<String, Object> call(
        Account account,
        String method,
        TlBuilder request
    ) throws IOException {
        return asMap(result(account, method, request));
    }


    Object result(
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


    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
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
        Thread.sleep(PauseMillis);
        Map<String, Object> signIn = call(
            account,
            "auth.signIn",
            TlBuilder.method("auth.signIn")
                .put("phone_number", account.phone)
                .put("phone_code_hash", hash)
                .put("phone_code", account.code)
        );
        Thread.sleep(PauseMillis);
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
            Thread.sleep(PauseMillis);
        }
        assertEquals("auth.authorization", authorization.get("_"));
        assertTrue("no token pair captured", account.tokens.hasSession());
    }
}
