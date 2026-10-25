package net.impulsem.transport.auth;

import java.util.Random;


/**
 * Draws the moment an access token is refreshed: uniformly inside the window that opens 300 s before its expiry and
 * closes 120 s before it. A token with less than 300 s left opens the window at the moment it was stored. The draw
 * spreads refresh load across clients, so a non-cryptographic random source is enough.
 */
final class RefreshSchedule {

    static final long WindowStartBeforeExpirySeconds = 300L;
    static final long WindowEndBeforeExpirySeconds = 120L;

    private final Random random;


    RefreshSchedule(Random random) {
        this.random = random;
    }


    long drawRefreshPointMillis(
        long expSeconds,
        long nowMillis
    ) {
        long endMillis = (expSeconds - WindowEndBeforeExpirySeconds) * 1000L;
        if (endMillis <= nowMillis) {
            return nowMillis;
        }
        long startMillis = Math.max(nowMillis, (expSeconds - WindowStartBeforeExpirySeconds) * 1000L);
        return startMillis + (long) (random.nextDouble() * (endMillis - startMillis));
    }
}
