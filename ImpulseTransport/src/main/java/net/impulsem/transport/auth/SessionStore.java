package net.impulsem.transport.auth;


/** Persistent storage for the session. Implementations must make save() atomic for both tokens. */
public interface SessionStore {

    /** Returns null when there is no stored session. */
    SessionTokens load();

    void save(SessionTokens tokens);

    void clear();
}
