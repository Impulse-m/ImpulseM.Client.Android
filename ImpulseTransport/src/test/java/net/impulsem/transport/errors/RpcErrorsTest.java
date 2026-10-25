package net.impulsem.transport.errors;

import static org.junit.Assert.assertEquals;

import org.junit.Test;


public class RpcErrorsTest {

    @Test
    public void classify() {
        assertEquals(
            AuthErrorClass.REFRESH_AND_RETRY,
            RpcErrors.classify(new RpcError(401, "AUTH_KEY_UNREGISTERED"))
        );
        String[] logout = {
            "SESSION_REVOKED",
            "USER_DEACTIVATED",
            "SESSION_EXPIRED",
            "AUTH_TOKEN_EXPIRED",
            "AUTH_KEY_INVALID"
        };
        for (String text : logout) {
            assertEquals(text, AuthErrorClass.FORCE_LOGOUT, RpcErrors.classify(new RpcError(401, text)));
        }
        assertEquals(AuthErrorClass.NONE, RpcErrors.classify(new RpcError(401, "SESSION_PASSWORD_NEEDED")));
        assertEquals(AuthErrorClass.NONE, RpcErrors.classify(new RpcError(400, "PHONE_CODE_INVALID")));
        assertEquals(AuthErrorClass.NONE, RpcErrors.classify(null));
    }


    @Test
    public void floodWait() {
        assertEquals(7, RpcErrors.floodWaitSeconds(new RpcError(420, "FLOOD_WAIT_7")));
        assertEquals(30, RpcErrors.floodWaitSeconds(new RpcError(420, "FLOOD_PREMIUM_WAIT_30")));
        assertEquals(5, RpcErrors.floodWaitSeconds(new RpcError(420, "SLOWMODE_WAIT_5")));
        assertEquals(-1, RpcErrors.floodWaitSeconds(new RpcError(400, "PHONE_CODE_INVALID")));
        assertEquals(-1, RpcErrors.floodWaitSeconds(new RpcError(420, "FLOOD_WAIT_X")));
        assertEquals(-1, RpcErrors.floodWaitSeconds(null));
    }
}
