package net.impulsem.transport.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import net.impulsem.transport.auth.SessionTokens;
import net.impulsem.transport.codec.Transcoder;
import net.impulsem.transport.rpc.RpcOutcome;
import net.impulsem.transport.schema.TlProtoSchema;
import okhttp3.OkHttpClient;


/** Login and call helpers for live tests. Every account runs on its own {@link LiveStack}, wired as the app wires it. */
final class LiveAccounts {

    static final long ConnectTimeoutMillis = 20000L;

    private static final String ApiHash = "85e6b3be52309e397e6b4b2d688c7ca4";
    private static final long PauseMillis = 300L;


    static final class Account {

        final String phone;
        final String code;
        final LiveStack stack;
        long userId;
        long accessHash;


        Account(
            String phone,
            String code,
            LiveStack stack
        ) {
            this.phone = phone;
            this.code = code;
            this.stack = stack;
        }
    }


    final OkHttpClient http;
    final TlView view;
    final Transcoder transcoder;
    private final Random random = new Random();
    private final List<Account> accounts = new ArrayList<Account>();


    LiveAccounts() {
        TlProtoSchema schema = TlProtoSchema.load();
        view = new TlView(schema);
        transcoder = new Transcoder(schema);
        http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
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
        Account account = newAccount("99966" + x + String.format("%04d", random.nextInt(10000)), code.toString());
        fetchConfig(account);
        login(account, lastName);
        account.userId = (Long) selfUser(account).get("id");
        return account;
    }


    /** A signed-out account whose socket is starting. Calls wait for the socket to connect. */
    Account newAccount(
        String phone,
        String code
    ) {
        Account account = build(phone, code, null);
        account.stack.connect();
        return account;
    }


    /** Rebuilds an account from a session saved by an earlier run; the user id comes with the tokens. */
    Account restoreAccount(
        String phone,
        String code,
        SessionTokens saved
    ) throws IOException {
        Account account = build(phone, code, saved);
        account.stack.connect();
        account.userId = saved.userId;
        fetchConfig(account);
        return account;
    }


    /** An account with a session and no socket started: for checks that go straight to the token pool. */
    Account detachedAccount(SessionTokens saved) {
        return build("0", "0", saved);
    }


    /** help.getConfig over the account's socket; its media option becomes the account's media endpoint. */
    Map<String, Object> fetchConfig(Account account) throws IOException {
        Map<String, Object> config = call(account, "help.getConfig", TlBuilder.method("help.getConfig"));
        account.stack.applyConfig(config);
        return config;
    }


    /** The signed-in user itself, as users.getUsers reports it. */
    Map<String, Object> selfUser(Account account) throws IOException {
        List<?> users = (List<?>) result(
            account,
            "users.getUsers",
            TlBuilder.method("users.getUsers").put("id", TlBuilder.list(TlBuilder.object("inputUserSelf")))
        );
        assertEquals(1, users.size());
        return asMap(users.get(0));
    }


    /** The current session of the account, for saving between runs. */
    SessionTokens currentSession(Account account) {
        return account.stack.session();
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
        awaitSocket(account, method);
        RpcOutcome outcome = account.stack.rpc.callBlocking(request.toBytes());
        if (outcome.error != null) {
            throw new AssertionError(method + " failed: " + outcome.error + " forceLogout=" + outcome.forceLogout);
        }
        assertNotNull(method, outcome.tlResult);
        return view.result(method, outcome.tlResult);
    }


    /** Stops every socket and pool the accounts opened. */
    void close() {
        for (Account account : accounts) {
            account.stack.close();
        }
        accounts.clear();
    }


    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }


    private Account build(
        String phone,
        String code,
        SessionTokens saved
    ) {
        Account account = new Account(phone, code, new LiveStack(http, transcoder, phone, saved));
        accounts.add(account);
        return account;
    }


    private static void awaitSocket(
        Account account,
        String method
    ) throws IOException {
        try {
            if (!account.stack.awaitConnected(ConnectTimeoutMillis)) {
                throw new AssertionError(method + " not sent: the socket never connected");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(method + " interrupted while waiting for the socket", e);
        }
    }


    void login(
        Account account,
        String lastName
    ) throws Exception {
        Map<String, Object> sentCode = call(
            account,
            "auth.sendCode",
            TlBuilder.method("auth.sendCode")
                .put("phone_number", account.phone)
                .put("api_id", LiveStack.ApiId)
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
        assertTrue("no token pair captured", account.stack.tokens.hasSession());
    }
}
