package net.impulsem.transport.auth;

import java.io.IOException;


/** What the update socket presents: the access token with a session, else the connection identity. */
public final class Credentials {

    private final TokenManager tokens;
    private final IdentityManager identity;


    public Credentials(
        TokenManager tokens,
        IdentityManager identity
    ) {
        this.tokens = tokens;
        this.identity = identity;
    }


    /** True when the slot has a session, so the socket presents the access token. */
    public boolean signedIn() {
        return tokens.hasSession();
    }


    /** The credential the socket presents now, or null when none is known yet. Never sends anything. */
    public String current() {
        return signedIn() ? tokens.bearer() : identity.current();
    }


    /**
     * The token to connect with. Signed in: the session's connection token, and when that is null (the session ended
     * during the refresh) the identity. Signed out: the identity.
     */
    public String connectTokenBlocking() throws IOException {
        if (signedIn()) {
            String token = tokens.connectionTokenBlocking();
            if (token != null) {
                return token;
            }
        }
        return identity.obtainBlocking();
    }


    /**
     * The server refused this credential. The access token is marked refused, so the next refresh goes through the
     * pool fleet; an identity token is discarded and its listener told to reconnect.
     */
    public void refused(String credential) {
        if (credential == null) {
            return;
        }
        if (signedIn() && credential.equals(tokens.bearer())) {
            tokens.markRefused(credential);
            return;
        }
        identity.refuse(credential);
    }
}
