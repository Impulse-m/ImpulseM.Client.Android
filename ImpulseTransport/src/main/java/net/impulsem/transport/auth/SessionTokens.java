package net.impulsem.transport.auth;


public final class SessionTokens {

    public final String accessToken;
    public final String refreshToken;
    public final long userId;


    public SessionTokens(
        String accessToken,
        String refreshToken,
        long userId
    ) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.userId = userId;
    }
}
