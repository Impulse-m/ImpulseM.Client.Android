package net.impulsem.transport.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Collections;
import net.impulsem.transport.errors.RpcError;
import net.impulsem.transport.errors.RpcErrors;
import net.impulsem.transport.grpcweb.GrpcWebClient;
import net.impulsem.transport.grpcweb.GrpcWebResponse;


/**
 * Holds the session, the pending 2FA token and the QR exporter ticket, and refreshes the session
 * with a single in-flight request shared by every waiting caller.
 */
public final class TokenManager {

    private static final long PendingLifetimeMillis = 5 * 60 * 1000L;
    private static final long ProactiveMarginSeconds = 60L;

    private final SessionStore store;
    private final GrpcWebClient client;
    private final Clock clock;

    private final Object refreshLock = new Object();
    private boolean refreshing;
    private long refreshGeneration;
    private boolean lastRefreshResult;
    private IOException lastRefreshFailure;

    private volatile SessionTokens tokens;
    private volatile String pendingToken;
    private volatile long pendingExpiresAt;
    private volatile String qrTicket;


    public TokenManager(
        SessionStore store,
        GrpcWebClient client,
        Clock clock
    ) {
        this.store = store;
        this.client = client;
        this.clock = clock;
        this.tokens = store.load();
    }


    /** The pending token if set and unexpired, else the access token, else null. */
    public String bearer() {
        String pending = pendingToken;
        if (pending != null) {
            if (clock.nowMillis() < pendingExpiresAt) {
                return pending;
            }
            pendingToken = null;
        }
        SessionTokens current = tokens;
        return current == null ? null : current.accessToken;
    }


    public void onLoginTokens(
        String access,
        String refresh,
        long userId
    ) {
        SessionTokens value = new SessionTokens(access, refresh, userId);
        synchronized (this) {
            store.save(value);
            tokens = value;
            pendingToken = null;
        }
    }


    public void setPendingToken(String jwt) {
        pendingExpiresAt = clock.nowMillis() + PendingLifetimeMillis;
        pendingToken = jwt;
    }


    public String qrExporterTicket() {
        return qrTicket;
    }


    public void setQrExporterTicket(String ticket) {
        qrTicket = ticket;
    }


    public boolean hasSession() {
        return tokens != null;
    }


    public void clear() {
        synchronized (this) {
            store.clear();
            tokens = null;
            pendingToken = null;
            qrTicket = null;
        }
    }


    /** True when the access token expires within 60 seconds. Opaque or missing tokens never need it. */
    public boolean needsProactiveRefresh() {
        SessionTokens current = tokens;
        if (current == null || current.accessToken == null || current.refreshToken == null) {
            return false;
        }
        long exp = expirySeconds(current.accessToken);
        if (exp < 0) {
            return false;
        }
        return exp - ProactiveMarginSeconds <= clock.nowMillis() / 1000;
    }


    /**
     * Single-flight: while one refresh is on the wire every other caller waits for its outcome
     * instead of sending a second request, which would burn the already rotated refresh token.
     *
     * @return true when a fresh session is in place; false when the session is unrecoverable
     *     (the store has been cleared).
     * @throws IOException on transport failures and unexpected server errors; the session is kept.
     */
    public boolean refreshBlocking() throws IOException {
        long joinedGeneration;
        synchronized (refreshLock) {
            if (refreshing) {
                joinedGeneration = refreshGeneration;
                while (refreshing && refreshGeneration == joinedGeneration) {
                    try {
                        refreshLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("interrupted while waiting for session refresh");
                    }
                }
                if (lastRefreshFailure != null) {
                    throw new IOException(lastRefreshFailure.getMessage(), lastRefreshFailure);
                }
                return lastRefreshResult;
            }
            refreshing = true;
        }

        boolean result = false;
        IOException failure = null;
        try {
            result = doRefresh();
        } catch (IOException e) {
            failure = e;
        } catch (RuntimeException e) {
            failure = new IOException("session refresh failed: " + e.getMessage(), e);
        } finally {
            synchronized (refreshLock) {
                lastRefreshResult = result;
                lastRefreshFailure = failure;
                refreshGeneration++;
                refreshing = false;
                refreshLock.notifyAll();
            }
        }
        if (failure != null) {
            throw failure;
        }
        return result;
    }


    private boolean doRefresh() throws IOException {
        SessionTokens current = tokens;
        if (current == null || current.refreshToken == null) {
            return false;
        }
        GrpcWebResponse response = client.execute(
            client.newCall(
                ImpulseRpcPaths.RefreshSession,
                ImpulseRpcPaths.encodeRefreshSession(current.refreshToken),
                Collections.<String, String>emptyMap()
            )
        );
        RpcError error = RpcErrors.fromResponse(response);
        if (error != null) {
            if (isUnrecoverable(error)) {
                clear();
                return false;
            }
            throw new IOException("RefreshSession failed: " + error);
        }
        ImpulseRpcPaths.Authorization authorization = ImpulseRpcPaths.decodeAuthorization(response.body);
        if (authorization.sessionToken == null
            || authorization.sessionToken.isEmpty()
            || authorization.refreshToken == null
            || authorization.refreshToken.isEmpty()
        ) {
            throw new IOException("RefreshSession response has no tokens");
        }
        long userId = authorization.userId != 0 ? authorization.userId : current.userId;
        onLoginTokens(authorization.sessionToken, authorization.refreshToken, userId);
        return true;
    }


    private static boolean isUnrecoverable(RpcError error) {
        return "AUTH_TOKEN_INVALID".equals(error.text)
            || "AUTH_TOKEN_EXPIRED".equals(error.text)
            || "SESSION_EXPIRED".equals(error.text);
    }


    /** The exp claim in unix seconds, or -1 when the token is not a readable JWT. */
    private static long expirySeconds(String jwt) {
        try {
            int first = jwt.indexOf('.');
            int second = first < 0 ? -1 : jwt.indexOf('.', first + 1);
            if (first < 0 || second < 0) {
                return -1;
            }
            byte[] payload = decodeBase64Url(jwt.substring(first + 1, second));
            JsonElement root = JsonParser.parseString(new String(payload, "UTF-8"));
            if (!root.isJsonObject()) {
                return -1;
            }
            JsonObject object = root.getAsJsonObject();
            JsonElement exp = object.get("exp");
            if (exp == null || !exp.isJsonPrimitive()) {
                return -1;
            }
            return exp.getAsLong();
        } catch (Exception e) {
            return -1;
        }
    }


    /** Hand-rolled so the module does not need java.util.Base64 (absent below Android API 26). */
    private static byte[] decodeBase64Url(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int value;
            if (c >= 'A' && c <= 'Z') {
                value = c - 'A';
            } else if (c >= 'a' && c <= 'z') {
                value = c - 'a' + 26;
            } else if (c >= '0' && c <= '9') {
                value = c - '0' + 52;
            } else if (c == '-' || c == '+') {
                value = 62;
            } else if (c == '_' || c == '/') {
                value = 63;
            } else if (c == '=') {
                break;
            } else {
                throw new IllegalArgumentException("bad base64url character");
            }
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}
