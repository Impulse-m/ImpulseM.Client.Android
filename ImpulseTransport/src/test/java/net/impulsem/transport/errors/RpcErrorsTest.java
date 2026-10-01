package net.impulsem.transport.errors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;
import net.impulsem.transport.grpcweb.GrpcWebResponse;
import org.junit.Test;


public class RpcErrorsTest {

    private static GrpcWebResponse response(
        int status,
        String message,
        String... kv
    ) {
        Map<String, String> metadata = new HashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            metadata.put(kv[i], kv[i + 1]);
        }
        return new GrpcWebResponse(status, message, metadata, new byte[0]);
    }


    private static void assertMapped(
        int status,
        int expectedCode
    ) {
        RpcError error = RpcErrors.fromResponse(response(status, null, "error-code", "SOME_ERROR"));
        assertNotNull(error);
        assertEquals("status " + status, expectedCode, error.code);
        assertEquals("SOME_ERROR", error.text);
    }


    @Test
    public void okIsNull() {
        assertNull(RpcErrors.fromResponse(response(0, null)));
    }


    @Test
    public void statusTable() {
        assertMapped(8, 420);
        assertMapped(3, 400);
        assertMapped(16, 401);
        assertMapped(7, 403);
        assertMapped(5, 404);
        assertMapped(6, 409);
        assertMapped(13, 500);
        assertMapped(12, 501);
        assertMapped(14, 503);
        assertMapped(2, 500);
    }


    @Test
    public void unimplementedWithoutErrorCodeIsMethodInvalid() {
        RpcError error = RpcErrors.fromResponse(response(12, null));
        assertEquals(400, error.code);
        assertEquals("METHOD_INVALID", error.text);
        RpcError withMessage = RpcErrors.fromResponse(response(12, "no such method"));
        assertEquals(400, withMessage.code);
        assertEquals("METHOD_INVALID", withMessage.text);
    }


    @Test
    public void unimplementedWithMethodInvalidErrorCodeIs400() {
        RpcError error = RpcErrors.fromResponse(response(12, null, "error-code", "METHOD_INVALID"));
        assertEquals(400, error.code);
        assertEquals("METHOD_INVALID", error.text);
    }


    @Test
    public void unimplementedWithOtherErrorCodeKeepsServerTextAnd501() {
        RpcError error = RpcErrors.fromResponse(response(12, null, "error-code", "FEATURE_OFF"));
        assertEquals(501, error.code);
        assertEquals("FEATURE_OFF", error.text);
    }


    @Test
    public void failedPreconditionWithoutMigrateDcIs406() {
        assertMapped(9, 406);
    }


    @Test
    public void failedPreconditionWithMigrateDcIs303() {
        RpcError error = RpcErrors.fromResponse(
            response(9, null, "error-code", "NETWORK_MIGRATE_2", "migrate-dc", "2")
        );
        assertEquals(303, error.code);
        assertEquals("NETWORK_MIGRATE_2", error.text);
    }


    @Test
    public void textFallsBackToGrpcMessageThenStatusName() {
        assertEquals("boom", RpcErrors.fromResponse(response(13, "boom")).text);
        assertEquals("INTERNAL", RpcErrors.fromResponse(response(13, null)).text);
        assertEquals("INTERNAL", RpcErrors.fromResponse(response(13, "")).text);
        assertEquals("UNAUTHENTICATED", RpcErrors.fromResponse(response(16, null)).text);
    }


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
