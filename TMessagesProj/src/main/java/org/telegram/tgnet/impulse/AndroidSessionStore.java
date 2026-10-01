package org.telegram.tgnet.impulse;

import android.content.Context;
import android.content.SharedPreferences;

import net.impulsem.transport.auth.SessionStore;
import net.impulsem.transport.auth.SessionTokens;

import org.telegram.messenger.ApplicationLoader;


/** Keeps one account's ImpulseM session in app-private preferences. */
public final class AndroidSessionStore implements SessionStore {

    private static final String AccessKey = "access";
    private static final String RefreshKey = "refresh";
    private static final String UserIdKey = "user_id";

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


    @Override
    public synchronized void clear() {
        preferences.edit().clear().commit();
    }
}
