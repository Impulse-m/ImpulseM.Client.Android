package net.impulsem.transport.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.impulsem.transport.errors.RpcError;
import org.junit.Test;


public class RequestPolicyTest {

    private static final int Generic = RequestPolicy.CONNECTION_TYPE_GENERIC;
    private static final int Download = RequestPolicy.CONNECTION_TYPE_DOWNLOAD;
    private static final int Upload = RequestPolicy.CONNECTION_TYPE_UPLOAD;


    // ConnectionsManager.cpp 1350-1351, 1375-1411: without FailOnServerErrors a flood wait is waited out.
    @Test
    public void floodWaitWithoutFlagsRetriesAfterTheAnnouncedTime() {
        RequestPolicy.Decision decision = RequestPolicy.onError(0, Generic, new RpcError(420, "FLOOD_WAIT_5"), 0);
        assertEquals(RequestPolicy.Action.RETRY_AFTER, decision.action);
        assertEquals(5000L, decision.delayMillis);
    }


    // ConnectionsManager.cpp 1350: failServerErrors is false when the flag is set, so the error is delivered.
    @Test
    public void floodWaitWithFailOnServerErrorsIsDelivered() {
        RequestPolicy.Decision decision = RequestPolicy.onError(
            RequestPolicy.FLAG_FAIL_ON_SERVER_ERRORS,
            Generic,
            new RpcError(420, "FLOOD_WAIT_5"),
            0
        );
        assertEquals(RequestPolicy.Action.DELIVER, decision.action);
    }


    // ConnectionsManager.cpp 1351, 1375: exceptFloodWait re-enables the flood wait branch.
    @Test
    public void floodWaitWithExceptFloodWaitFlagRetries() {
        RequestPolicy.Decision decision = RequestPolicy.onError(
            RequestPolicy.FLAG_FAIL_ON_SERVER_ERRORS | RequestPolicy.FLAG_FAIL_ON_SERVER_ERRORS_EXCEPT_FLOOD_WAIT,
            Generic,
            new RpcError(420, "FLOOD_WAIT_5"),
            0
        );
        assertEquals(RequestPolicy.Action.RETRY_AFTER, decision.action);
        assertEquals(5000L, decision.delayMillis);
    }


    // ConnectionsManager.cpp 1376: RequestFlagIgnoreFloodWait (1024) skips the wait.
    @Test
    public void floodWaitWithDoNotWaitFlagIsDelivered() {
        RequestPolicy.Decision decision = RequestPolicy.onError(
            RequestPolicy.FLAG_DO_NOT_WAIT_FLOOD_WAIT,
            Generic,
            new RpcError(420, "FLOOD_WAIT_5"),
            0
        );
        assertEquals(RequestPolicy.Action.DELIVER, decision.action);
    }


    // ConnectionsManager.cpp 1377: STORY_SEND_FLOOD is never waited out.
    @Test
    public void storySendFloodIsDelivered() {
        RequestPolicy.Decision decision = RequestPolicy.onError(0, Generic, new RpcError(420, "STORY_SEND_FLOOD_X"), 0);
        assertEquals(RequestPolicy.Action.DELIVER, decision.action);
    }


    // ConnectionsManager.cpp 1396-1404: slow mode waits are recorded but the response is not discarded.
    @Test
    public void slowmodeWaitIsDelivered() {
        RequestPolicy.Decision decision = RequestPolicy.onError(0, Generic, new RpcError(420, "SLOWMODE_WAIT_30"), 0);
        assertEquals(RequestPolicy.Action.DELIVER, decision.action);
    }


    // ConnectionsManager.cpp 1382-1389: a premium flood wait is retried after its own time.
    @Test
    public void premiumFloodWaitRetries() {
        RequestPolicy.Decision decision = RequestPolicy.onError(0, Generic, new RpcError(420, "FLOOD_PREMIUM_WAIT_7"), 0);
        assertEquals(RequestPolicy.Action.RETRY_AFTER, decision.action);
        assertEquals(7000L, decision.delayMillis);
    }


    // ConnectionsManager.cpp 1383-1393: an unparsable or non-positive wait falls back to 2 seconds.
    @Test
    public void floodWaitWithoutNumberWaitsTwoSeconds() {
        RequestPolicy.Decision decision = RequestPolicy.onError(0, Generic, new RpcError(420, "FLOOD_WAIT_"), 0);
        assertEquals(RequestPolicy.Action.RETRY_AFTER, decision.action);
        assertEquals(2000L, decision.delayMillis);
    }


    // ConnectionsManager.cpp 1356-1366: 500 retries with serverFailureCount seconds, capped at 10.
    @Test
    public void serverErrorWithoutFlagsBacksOff() {
        RequestPolicy.Decision first = RequestPolicy.onError(0, Generic, new RpcError(500, "INTERNAL"), 3);
        assertEquals(RequestPolicy.Action.RETRY_AFTER, first.action);
        assertEquals(3000L, first.delayMillis);
        RequestPolicy.Decision capped = RequestPolicy.onError(0, Generic, new RpcError(500, "INTERNAL"), 50);
        assertEquals(10000L, capped.delayMillis);
        RequestPolicy.Decision initial = RequestPolicy.onError(0, Generic, new RpcError(500, "INTERNAL"), 0);
        assertEquals(RequestPolicy.Action.RETRY_AFTER, initial.action);
        assertTrue(initial.delayMillis >= 0L);
    }


    // ConnectionsManager.cpp 1350: with FailOnServerErrors a 500 is delivered.
    @Test
    public void serverErrorWithFailOnServerErrorsIsDelivered() {
        RequestPolicy.Decision decision = RequestPolicy.onError(
            RequestPolicy.FLAG_FAIL_ON_SERVER_ERRORS,
            Generic,
            new RpcError(500, "INTERNAL"),
            0
        );
        assertEquals(RequestPolicy.Action.DELIVER, decision.action);
    }


    // ConnectionsManager.cpp 1344-1350: AUTH_RESTART is processed even when FailOnServerErrors is set.
    @Test
    public void authRestartRetriesEvenWithFailOnServerErrors() {
        RequestPolicy.Decision decision = RequestPolicy.onError(
            RequestPolicy.FLAG_FAIL_ON_SERVER_ERRORS,
            Generic,
            new RpcError(500, "AUTH_RESTART"),
            1
        );
        assertEquals(RequestPolicy.Action.RETRY_AFTER, decision.action);
    }


    // ConnectionsManager.cpp 1361: WORKER_BUSY_TOO_LONG_RETRY restarts immediately.
    @Test
    public void workerBusyRetriesImmediately() {
        RequestPolicy.Decision decision = RequestPolicy.onError(0, Generic, new RpcError(500, "WORKER_BUSY_TOO_LONG_RETRY"), 4);
        assertEquals(RequestPolicy.Action.RETRY_AFTER, decision.action);
        assertEquals(0L, decision.delayMillis);
    }


    // ConnectionsManager.cpp 1417-1424: 400 MSG_WAIT_* is resent.
    @Test
    public void msgWaitFailedRetries() {
        RequestPolicy.Decision decision = RequestPolicy.onError(0, Generic, new RpcError(400, "MSG_WAIT_FAILED"), 0);
        assertEquals(RequestPolicy.Action.RETRY_AFTER, decision.action);
    }


    // ConnectionsManager.cpp 1368-1373: -504 waits two seconds unless IgnoreFloodWait.
    @Test
    public void error504WaitsTwoSecondsUnlessIgnored() {
        RequestPolicy.Decision decision = RequestPolicy.onError(0, Generic, new RpcError(-504, "TIMEOUT"), 0);
        assertEquals(RequestPolicy.Action.RETRY_AFTER, decision.action);
        assertEquals(2000L, decision.delayMillis);
        RequestPolicy.Decision ignored = RequestPolicy.onError(
            RequestPolicy.FLAG_DO_NOT_WAIT_FLOOD_WAIT,
            Generic,
            new RpcError(-504, "TIMEOUT"),
            0
        );
        assertEquals(RequestPolicy.Action.DELIVER, ignored.action);
    }


    // ConnectionsManager.cpp 1519-1535: a revoked session logs the app out after delivering the error.
    @Test
    public void sessionRevokedLogsOut() {
        RequestPolicy.Decision decision = RequestPolicy.onError(0, Generic, new RpcError(401, "SESSION_REVOKED"), 0);
        assertEquals(RequestPolicy.Action.LOGOUT_AND_DELIVER, decision.action);
    }


    // ConnectionsManager.cpp 1522-1524: SESSION_PASSWORD_NEEDED is ignored.
    @Test
    public void sessionPasswordNeededIsDelivered() {
        RequestPolicy.Decision decision = RequestPolicy.onError(0, Generic, new RpcError(401, "SESSION_PASSWORD_NEEDED"), 0);
        assertEquals(RequestPolicy.Action.DELIVER, decision.action);
    }


    // ConnectionsManager.cpp 1350: other client errors are delivered as they are.
    @Test
    public void plainClientErrorsAreDelivered() {
        assertEquals(RequestPolicy.Action.DELIVER, RequestPolicy.onError(0, Generic, new RpcError(400, "PHONE_CODE_INVALID"), 0).action);
        assertEquals(RequestPolicy.Action.DELIVER, RequestPolicy.onError(0, Generic, new RpcError(403, "CHAT_WRITE_FORBIDDEN"), 0).action);
        assertEquals(RequestPolicy.Action.DELIVER, RequestPolicy.onError(0, Generic, new RpcError(401, "AUTH_KEY_UNREGISTERED"), 0).action);
    }


    // ConnectionsManager.cpp 2616-2635: download retryMax is 6, so the sixth failure delivers -123 RETRY_LIMIT.
    @Test
    public void downloadNetworkFailureStopsAtSixAttempts() {
        RequestPolicy.Decision fifth = RequestPolicy.onNetworkFailure(0, Download, 5);
        assertEquals(RequestPolicy.Action.RETRY_AFTER, fifth.action);
        RequestPolicy.Decision sixth = RequestPolicy.onNetworkFailure(0, Download, 6);
        assertEquals(RequestPolicy.Action.DELIVER, sixth.action);
        assertEquals(-123, sixth.errorCode);
        assertEquals("RETRY_LIMIT", sixth.errorText);
    }


    // ConnectionsManager.cpp 2621-2622: ForceDownload raises retryMax to 10.
    @Test
    public void forceDownloadStopsAtTenAttempts() {
        RequestPolicy.Decision ninth = RequestPolicy.onNetworkFailure(RequestPolicy.FLAG_FORCE_DOWNLOAD, Download, 9);
        assertEquals(RequestPolicy.Action.RETRY_AFTER, ninth.action);
        RequestPolicy.Decision tenth = RequestPolicy.onNetworkFailure(RequestPolicy.FLAG_FORCE_DOWNLOAD, Download, 10);
        assertEquals(RequestPolicy.Action.DELIVER, tenth.action);
        assertEquals(-123, tenth.errorCode);
    }


    // ConnectionsManager.cpp 2619: the retry limit applies only to download connections.
    @Test
    public void genericAndUploadNetworkFailuresRetryForever() {
        assertEquals(RequestPolicy.Action.RETRY_AFTER, RequestPolicy.onNetworkFailure(0, Generic, 100).action);
        assertEquals(RequestPolicy.Action.RETRY_AFTER, RequestPolicy.onNetworkFailure(0, Upload, 100).action);
        assertNull(RequestPolicy.onNetworkFailure(0, Generic, 1).errorText);
    }


    @Test
    public void networkFailureBackoffGrowsAndIsCapped() {
        long first = RequestPolicy.onNetworkFailure(0, Generic, 1).delayMillis;
        long later = RequestPolicy.onNetworkFailure(0, Generic, 4).delayMillis;
        long capped = RequestPolicy.onNetworkFailure(0, Generic, 500).delayMillis;
        assertTrue(first > 0L);
        assertTrue(later > first);
        assertEquals(10000L, capped);
    }


    // ConnectionsManager.cpp 1901, 1932, 1960: without a user only WithoutLogin requests are sent.
    @Test
    public void mustWaitForLoginOnlyWhenLoggedOutAndNotWithoutLogin() {
        assertTrue(RequestPolicy.mustWaitForLogin(0, false));
        assertTrue(RequestPolicy.mustWaitForLogin(RequestPolicy.FLAG_FAIL_ON_SERVER_ERRORS, false));
        assertFalse(RequestPolicy.mustWaitForLogin(RequestPolicy.FLAG_WITHOUT_LOGIN, false));
        assertFalse(RequestPolicy.mustWaitForLogin(0, true));
        assertFalse(RequestPolicy.mustWaitForLogin(RequestPolicy.FLAG_WITHOUT_LOGIN, true));
    }
}
