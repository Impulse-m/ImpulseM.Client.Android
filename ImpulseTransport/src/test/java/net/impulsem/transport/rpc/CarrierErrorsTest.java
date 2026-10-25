package net.impulsem.transport.rpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.centrifugal.centrifuge.ReplyError;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import net.impulsem.transport.errors.RpcError;
import org.junit.Test;


public class CarrierErrorsTest {

    private static final int ServerCode = 100;


    private static Map<String, String> metadata(
        String key,
        String value
    ) {
        Map<String, String> map = new HashMap<String, String>();
        map.put(key, value);
        return map;
    }


    private static CarrierFailure appError(
        int appCode,
        String appType,
        Map<String, String> metadata
    ) {
        return CarrierFailure.of(new ReplyError(ServerCode, "server", false, appCode, appType, metadata));
    }


    private static CarrierFailure sdkError(int sdkCode) {
        return CarrierFailure.of(new ReplyError(sdkCode, "sdk", false));
    }


    // Web K fleetError.ts: a flood wait keeps its own text and code.
    @Test
    public void floodWaitAppErrorKeepsItsCodeAndText() {
        RpcError error = CarrierErrors.toRpcError(appError(420, "FLOOD_WAIT_7", Collections.<String, String>emptyMap()));
        assertEquals(420, error.code);
        assertEquals("FLOOD_WAIT_7", error.text);
    }


    // A USER_MIGRATE without a migrate-dc metadata key keeps the app code 303.
    @Test
    public void userMigrateWithoutMetadataKeepsAppCode() {
        RpcError error = CarrierErrors.toRpcError(appError(303, "USER_MIGRATE_2", Collections.<String, String>emptyMap()));
        assertEquals(303, error.code);
        assertEquals("USER_MIGRATE_2", error.text);
    }


    // A migrate-dc key turns any app error into the 303 migration, whatever its app code.
    @Test
    public void migrateDcMetadataMakesTheErrorAMigration() {
        RpcError error = CarrierErrors.toRpcError(appError(400, "PHONE_MIGRATE_2", metadata("migrate-dc", "2")));
        assertEquals(303, error.code);
        assertEquals("PHONE_MIGRATE_2", error.text);
    }


    @Test
    public void appErrorWithoutAppCodeIsA400() {
        RpcError error = CarrierErrors.toRpcError(appError(0, "PHONE_CODE_INVALID", Collections.<String, String>emptyMap()));
        assertEquals(400, error.code);
        assertEquals("PHONE_CODE_INVALID", error.text);
    }


    @Test
    public void tokenInvalidSdkCodeMapsToAuthTokenInvalid() {
        RpcError error = CarrierErrors.toRpcError(sdkError(103));
        assertEquals(400, error.code);
        assertEquals("AUTH_TOKEN_INVALID", error.text);
    }


    @Test
    public void tokenExpiredSdkCodeMapsToAuthTokenExpired() {
        RpcError error = CarrierErrors.toRpcError(sdkError(109));
        assertEquals(400, error.code);
        assertEquals("AUTH_TOKEN_EXPIRED", error.text);
    }


    @Test
    public void methodInvalidSdkCodeMapsToMethodInvalid() {
        RpcError error = CarrierErrors.toRpcError(sdkError(104));
        assertEquals(400, error.code);
        assertEquals("METHOD_INVALID", error.text);
    }


    // A server code without an app type is not a Telegram error; the call takes the network-failure path.
    @Test
    public void serverCodeWithoutAppTypeIsNotAnRpcError() {
        assertNull(CarrierErrors.toRpcError(sdkError(ServerCode)));
    }


    @Test
    public void ioExceptionIsNotAnRpcError() {
        assertNull(CarrierErrors.toRpcError(CarrierFailure.of(new IOException("lost"))));
    }


    @Test
    public void timeoutIsNotAnRpcError() {
        assertNull(CarrierErrors.toRpcError(CarrierFailure.of(new TimeoutException())));
    }


    @Test
    public void cancelledSdkCodeIsNotAnRpcError() {
        assertNull(CarrierErrors.toRpcError(sdkError(ReplyError.CANCELLED)));
    }


    @Test
    public void cancelledSdkCodeIsCancelled() {
        assertTrue(CarrierErrors.isCancelled(sdkError(ReplyError.CANCELLED)));
        assertFalse(CarrierErrors.isCancelled(sdkError(ReplyError.LIMIT_EXCEEDED)));
        assertFalse(CarrierErrors.isCancelled(CarrierFailure.of(new IOException("x"))));
    }


    @Test
    public void tokenRefusalIsTrueForTheTwoTokenCodesOnly() {
        assertTrue(CarrierErrors.isTokenRefusal(sdkError(103)));
        assertTrue(CarrierErrors.isTokenRefusal(sdkError(109)));
        assertFalse(CarrierErrors.isTokenRefusal(sdkError(104)));
        assertFalse(CarrierErrors.isTokenRefusal(CarrierFailure.of(new IOException("x"))));
    }


    @Test
    public void ofNonReplyErrorCarriesOnlyTheCause() {
        IOException cause = new IOException("x");
        CarrierFailure failure = CarrierFailure.of(cause);
        assertEquals(0, failure.sdkCode);
        assertEquals(0, failure.appCode);
        assertEquals("", failure.appType);
        assertTrue(failure.metadata.isEmpty());
        assertSame(cause, failure.cause);
    }


    @Test
    public void ofReplyErrorCopiesTheFields() {
        CarrierFailure failure = appError(420, "FLOOD_WAIT_7", metadata("k", "v"));
        assertEquals(ServerCode, failure.sdkCode);
        assertEquals(420, failure.appCode);
        assertEquals("FLOOD_WAIT_7", failure.appType);
        assertEquals("v", failure.metadata.get("k"));
    }


    @Test
    public void metadataIsUnmodifiable() {
        CarrierFailure failure = appError(420, "FLOOD_WAIT_7", metadata("k", "v"));
        try {
            failure.metadata.put("x", "y");
            fail("metadata must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            assertFalse(failure.metadata.containsKey("x"));
        }
    }


    @Test(expected = NullPointerException.class)
    public void ofNullThrows() {
        CarrierFailure.of(null);
    }
}
