package org.telegram.tgnet.impulse;

import android.content.Context;
import android.content.SharedPreferences;

import net.impulsem.transport.auth.IdentityManager;
import net.impulsem.transport.auth.SessionStore;
import net.impulsem.transport.auth.SessionTokens;

import org.telegram.messenger.ApplicationLoader;

import java.security.SecureRandom;


/** Keeps one account's ImpulseM session in app-private preferences. */
public final class AndroidSessionStore implements SessionStore {

    private static final String AccessKey = "access";
    private static final String RefreshKey = "refresh";
    private static final String UserIdKey = "user_id";
    private static final String IdentityKey = "identity_token";
    private static final String RpcInstanceKey = "rpc_instance";
    private static final int RpcInstanceBytes = 16;

    private final SharedPreferences preferences;


    public AndroidSessionStore(int account) {
        this.preferences = ApplicationLoader.applicationContext.getSharedPreferences(
            "impulse_session" + (account == 0 ? "" : String.valueOf(account)),
            Context.MODE_PRIVATE
        );
    }


    @Override
    public synchronized SessionTokens load() {
        String access = preferences.getString(AccessKey, null);
        String refresh = preferences.getString(RefreshKey, null);
        if (access == null || access.isEmpty()) {
            return null;
        }
        return new SessionTokens(access, refresh, preferences.getLong(UserIdKey, 0L));
    }


    /** One commit writes both tokens together. */
    @Override
    public synchronized void save(SessionTokens tokens) {
        preferences
            .edit()
            .putString(AccessKey, tokens.accessToken)
            .putString(RefreshKey, tokens.refreshToken)
            .putLong(UserIdKey, tokens.userId)
            .commit();
    }


    /** Removes the session keys only: the identity token and the rpc instance share this file. */
    @Override
    public synchronized void clear() {
        preferences
            .edit()
            .remove(AccessKey)
            .remove(RefreshKey)
            .remove(UserIdKey)
            .commit();
    }


    /** The connection identity token, kept apart from the session pair. */
    public IdentityManager.Store identityStore() {
        return new IdentityManager.Store() {
            @Override
            public String load() {
                String token = preferences.getString(IdentityKey, null);
                return token == null || token.isEmpty() ? null : token;
            }

            @Override
            public void save(String identityToken) {
                preferences.edit().putString(IdentityKey, identityToken).commit();
            }

            @Override
            public void clear() {
                preferences.edit().remove(IdentityKey).commit();
            }
        };
    }


    /**
     * The Centrifugo instance name of this install: random once, then kept, so the server sees one stable client per
     * install. Never logged.
     */
    public synchronized String rpcInstance() {
        String stored = preferences.getString(RpcInstanceKey, null);
        if (stored != null && !stored.isEmpty()) {
            return stored;
        }
        byte[] bytes = new byte[RpcInstanceBytes];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(RpcInstanceBytes * 2);
        for (int index = 0; index < bytes.length; index++) {
            hex.append(String.format("%02x", bytes[index] & 0xff));
        }
        String created = hex.toString();
        preferences.edit().putString(RpcInstanceKey, created).commit();
        return created;
    }
}
